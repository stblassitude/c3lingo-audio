package org.c3lingo.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.floor
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.webrtc.AudioTrackSink

/**
 * Live subtitles for the channel that is playing, transcribed on the device from the
 * audio as it arrives, in the language the page says the channel carries.
 *
 * Uses the system's on-device speech recognizer, fed with the received audio rather than
 * the microphone, which needs Android 13. Turning them off, pausing and stopping stop the
 * recognition; what was said in the meantime is missing, and a marker says so when they
 * are turned on again. Everything runs on the main thread.
 */
class Subtitles(private val context: Context, private val player: Player) {
    sealed interface State {
        /** The explanation, shown before the subtitles are turned on for the first time. */
        data object Intro : State
        data object Preparing : State
        /** The speech model for the language is being downloaded, with the percentage done if known. */
        data class Downloading(val percent: Int?) : State
        data object Listening : State
        /** The page doesn't say which language the channel carries. */
        data object UnknownLanguage : State
        /** The device can't transcribe the channel's language. */
        data object Unsupported : State
        /** The system or the device can't make subtitles at all. */
        data object DeviceUnsupported : State
        data class Failed(val reason: String) : State
    }

    data class Paragraph(val id: Int, val kind: Kind, val text: String) {
        enum class Kind {
            TEXT,
            /** The subtitles were off, or the connection dropped: text is missing here. */
            GAP,
            /** The channel now carries another language. */
            LANGUAGE,
        }
    }

    /** What the subtitles depend on, from the player and the page. */
    private data class Situation(
        val channel: String? = null,
        val language: ChannelDescription.Language? = null,
        val status: Player.Status = Player.Status.IDLE,
    ) {
        /** Whether the subtitles can be turned on: only while something plays. */
        val isAvailable: Boolean
            get() = channel != null && status != Player.Status.PAUSED && status != Player.Status.IDLE
    }

    private val _isShowing = MutableStateFlow(false)
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()
    private val _state = MutableStateFlow<State>(State.Preparing)
    val state: StateFlow<State> = _state.asStateFlow()
    /** Finished text and markers, oldest first. */
    private val _paragraphs = MutableStateFlow<List<Paragraph>>(emptyList())
    val paragraphs: StateFlow<List<Paragraph>> = _paragraphs.asStateFlow()
    /** The text being spoken right now, which may still change. */
    private val _pendingText = MutableStateFlow("")
    val pendingText: StateFlow<String> = _pendingText.asStateFlow()
    private val _language = MutableStateFlow<ChannelDescription.Language?>(null)
    val language: StateFlow<ChannelDescription.Language?> = _language.asStateFlow()
    private val _isAvailable = MutableStateFlow(false)
    /** Whether the player bar's button can turn the subtitles on. */
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    /** Whether this device can make subtitles at all; if not, the subtitles explain why. */
    val isSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    private val preferences = context.getSharedPreferences("subtitles", Context.MODE_PRIVATE)
    private var introShown: Boolean
        get() = preferences.getBoolean("introShown", false)
        set(value) = preferences.edit { putBoolean("introShown", value) }

    private val scope = MainScope()
    private var situation = Situation()
    private var recognition: Recognition? = null
    private var job: Job? = null
    /** Tells results of an earlier recognition from those of the current one. */
    private var generation = 0
    private var nextId = 0

    init {
        scope.launch {
            combine(player.state, player.descriptions) { state, descriptions ->
                Situation(state.channel?.name, state.channel?.let { descriptions[it.name]?.language }, state.status)
            }.distinctUntilChanged().collect(::update)
        }
    }

    fun toggle() {
        if (_isShowing.value) hide() else show()
    }

    fun show() {
        if (!situation.isAvailable || _isShowing.value) return
        _isShowing.value = true
        _state.value = when {
            !isSupported -> State.DeviceUnsupported
            introShown -> return start(Paragraph.Kind.GAP)
            else -> State.Intro
        }
    }

    /** Confirms the explanation and turns the subtitles on. */
    fun turnOn() {
        introShown = true
        start(Paragraph.Kind.GAP)
    }

    fun hide() {
        if (!_isShowing.value) return
        _isShowing.value = false
        stop()
    }

