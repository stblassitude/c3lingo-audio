package org.c3lingo.audio

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel as MessageQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** [unavailable]: the server is reachable but won't let us join, as opposed to a network problem. */
class GaleneException(message: String, val unavailable: Boolean = false) : Exception(message)

/**
 * A receive-only client for one Galène group, speaking version 2 of the Galène protocol.
 *
 * Galène sends one offer per remote stream; each one gets its own peer connection.
 * The session doesn't retry on its own: once it fails or closes it's done, and the
 * owner creates a new one. Everything runs on the main thread.
 */
class GaleneSession(
    private val context: Context,
    private val endpoint: Uri,
    private val group: String,
    private val http: OkHttpClient,
) {
    sealed interface Event {
        /** Audio is flowing from at least one stream. */
        data object Receiving : Event
        /**
         * Joined, but nobody is sending: no stream was offered after joining, or the
         * last one was closed because the sender stopped. Audio follows when they start.
         */
        data object Waiting : Event
        data class Failed(val error: Throwable) : Event
    }

    var onEvent: ((Event) -> Unit)? = null

    var isMuted = false
        set(value) {
            field = value
            downstreams.values.forEach { it.setMuted(value) }
        }

    private val id = "app-" + UUID.randomUUID().toString().take(8)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val incoming = MessageQueue<String>(MessageQueue.UNLIMITED)
    private var socket: WebSocket? = null
    private val downstreams = mutableMapOf<String, Downstream>()
    private var iceServers = emptyList<PeerConnection.IceServer>()
    private var offerTimeout: Job? = null
    /** Streams offered but not yet connected, failing when ICE doesn't get through. */
    private val mediaTimeouts = mutableMapOf<String, Job>()
    private var lastReceived = SystemClock.elapsedRealtime()
    private var isClosed = false

    fun start() {
        Log.i(TAG, "connecting to $endpoint for group $group")
        socket = http.newWebSocket(Request.Builder().url(endpoint.toString()).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    fail(GaleneException(reason.ifEmpty { "The server closed the connection" }))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { fail(t) }
            }
        })
        send(JSONObject().put("type", "handshake").put("version", JSONArray().put("2")).put("id", id))
        // Messages are handled strictly in order: ICE candidates follow right after an
        // offer and need its peer connection.
        scope.launch {
            for (text in incoming) {
                lastReceived = SystemClock.elapsedRealtime()
                handle(text)
            }
        }
        scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (SystemClock.elapsedRealtime() - lastReceived > TIMEOUT_MS) {
                    fail(GaleneException("The server stopped responding"))
                    return@launch
                }
                send(JSONObject().put("type", "ping"))
                if (BuildConfig.DEBUG) downstreams.values.forEach { it.logStatistics() }
            }
        }
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        scope.cancel()
        incoming.close()
        downstreams.values.forEach { it.close() }
        downstreams.clear()
        socket?.close(1001, null)
        socket = null
        Log.i(TAG, "closed session for group $group")
    }

    private fun fail(error: Throwable) {
        if (isClosed) return
        Log.e(TAG, "session for group $group failed", error)
        close()
        onEvent?.invoke(Event.Failed(error))
    }

    // Signalling

    private fun send(message: JSONObject) {
        if (isClosed) return
        val socket = socket ?: return
        if (!socket.send(message.toString())) {
            fail(GaleneException("The audio connection failed"))
        }
    }

    private suspend fun handle(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull()
        val type = message?.string("type")
        if (type == null) {
            Log.w(TAG, "ignoring malformed message $text")
            return
        }
        when (type) {
            "handshake" -> send(JSONObject().put("type", "join").put("kind", "join").put("group", group).put("username", id))
            "ping" -> send(JSONObject().put("type", "pong"))
            "joined" -> handleJoined(message)
            "offer" -> handleOffer(message)
            "ice" -> handleRemoteCandidate(message)
            "close" -> message.string("id")?.let { closeDownstream(it) }
            "usermessage" -> if (message.string("kind") == "error") Log.e(TAG, "server error: ${message.opt("value")}")
        }
    }

    private fun handleJoined(message: JSONObject) {
        when (message.string("kind")) {
            "join" -> {
                iceServers = parseIceServers(message.optJSONObject("rtcConfiguration"))
                send(JSONObject().put("type", "request").put("request", JSONObject().put("", JSONArray().put("audio"))))
                offerTimeout = scope.launch {
                    delay(OFFER_TIMEOUT_MS)
                    if (downstreams.isEmpty()) onEvent?.invoke(Event.Waiting)
                }
            }
            "fail" -> fail(GaleneException("Unable to join the channel: ${message.string("value") ?: "unknown reason"}", unavailable = true))
            "leave" -> fail(GaleneException(message.string("value") ?: "The server closed the connection", unavailable = true))
        }
    }

    private suspend fun handleOffer(message: JSONObject) {
        val streamId = message.string("id") ?: return
        val sdp = message.string("sdp") ?: return
        // Galène's web client labels its streams "camera", WHIP streams have no label.
        val label = message.string("label")
        if (label != null && label != "camera") {
            Log.i(TAG, "ignoring stream $streamId with label $label")
            send(JSONObject().put("type", "abort").put("id", streamId))
            return
        }
        message.string("replace")?.let { closeDownstream(it) }

        val downstream = downstreams[streamId] ?: run {
            val created = Downstream.create(streamId, iceServers, this)
            if (created == null) {
                fail(GaleneException("The audio connection failed"))
                return
            }
            created.setMuted(isMuted)
            downstreams[streamId] = created
            offerTimeout?.cancel()
            mediaTimeouts[streamId] = scope.launch {
                delay(MEDIA_TIMEOUT_MS)
                fail(GaleneException("The audio connection timed out"))
            }
            created
        }

        try {
            val answer = downstream.answer(sdp)
            Log.i(TAG, "answering offer for stream $streamId")
            send(JSONObject().put("type", "answer").put("id", streamId).put("sdp", answer))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "unable to answer offer for stream $streamId", e)
            closeDownstream(streamId)
            send(JSONObject().put("type", "abort").put("id", streamId))
        }
    }

    private fun handleRemoteCandidate(message: JSONObject) {
        val streamId = message.string("id") ?: return
        val candidate = message.optJSONObject("candidate") ?: return
        val sdp = candidate.string("candidate") ?: return
        Log.d(TAG, "stream $streamId remote candidate $sdp")
        downstreams[streamId]?.add(IceCandidate(candidate.string("sdpMid") ?: "", candidate.optInt("sdpMLineIndex"), sdp))
    }

    private fun closeDownstream(streamId: String) {
        val downstream = downstreams.remove(streamId) ?: return
        mediaTimeouts.remove(streamId)?.cancel()
        downstream.close()
        if (downstreams.isEmpty()) onEvent?.invoke(Event.Waiting)
    }

    // Events from the peer connections, delivered on WebRTC's signalling thread

    internal val peerConnectionFactory get() = WebRtc.factory(context)

    internal fun onLocalCandidate(streamId: String, candidate: IceCandidate) {
        scope.launch {
            val candidateInit = JSONObject().put("candidate", candidate.sdp).put("sdpMLineIndex", candidate.sdpMLineIndex)
            candidate.sdpMid?.let { candidateInit.put("sdpMid", it) }
            send(JSONObject().put("type", "ice").put("id", streamId).put("candidate", candidateInit))
        }
    }

    internal fun onConnectionChange(streamId: String, state: PeerConnection.PeerConnectionState) {
        Log.i(TAG, "stream $streamId connection state $state")
        scope.launch {
            if (downstreams[streamId] == null) return@launch
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    mediaTimeouts.remove(streamId)?.cancel()
                    onEvent?.invoke(Event.Receiving)
                }
                PeerConnection.PeerConnectionState.FAILED -> fail(GaleneException("The audio connection failed"))
                else -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "galene"
        const val PING_INTERVAL_MS = 5_000L
        const val TIMEOUT_MS = 15_000L
        /** After joining, Galène offers the streams that are already there right away. */
        const val OFFER_TIMEOUT_MS = 2_000L
        /** Time for ICE to connect after answering an offer. */
        const val MEDIA_TIMEOUT_MS = 15_000L

        fun parseIceServers(configuration: JSONObject?): List<PeerConnection.IceServer> {
            val servers = configuration?.optJSONArray("iceServers") ?: return emptyList()
            return (0 until servers.length()).mapNotNull { index ->
                val server = servers.optJSONObject(index) ?: return@mapNotNull null
                val urls = server.optJSONArray("urls")?.let { array -> (0 until array.length()).mapNotNull { array.opt(it) as? String } }
                    ?: listOfNotNull(server.string("urls"))
                if (urls.isEmpty()) return@mapNotNull null
                PeerConnection.IceServer.builder(urls)
                    .setUsername(server.string("username").orEmpty())
                    .setPassword(server.string("credential").orEmpty())
                    .createIceServer()
            }
        }
    }
}

