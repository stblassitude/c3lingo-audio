import AVFoundation
import Combine
import LiveKitWebRTC
import os
import Speech

/// Live subtitles for the channel that is playing, transcribed on the device from the
/// audio as it arrives, in the language the page says the channel carries.
///
/// Turning them off, pausing and stopping stop the transcription; what was said in the
/// meantime is missing, and a marker says so when they are turned on again. Needs
/// SpeechTranscriber (iOS, macOS, visionOS 26, on devices that support it).
final class Subtitles: ObservableObject {
    enum State: Equatable {
        /// The explanation, shown before the subtitles are turned on for the first time.
        case intro
        case preparing
        /// The speech model for the language is being downloaded, with the fraction done.
        case downloading(Double)
        case listening
        /// The page doesn't say which language the channel carries.
        case unknownLanguage
        /// The device can't transcribe the channel's language.
        case unsupported
        /// The system or the device can't make subtitles at all.
        case deviceUnsupported
        case failed(String)
    }

    struct Paragraph: Identifiable, Equatable {
        enum Kind: Equatable {
            case text
            /// The subtitles were off, or the connection dropped: text is missing here.
            case gap
            /// The channel now carries another language.
            case language
        }

        let id: Int
        let kind: Kind
        var text: String
    }

    /// What the subtitles depend on, from the player and the page.
    struct Context: Equatable {
        var channel: String?
        var language: ChannelDescription.Language?
        var status: Player.Status
    }

    @Published private(set) var isShowing = false
    @Published private(set) var state: State = .preparing
    /// Finished text and markers, oldest first.
    @Published private(set) var paragraphs: [Paragraph] = []
    /// The text being spoken right now, which may still change.
    @Published private(set) var pendingText = ""
    @Published private(set) var language: ChannelDescription.Language?