    /**
     * Follows the player and the page: closes the subtitles when playback pauses or
     * stops, starts over for another channel, and follows a change of language.
     */
    private fun update(new: Situation) {
        val old = situation
        situation = new
        _isAvailable.value = new.isAvailable
        if (new.channel != old.channel) {
            hide()
            _paragraphs.value = emptyList()
            _pendingText.value = ""
            _language.value = new.language
            return
        }
        if (!new.isAvailable) {
            hide()
        } else if (new.status == Player.Status.RECONNECTING && old.status != Player.Status.RECONNECTING && _state.value == State.Listening) {
            // Whatever is said until the connection is back is lost.
            commitPendingText()
            addMarker(Paragraph.Kind.GAP)
        }
        if (new.language?.tag != old.language?.tag) {
            _language.value = new.language
            if (_isShowing.value && _state.value != State.Intro && _state.value != State.DeviceUnsupported) start(Paragraph.Kind.LANGUAGE)
        } else if (new.language != _language.value) {
            // Only the name or flag changed.
            _language.value = new.language
        }
    }

    // Recognition

    private fun start(marker: Paragraph.Kind) {
        stop()
        addMarker(marker)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            _state.value = State.Unsupported
            return
        }
        val tag = _language.value?.tag ?: run {
            _state.value = State.UnknownLanguage
            return
        }
        generation += 1
        val generation = generation
        _state.value = State.Preparing
        job = scope.launch {
            val checker = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            try {
                // Until the model is there: wait for a download in progress, or start one.
                var triggered = false
                while (true) {
                    val support = checker.support(tag)
                    if (generation != this@Subtitles.generation) return@launch
                    if (support == null) {
                        _state.value = State.Unsupported
                        return@launch
                    }
                    val installed = match(tag, support.installedOnDeviceLanguages)
                    if (installed != null) {
                        listen(installed, generation)
                        return@launch
                    }
                    val pending = match(tag, support.pendingOnDeviceLanguages)
                    val supported = match(tag, support.supportedOnDeviceLanguages)
                    when {
                        pending != null -> if (_state.value !is State.Downloading) _state.value = State.Downloading(null)
                        supported != null && !triggered -> {
                            Log.i(TAG, "downloading speech model for $supported")
                            triggered = true
                            _state.value = State.Downloading(null)
                            checker.download(supported, onProgress = { percent ->
                                if (generation == this@Subtitles.generation) _state.value = State.Downloading(percent)
                            }, onError = {
                                // E.g. the download was cancelled.
                                if (generation == this@Subtitles.generation) {
                                    stop()
                                    _state.value = State.Failed(context.getString(R.string.subtitles_download_failed))
                                }
                            })
                        }
                        supported == null -> {
                            Log.i(TAG, "no speech model for $tag")
                            _state.value = State.Unsupported
                            return@launch
                        }
                    }
                    delay(DOWNLOAD_POLL_MS)
                }
            } finally {
                checker.destroy()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun listen(language: String, generation: Int) {
        Log.i(TAG, "transcribing $language")
        val recognition = Recognition(context, language, onResult = { text, isFinal ->
            if (generation == this.generation) received(text, isFinal)
        }, onFailure = { reason ->
            if (generation == this.generation) {
                stop()
                _state.value = reason
            }
        })
        this.recognition = recognition
        player.audioSink = recognition.feed
        recognition.start()
        _state.value = State.Listening
    }

    private fun stop() {
        generation += 1
        job?.cancel()
        job = null
        player.audioSink = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) recognition?.stop()
        recognition = null
        // The best guess of what was said last is better than nothing.
        commitPendingText()
    }

    private fun received(text: String, isFinal: Boolean) {
        if (isFinal) {
            _pendingText.value = ""
            append(text)
        } else {
            _pendingText.value = text.trim()
        }
    }

    private fun commitPendingText() {
        val text = _pendingText.value
        _pendingText.value = ""
        append(text)
    }

    private fun append(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val paragraphs = _paragraphs.value
        val last = paragraphs.lastOrNull()
        if (last != null && last.kind == Paragraph.Kind.TEXT && last.text.length < PARAGRAPH_LENGTH) {
            _paragraphs.value = paragraphs.dropLast(1) + last.copy(text = "${last.text} $trimmed")
        } else {
            add(Paragraph(nextId, Paragraph.Kind.TEXT, trimmed))
        }
    }

    /** Marks where text is missing or the language changed, if there is text before it. */
    private fun addMarker(kind: Paragraph.Kind) {
        val paragraphs = _paragraphs.value
        val last = paragraphs.lastOrNull() ?: return
        val text = if (kind == Paragraph.Kind.LANGUAGE) {
            _language.value?.let { listOfNotNull(it.flag, it.name).joinToString(" ") }.orEmpty()
        } else {
            ""
        }
        when {
            // Missing once is missing.
            last.kind == Paragraph.Kind.GAP && kind == Paragraph.Kind.GAP -> Unit
            // Only the latest language counts.
            last.kind == Paragraph.Kind.LANGUAGE && kind == Paragraph.Kind.LANGUAGE ->
                _paragraphs.value = paragraphs.dropLast(1) + last.copy(text = text)
            else -> add(Paragraph(nextId, kind, text))
        }
    }

    private fun add(paragraph: Paragraph) {
        nextId += 1
        _paragraphs.value = (_paragraphs.value + paragraph).takeLast(MAXIMUM_PARAGRAPHS)
    }

    /** What the recognizer can do for [tag], or null if it can't say. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun SpeechRecognizer.support(tag: String): RecognitionSupport? =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            checkRecognitionSupport(recognizerIntent(tag), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(support))
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "unable to check support for $tag: $error")
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }
            })
        }

    /** Asks the system to download the model for [language], with progress where it reports it. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun SpeechRecognizer.download(language: String, onProgress: (Int) -> Unit, onError: () -> Unit) {
        val intent = recognizerIntent(language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            triggerModelDownload(intent, context.mainExecutor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = onProgress(completedPercent)
                override fun onSuccess() = Unit
                override fun onScheduled() {
                    Log.i(TAG, "speech model download for $language scheduled")
                }
                override fun onError(error: Int) {
                    Log.w(TAG, "speech model download for $language failed: $error")
                    onError()
                }
            })
        } else {
            triggerModelDownload(intent)
        }
    }

    companion object {
        private const val TAG = "subtitles"
        /** Paragraphs kept; older ones are dropped. */
        private const val MAXIMUM_PARAGRAPHS = 500
        /** Finished text is added to the last paragraph until it is about this long. */
        private const val PARAGRAPH_LENGTH = 400
        /** How often to check whether a model download has finished. */
        private const val DOWNLOAD_POLL_MS = 2_000L

        /**
         * The recognizer's language for [tag]: the same tag, else the same language in
         * the same region, or in the device's region, or in any.
         */
        fun match(tag: String, languages: List<String>): String? {
            languages.firstOrNull { it.equals(tag, ignoreCase = true) }?.let { return it }
            val wanted = Locale.forLanguageTag(tag)
            val sameLanguage = languages.filter { Locale.forLanguageTag(it).language == wanted.language }
            return sameLanguage.firstOrNull { wanted.country.isNotEmpty() && Locale.forLanguageTag(it).country == wanted.country }
                ?: sameLanguage.firstOrNull { Locale.forLanguageTag(it).country == Locale.getDefault().country }
                ?: sameLanguage.firstOrNull()
        }

        fun recognizerIntent(language: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }
}

/**
 * One language's recognition with the on-device recognizer, fed by [feed] instead of the
 * microphone. A recognizer session ends now and then, e.g. after a long silence; the
 * next one starts right away.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class Recognition(
    private val context: Context,
    private val language: String,
    private val onResult: (String, Boolean) -> Unit,
    private val onFailure: (Subtitles.State) -> Unit,
) : RecognitionListener {
    val feed = AudioFeed()
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    /** The read end of the current session's pipe, closed once the session is over. */
    private var input: ParcelFileDescriptor? = null
    private var stopped = false
    /** Errors in a row without any text in between. */
    private var errors = 0

