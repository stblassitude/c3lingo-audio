package org.c3lingo.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The shared WebRTC peer connection factory.
 *
 * Audio is played as media rather than as a call: it goes to the loudspeaker or headphones
 * at media volume, and hardware echo cancelling and noise suppression stay off. The app
 * only ever receives audio, so the microphone is never opened and needs no permission.
 */
object WebRtc {
    /** How the received audio is played, also used when requesting audio focus. */
    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile private var factory: PeerConnectionFactory? = null

    fun factory(context: Context): PeerConnectionFactory =
        factory ?: synchronized(this) {
            factory ?: create(context.applicationContext).also { factory = it }
        }

    private fun create(context: Context): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        // Without this, WebRTC may pick a low rate such as 8 kHz, which sounds like a phone call.
        val outputSampleRate = context.getSystemService(AudioManager::class.java)
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setOutputSampleRate(outputSampleRate)
            .setAudioAttributes(audioAttributes)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setUseStereoOutput(true)
            .createAudioDeviceModule()
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }
}