    /// Whether this device can make subtitles at all; if not, the subtitles explain why.
    static var isSupported: Bool {
        if #available(iOS 26, macOS 26, visionOS 26, *) {
            SpeechTranscriber.isAvailable
        } else {
            false
        }
    }

    private static let introShownKey = "subtitlesIntroShown"
    /// Paragraphs kept; older ones are dropped.
    private static let maximumParagraphs = 500
    /// Finished text is added to the last paragraph until it is about this long.
    private static let paragraphLength = 400

    private weak var player: Player?
    private var context = Context(channel: nil, language: nil, status: .idle)
    /// The running transcription, a `Transcription` on systems that have one.
    private var transcription: AnyObject?
    private var task: Task<Void, Never>?
    /// Tells results of an earlier transcription from those of the current one.
    private var generation = 0
    private var nextID = 0

    func attach(_ player: Player) {
        self.player = player
    }

    /// Whether the player bar's button can turn the subtitles on: only while something plays.
    var isAvailable: Bool {
        context.channel != nil && context.status != .paused && context.status != .idle
    }

    func toggle() {
        isShowing ? hide() : show()
    }

    func show() {
        guard isAvailable, !isShowing else { return }
        isShowing = true
        if !Self.isSupported {
            state = .deviceUnsupported
        } else if UserDefaults.standard.bool(forKey: Self.introShownKey) {
            start(marking: .gap)
        } else {
            state = .intro
        }
    }

    /// Confirms the explanation and turns the subtitles on.
    func turnOn() {
        UserDefaults.standard.set(true, forKey: Self.introShownKey)
        start(marking: .gap)
    }

    func hide() {
        guard isShowing else { return }
        isShowing = false
        stop()
    }

    /// Follows the player and the page: closes the subtitles when playback pauses or
    /// stops, starts over for another channel, and follows a change of language.
    func update(_ context: Context) {
        let old = self.context
        self.context = context
        if context.channel != old.channel {
            hide()
            paragraphs.removeAll()
            pendingText = ""
            language = context.language
            return
        }
        if !isAvailable {
            hide()
        } else if context.status == .reconnecting, old.status != .reconnecting, state == .listening {
            // Whatever is said until the connection is back is lost.
            commitPendingText()
            addMarker(.gap)
        }
        if context.language?.tag != old.language?.tag {
            language = context.language
            if isShowing, state != .intro, state != .deviceUnsupported {
                start(marking: .language)
            }
        } else if context.language != language {
            // Only the name or flag changed.
            language = context.language
        }
    }

    // MARK: - Transcription

    private func start(marking marker: Paragraph.Kind) {
        stop()
        addMarker(marker)
        guard #available(iOS 26, macOS 26, visionOS 26, *) else {
            state = .unsupported
            return
        }
        guard let tag = language?.tag else {
            state = .unknownLanguage
            return
        }
        generation += 1
        let generation = generation
        state = .preparing
        task = Task { [weak self] in
            do {
                guard let locale = await SpeechTranscriber.supportedLocale(equivalentTo: Locale(identifier: tag)) else {
                    Log.subtitles.info("no speech model for \(tag)")
                    self?.state = .unsupported
                    return
                }
                let transcription = Transcription(locale: locale)
                if let request = try await transcription.installationRequest() {
                    Log.subtitles.info("downloading speech model for \(locale.identifier)")
                    try await self?.download(request)
                }
                guard let self, !Task.isCancelled, generation == self.generation else { return }
                try await transcription.start { [weak self] text, isFinal in
                    guard let self, generation == self.generation else { return }
                    self.received(text, isFinal: isFinal)
                }
                guard !Task.isCancelled, generation == self.generation else {
                    transcription.stop()
                    return
                }
                Log.subtitles.info("transcribing \(locale.identifier)")
                self.transcription = transcription
                player?.audioRenderer = transcription.audioFeed
                state = .listening
            } catch is CancellationError {
            } catch {
                Log.subtitles.error("unable to start subtitles: \(error)")
                guard let self, generation == self.generation else { return }
                state = .failed(error.localizedDescription)
            }
        }
    }

    /// Shows the progress until the model is installed.
    @available(iOS 26, macOS 26, visionOS 26, *)
    private func download(_ request: AssetInstallationRequest) async throws {
        state = .downloading(0)
        let progress = Task { [weak self] in
            while !Task.isCancelled {
                self?.state = .downloading(request.progress.fractionCompleted)
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
        }
        defer { progress.cancel() }
        try await withTaskCancellationHandler {
            try await request.downloadAndInstall()
        } onCancel: {
            request.progress.cancel()
        }
    }

    private func stop() {
        generation += 1
        task?.cancel()
        task = nil
        player?.audioRenderer = nil
        if #available(iOS 26, macOS 26, visionOS 26, *) {
            (transcription as? Transcription)?.stop()
        }
        transcription = nil
        // The best guess of what was said last is better than nothing.
        commitPendingText()
    }

    private func received(_ text: String, isFinal: Bool) {
        if isFinal {
            pendingText = ""
            append(text)
        } else {
            pendingText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    private func commitPendingText() {
        let text = pendingText
        pendingText = ""
        append(text)
    }

    private func append(_ text: String) {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        if let last = paragraphs.indices.last, paragraphs[last].kind == .text, paragraphs[last].text.count < Self.paragraphLength {
            paragraphs[last].text += " " + text
        } else {
            add(Paragraph(id: nextID, kind: .text, text: text))
        }
    }

    /// Marks where text is missing or the language changed, if there is text before it.
    private func addMarker(_ kind: Paragraph.Kind) {
        guard let last = paragraphs.last else { return }
        let text = kind == .language ? language.map { [$0.flag, $0.name].compactMap { $0 }.joined(separator: " ") } ?? "" : ""
        switch (last.kind, kind) {
        case (.gap, .gap):
            // Missing once is missing.
            return
        case (.language, .language):
            // Only the latest language counts.
            paragraphs[paragraphs.count - 1].text = text
        default:
            add(Paragraph(id: nextID, kind: kind, text: text))
        }
    }

    private func add(_ paragraph: Paragraph) {
        nextID += 1
        paragraphs.append(paragraph)
        if paragraphs.count > Self.maximumParagraphs {
            paragraphs.removeFirst(paragraphs.count - Self.maximumParagraphs)
        }
    }
}

/// Transcribes the audio fed to it with SpeechAnalyzer, in one language.
@available(iOS 26, macOS 26, visionOS 26, *)
private final class Transcription {
    enum Failure: LocalizedError {
        case noAudioFormat

        var errorDescription: String? {
            String(localized: "Speech recognition can't process this audio.")
        }
    }

    private let transcriber: SpeechTranscriber
    private var analyzer: SpeechAnalyzer?
    private var results: Task<Void, Never>?
    private(set) var audioFeed: AudioFeed?

    init(locale: Locale) {
        transcriber = SpeechTranscriber(locale: locale, transcriptionOptions: [], reportingOptions: [.volatileResults], attributeOptions: [])
    }

    /// What needs to be downloaded before the language can be transcribed, if anything.
    func installationRequest() async throws -> AssetInstallationRequest? {
        try await AssetInventory.assetInstallationRequest(supporting: [transcriber])
    }

    /// Starts the analyzer; `onResult` gets the text as it is recognized, and again once it is final.
    func start(onResult: @escaping (String, Bool) -> Void) async throws {
        guard let format = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [transcriber]) else {
            throw Failure.noAudioFormat
        }
        // About ten seconds of WebRTC's 10 ms buffers, in case recognition falls behind.
        let (input, continuation) = AsyncStream.makeStream(of: AnalyzerInput.self, bufferingPolicy: .bufferingNewest(1000))
        let analyzer = SpeechAnalyzer(modules: [transcriber])
        let transcriber = transcriber
        results = Task {
            do {
                for try await result in transcriber.results {
                    onResult(String(result.text.characters), result.isFinal)
                }
            } catch {
                Log.subtitles.error("transcription failed: \(error)")
            }
        }
        do {
            try await analyzer.start(inputSequence: input)
        } catch {
            results?.cancel()
            throw error
        }
        self.analyzer = analyzer
        audioFeed = AudioFeed(format: format, continuation: continuation)
    }

    func stop() {
        audioFeed?.finish()
        audioFeed = nil
        results?.cancel()
        results = nil
        if let analyzer {
            Task { await analyzer.cancelAndFinishNow() }
        }
        analyzer = nil
    }
}