    fun start() {
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { it.setRecognitionListener(this) }
        listen()
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        feed.close()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        closeInput()
    }

    private fun listen() {
        val recognizer = recognizer ?: return
        if (stopped) return
        closeInput()
        val (read, write) = ParcelFileDescriptor.createPipe()
        input = read
        feed.setOutput(write)
        recognizer.startListening(Subtitles.recognizerIntent(language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioFeed.SAMPLE_RATE)
            // One session for as long as the audio lasts, with results for each part.
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            .putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY))
    }

    private fun closeInput() {
        try {
            input?.close()
        } catch (e: IOException) {
            // Closed already.
        }
        input = null
    }

    /** Starts the next session once this one is over. */
    private fun restart(delayMs: Long = 0) {
        if (stopped) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(::listen, delayMs)
    }

    private fun text(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    override fun onPartialResults(partialResults: Bundle?) {
        text(partialResults)?.takeIf { it.isNotBlank() }?.let {
            errors = 0
            onResult(it, false)
        }
    }

    override fun onSegmentResults(segmentResults: Bundle) {
        text(segmentResults)?.let {
            errors = 0
            onResult(it, true)
        }
    }

    override fun onResults(results: Bundle?) {
        text(results)?.let { onResult(it, true) }
        restart()
    }

    override fun onEndOfSegmentedSession() = restart()

    override fun onError(error: Int) {
        if (stopped) return
        Log.i(TAG, "recognizer error $error")
        when (error) {
            // Nobody spoke, or nothing could be understood: carry on.
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart()
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                onFailure(Subtitles.State.Unsupported)
            else -> {
                errors += 1
                if (errors > MAXIMUM_ERRORS) {
                    onFailure(Subtitles.State.Failed(context.getString(R.string.subtitles_recognizer_error, error)))
                } else {
                    if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                        // The recognizer service went away; a new connection to it is needed.
                        recognizer?.destroy()
                        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { it.setRecognitionListener(this) }
                    }
                    restart(RETRY_DELAY_MS * errors)
                }
            }
        }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private companion object {
        const val TAG = "subtitles"
        const val MAXIMUM_ERRORS = 5
        const val RETRY_DELAY_MS = 1_000L
    }
}