/** One stream sent by the server, received on its own peer connection. */
private class Downstream private constructor(val id: String) {
    private lateinit var connection: PeerConnection
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var hasRemoteDescription = false
    private var isClosed = false

    /** Applies the server's offer and returns the SDP of the answer. */
    suspend fun answer(offer: String): String {
        awaitSet { connection.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, offer)) }
        hasRemoteDescription = true
        pendingCandidates.forEach(::addNow)
        pendingCandidates.clear()
        val answer = awaitCreate { connection.createAnswer(it, MediaConstraints()) }
        awaitSet { connection.setLocalDescription(it, answer) }
        return answer.description
    }

    fun add(candidate: IceCandidate) {
        // Candidates can arrive while the offer is still being applied.
        if (hasRemoteDescription) addNow(candidate) else pendingCandidates.add(candidate)
    }

    private fun addNow(candidate: IceCandidate) {
        if (!isClosed && !connection.addIceCandidate(candidate)) {
            Log.w(TAG, "unable to add remote candidate ${candidate.sdp}")
        }
    }

    fun setMuted(muted: Boolean) {
        if (isClosed) return
        connection.transceivers.forEach { it.receiver.track()?.setEnabled(!muted) }
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        connection.dispose()
    }

    fun logStatistics() {
        if (isClosed) return
        connection.getStats { report ->
            report.statsMap.values.filter { it.type == "inbound-rtp" }.forEach { stats ->
                val value = { key: String -> stats.members[key]?.toString() ?: "-" }
                Log.d(TAG, "stream $id: ${value("bytesReceived")} bytes, ${value("totalSamplesReceived")} samples, level ${value("audioLevel")}")
            }
        }
    }

    /** Runs an operation on the peer connection; the result arrives on the signalling thread. */
    private suspend fun awaitSet(operation: (SdpObserver) -> Unit) {
        checkOpen()
        suspendCancellableCoroutine { continuation ->
            operation(object : SdpObserverAdapter() {
                override fun onSetSuccess() = continuation.resume(Unit)
                override fun onSetFailure(error: String?) = continuation.resumeWithException(GaleneException(error.orEmpty()))
            })
        }
        checkOpen()
    }

    private suspend fun awaitCreate(operation: (SdpObserver) -> Unit): SessionDescription {
        checkOpen()
        val description = suspendCancellableCoroutine { continuation ->
            operation(object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)
                override fun onCreateFailure(error: String?) = continuation.resumeWithException(GaleneException(error.orEmpty()))
            })
        }
        checkOpen()
        return description
    }

    /** The connection may have been closed while waiting; it must not be touched then. */
    private fun checkOpen() {
        if (isClosed) throw CancellationException("stream $id closed")
    }

    companion object {
        private const val TAG = "galene"

        fun create(id: String, iceServers: List<PeerConnection.IceServer>, session: GaleneSession): Downstream? {
            val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val downstream = Downstream(id)
            val observer = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "stream $id local candidate ${candidate.sdp}")
                    session.onLocalCandidate(id, candidate)
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) =
                    session.onConnectionChange(id, newState)

                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    Log.i(TAG, "stream $id added track ${receiver.track()?.kind()}")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "stream $id ICE connection state $state")
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
            }
            val connection = session.peerConnectionFactory.createPeerConnection(configuration, observer)
                ?: return null
            downstream.connection = connection
            return downstream
        }
    }
}

private abstract class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