/// Gets the decoded audio of the channel from WebRTC, on its audio thread, and passes it
/// on to the analyzer in the format the analyzer wants.
@available(iOS 26, macOS 26, visionOS 26, *)
private nonisolated final class AudioFeed: NSObject, LKRTCAudioRenderer, @unchecked Sendable {
    private let format: AVAudioFormat
    private let continuation: AsyncStream<AnalyzerInput>.Continuation
    private let lock = NSLock()
    private var converter: AVAudioConverter?

    init(format: AVAudioFormat, continuation: AsyncStream<AnalyzerInput>.Continuation) {
        self.format = format
        self.continuation = continuation
    }

    func render(pcmBuffer buffer: AVAudioPCMBuffer) {
        guard buffer.frameLength > 0 else { return }
        lock.lock()
        defer { lock.unlock() }
        if converter?.inputFormat != buffer.format {
            converter = AVAudioConverter(from: buffer.format, to: format)
            converter?.primeMethod = .none
        }
        guard let converter else { return }
        let frames = (Double(buffer.frameLength) * format.sampleRate / buffer.format.sampleRate).rounded(.up)
        // Always a copy: WebRTC reuses its buffer once this returns.
        guard let output = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames) + 16) else { return }
        let input = InputOnce(buffer)
        var error: NSError?
        converter.convert(to: output, error: &error) { _, status in
            input.next(status)
        }
        if let error {
            Log.subtitles.error("unable to convert audio: \(error)")
            return
        }
        if output.frameLength > 0 {
            continuation.yield(AnalyzerInput(buffer: output))
        }
    }

    func finish() {
        continuation.finish()
    }
}

/// Hands one buffer to the converter, then says there is no more for now, so the
/// converter keeps its state for the next one.
private nonisolated final class InputOnce {
    private var buffer: AVAudioPCMBuffer?

    init(_ buffer: AVAudioPCMBuffer) {
        self.buffer = buffer
    }

    func next(_ status: UnsafeMutablePointer<AVAudioConverterInputStatus>) -> AVAudioBuffer? {
        guard let buffer else {
            status.pointee = .noDataNow
            return nil
        }
        self.buffer = nil
        status.pointee = .haveData
        return buffer
    }
}
