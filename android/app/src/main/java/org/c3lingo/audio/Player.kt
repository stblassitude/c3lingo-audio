package org.c3lingo.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.util.UnstableApi
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Plays one channel at a time and keeps it playing: reconnects with backoff when the
 * connection drops, after network changes and after audio interruptions such as calls.
 * Everything runs on the main thread.
 */
@OptIn(UnstableApi::class)
class Player(private val context: Context, private val settings: ServerSettings) {
    enum class Status {
        IDLE,
        CONNECTING,
        /** Joined, but nobody is sending audio right now. */
        WAITING,
        PLAYING,
        PAUSED,
        RECONNECTING,
    }

    data class State(
        val channel: Channel? = null,
        val status: Status = Status.IDLE,
        val lastError: String? = null,
        val isMuted: Boolean = false,
    ) {
        val isActive: Boolean
            get() = channel != null && status != Status.PAUSED
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = MainScope()
    private val http = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var session: GaleneSession? = null
    private var reconnectJob: Job? = null
    private var attempt = 0
    private var networkAvailable = true
    private var hasAudioFocus = false
    /** Paused because another app took audio focus for a while, e.g. for a call. */
    private var interrupted = false

    private val focusRequest = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build())
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(::onAudioFocusChange, mainHandler)
        .build()

    /** Headphones were unplugged or Bluetooth disconnected: pause rather than play out loud. */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private var noisyReceiverRegistered = false

    init {
        observeNetwork()
    }

    // Controls

    fun play(channel: Channel) {
        Log.i(TAG, "play ${channel.name}")
        _state.update { it.copy(channel = channel, lastError = null) }
        attempt = 0
        interrupted = false
        if (!acquireAudio()) {
            pause()
            return
        }
        connect(Status.CONNECTING)
    }

    /** Disconnects but keeps the channel, so playback can be resumed. */
    fun pause() {
        if (state.value.channel == null) return
        teardown()
        if (!interrupted) releaseAudio()
        _state.update { it.copy(status = Status.PAUSED) }
    }

    fun resume() {
        state.value.channel?.let(::play)
    }

    fun togglePause() {
        if (state.value.status == Status.PAUSED) resume() else pause()
    }

    /** Stops playback and forgets the channel. */
    fun stop() {
        if (state.value.channel == null) return
        Log.i(TAG, "stop")
        teardown()
        interrupted = false
        releaseAudio()
        _state.update { it.copy(channel = null, status = Status.IDLE, lastError = null) }
    }

    fun setMuted(muted: Boolean) {
        session?.isMuted = muted
        _state.update { it.copy(isMuted = muted) }
    }

    // Connection handling

    private fun connect(status: Status) {
        val channel = state.value.channel ?: return
        teardown()
        _state.update { it.copy(status = status) }
        val server = settings.serverURL.value
        reconnectJob = scope.launch {
            try {
                val endpoint = channel.resolveEndpoint(server, http)
                if (state.value.channel != channel) return@launch
                val session = GaleneSession(context, endpoint, channel.name, http)
                session.isMuted = state.value.isMuted
                session.onEvent = { event -> if (session === this@Player.session) handle(event) }
                this@Player.session = session
                session.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handle(GaleneSession.Event.Failed(e))
            }
        }
    }

    private fun handle(event: GaleneSession.Event) {
        when (event) {
            GaleneSession.Event.Joined ->
                if (state.value.status != Status.PLAYING) _state.update { it.copy(status = Status.WAITING) }
            GaleneSession.Event.Receiving -> {
                attempt = 0
                _state.update { it.copy(status = Status.PLAYING, lastError = null) }
            }
            GaleneSession.Event.Waiting -> _state.update { it.copy(status = Status.WAITING) }
            is GaleneSession.Event.Failed -> {
                _state.update { it.copy(lastError = event.error.localizedMessage ?: event.error.toString()) }
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (!state.value.isActive) return
        teardown()
        _state.update { it.copy(status = Status.RECONNECTING) }
        attempt += 1
        val delaySeconds = min(2.0.pow(attempt - 1), 15.0)
        Log.i(TAG, "reconnecting in $delaySeconds s (attempt $attempt)")
        reconnectJob = scope.launch {
            delay((delaySeconds * 1000).toLong())
            connect(Status.RECONNECTING)
        }
    }

    /** Reconnects right away, e.g. because the network came back. */
    private fun reconnectNow() {
        val current = state.value
        if (!current.isActive || (current.status != Status.RECONNECTING && current.status != Status.WAITING)) return
        Log.i(TAG, "reconnecting now")
        connect(Status.RECONNECTING)
    }

    private fun teardown() {
        reconnectJob?.cancel()
        reconnectJob = null
        session?.close()
        session = null
    }

    private fun observeNetwork() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // Called on a binder thread.
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    if (!networkAvailable) {
                        networkAvailable = true
                        reconnectNow()
                    }
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post { networkAvailable = false }
            }
        })
    }

    // Audio focus

    private fun acquireAudio(): Boolean {
        if (!hasAudioFocus) {
            hasAudioFocus = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasAudioFocus) {
                Log.w(TAG, "audio focus denied")
                return false
            }
        }
        if (!noisyReceiverRegistered) {
            ContextCompat.registerReceiver(context, becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyReceiverRegistered = true
        }
        return true
    }

    private fun releaseAudio() {
        if (hasAudioFocus) {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
            hasAudioFocus = false
        }
        if (noisyReceiverRegistered) {
            context.unregisterReceiver(becomingNoisyReceiver)
            noisyReceiverRegistered = false
        }
    }

    private fun onAudioFocusChange(change: Int) {
        Log.i(TAG, "audio focus change $change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (state.value.isActive) {
                interrupted = true
                pause()
            }
            // Live audio can't continue where it left off: reconnect.
            AudioManager.AUDIOFOCUS_GAIN -> if (interrupted) {
                interrupted = false
                if (state.value.channel != null) connect(Status.RECONNECTING)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                interrupted = false
                hasAudioFocus = false
                pause()
            }
            // Ducking is done by the system.
        }
    }

    private companion object {
        const val TAG = "player"
    }
}