/**
 * Gets the decoded audio of the channel from WebRTC, on its audio thread, turns it into
 * 16 kHz mono and writes it to the recognizer's pipe from a thread of its own, so that a
 * slow reader never holds up playback. What doesn't fit in the queue is dropped.
 */
private class AudioFeed : AudioTrackSink {
    private val queue = ArrayBlockingQueue<ShortArray>(QUEUE_CAPACITY)
    @Volatile private var output: ParcelFileDescriptor? = null
    @Volatile private var closed = false
    private val writer = Thread(::write, "subtitles-audio").apply { start() }

    // Low-pass filter and resampler state, only used on WebRTC's audio thread.
    private var filtered = 0f
    private var phase = 0.0
    private var previous = 0f

    /** Writes to [pipe] from now on, closing the pipe before it. */
    fun setOutput(pipe: ParcelFileDescriptor) {
        val old = output
        output = pipe
        old?.closeQuietly()
    }

    fun close() {
        closed = true
        writer.interrupt()
        output?.closeQuietly()
        output = null
    }

    override fun onData(audioData: ByteBuffer, bitsPerSample: Int, sampleRate: Int, numberOfChannels: Int, numberOfFrames: Int, absoluteCaptureTimestampMs: Long) {
        if (closed || bitsPerSample != 16 || numberOfChannels < 1 || numberOfFrames < 1 || sampleRate < SAMPLE_RATE) return
        val samples = audioData.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        if (samples.remaining() < numberOfFrames * numberOfChannels) return
        // Mixed down to mono, and filtered against aliasing.
        val alpha = 1f - exp(-2.0 * Math.PI * CUTOFF / sampleRate).toFloat()
        val mono = FloatArray(numberOfFrames) { frame ->
            var sum = 0f
            for (channel in 0 until numberOfChannels) sum += samples.get(frame * numberOfChannels + channel)
            filtered += alpha * (sum / numberOfChannels - filtered)
            filtered
        }
        queue.offer(resample(mono, sampleRate))
    }

    /** Linear interpolation down to [SAMPLE_RATE], continuing where the last buffer ended. */
    private fun resample(input: FloatArray, rate: Int): ShortArray {
        val step = rate.toDouble() / SAMPLE_RATE
        val result = ShortArray(((input.size - phase) / step).toInt() + 1)
        var count = 0
        // Position -1 is the last sample of the previous buffer.
        var position = phase
        while (position <= input.size - 1 && count < result.size) {
            val index = floor(position).toInt()
            val fraction = (position - index).toFloat()
            val a = if (index < 0) previous else input[index]
            val b = if (index + 1 < input.size) input[index + 1] else a
            result[count++] = (a + (b - a) * fraction).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            position += step
        }
        phase = position - input.size
        previous = input.last()
        return if (count == result.size) result else result.copyOf(count)
    }

    private fun write() {
        val bytes = ByteBuffer.allocate(QUEUE_BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        var stream: FileOutputStream? = null
        var streamPipe: ParcelFileDescriptor? = null
        while (!closed) {
            val samples = try {
                queue.poll(1, TimeUnit.SECONDS) ?: continue
            } catch (e: InterruptedException) {
                break
            }
            val pipe = output ?: continue
            if (pipe !== streamPipe) {
                streamPipe = pipe
                stream = FileOutputStream(pipe.fileDescriptor)
            }
            bytes.clear()
            if (samples.size * 2 > bytes.capacity()) continue
            samples.forEach(bytes::putShort)
            try {
                stream?.write(bytes.array(), 0, bytes.position())
            } catch (e: IOException) {
                // The session is over; the next pipe takes over.
                if (output === pipe) output = null
            }
        }
    }

    private fun ParcelFileDescriptor.closeQuietly() {
        try {
            close()
        } catch (e: IOException) {
            // Closed already.
        }
    }

    companion object {
        /** The rate speech recognizers work at. */
        const val SAMPLE_RATE = 16_000
        /** Below half of [SAMPLE_RATE], where speech has most of its energy anyway. */
        private const val CUTOFF = 6_000.0
        /** About ten seconds of WebRTC's 10 ms buffers. */
        private const val QUEUE_CAPACITY = 1_000
        private const val QUEUE_BUFFER_BYTES = 8_192
    }
}
