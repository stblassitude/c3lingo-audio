import AVFoundation
import LiveKitWebRTC
import os

/// The shared WebRTC peer connection factory.
///
/// It uses the AVAudioEngine based audio device module with Apple's voice processing
/// disabled: the app only ever plays audio, so the microphone is never touched, no
/// permission prompt appears, and the audio session can use the `.playback` category,
/// which keeps audio running in the background and on the lock screen.
nonisolated enum WebRTC {
    static let factory: LKRTCPeerConnectionFactory = {
        LKRTCInitializeSSL()
        let factory = LKRTCPeerConnectionFactory(
            audioDeviceModuleType: .audioEngine,
            bypassVoiceProcessing: true,
            encoderFactory: LKRTCDefaultVideoEncoderFactory(),
            decoderFactory: LKRTCDefaultVideoDecoderFactory(),
            audioProcessingModule: nil)
        let adm = factory.audioDeviceModule
        adm.setPlatformVoiceProcessingAllowed(false)
        adm.observer = audioEngineObserver
        return factory
    }()

    // The module only keeps a weak reference to its observer.
    private static let audioEngineObserver = AudioEngineObserver()
}

/// Configures the audio session around the WebRTC audio engine lifecycle.
///
/// Called by WebRTC on its own threads, hence nonisolated.
nonisolated final class AudioEngineObserver: NSObject, LKRTCAudioDeviceModuleDelegate, @unchecked Sendable {
    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule, didReceiveSpeechActivityEvent speechActivityEvent: LKRTCSpeechActivityEvent) {}

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule, didCreateEngine engine: AVAudioEngine) -> Int {
        0
    }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule, willEnableEngine engine: AVAudioEngine, isPlayoutEnabled: Bool, isRecordingEnabled: Bool, isVoiceProcessingEnabled: Bool) -> Int {
        Log.audio.info("enabling audio engine: playout \(isPlayoutEnabled), recording \(isRecordingEnabled), voice processing \(isVoiceProcessingEnabled)")
        #if !os(macOS)
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
        } catch {
            Log.audio.error("unable to activate audio session: \(error)")
            return -1
        }
        #endif
        return 0
    }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule, willStartEngine engine: AVAudioEngine, isPlayoutEnabled: Bool, isRecordingEnabled: Bool) -> Int {
        0
    }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule, didStopEngine engine: AVAudioEngine, isPlayoutEnabled: Bool, isRecordingEnabled: Bool) -> Int {
        0
    }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule, didDisableEngine engine: AVAudioEngine, isPlayoutEnabled: Bool, isRecordingEnabled: Bool) -> Int {
        Log.audio.info("disabled audio engine")
        #if !os(macOS)
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            Log.audio.error("unable to deactivate audio session: \(error)")
        }
        #endif
        return 0
    }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule, willReleaseEngine engine: AVAudioEngine) -> Int {
        0
    }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule, engine: AVAudioEngine, configureInputFromSource source: AVAudioNode?, toDestination destination: AVAudioNode, format: AVAudioFormat, context: [AnyHashable: Any]) -> Int {
        guard let source else { return 0 }
        return connect(source, to: destination, format: format, in: engine)
    }

    func audioDeviceModule(_ audioDeviceModule: LKRTCAudioDeviceModule, engine: AVAudioEngine, configureOutputFromSource source: AVAudioNode, toDestination destination: AVAudioNode?, format: AVAudioFormat, context: [AnyHashable: Any]) -> Int {
        guard let destination else { return 0 }
        return connect(source, to: destination, format: format, in: engine)
    }

    private func connect(_ source: AVAudioNode, to destination: AVAudioNode, format: AVAudioFormat, in engine: AVAudioEngine) -> Int {
        guard #available(iOS 27, macOS 27, visionOS 27, *) else {
            engine.connect(source, to: destination, format: format)
            return 0
        }
        do {
            try engine.connectNode(source, to: destination, format: format)
            return 0
        } catch {
            Log.audio.error("unable to connect audio nodes: \(error)")
            return -1
        }
    }

    func audioDeviceModuleDidUpdateDevices(_ audioDeviceModule: LKRTCAudioDeviceModule) {}
}
