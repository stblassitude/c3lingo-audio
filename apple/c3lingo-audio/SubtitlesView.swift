import SwiftUI

/// The subtitles, over the whole page, so that as much text as possible fits; the player
/// bar stays below. Before the subtitles are turned on for the first time, it explains
/// them instead, and while a speech model downloads, it shows the progress. Where the
/// system or the device can't make subtitles, it says so.
struct SubtitlesView: View {
    @ObservedObject var subtitles: Subtitles

    var body: some View {
        Group {
            switch subtitles.state {
            case .intro:
                SubtitlesIntro(subtitles: subtitles)
            case .listening:
                if #available(iOS 26, macOS 26, visionOS 26, *) {
                    Transcript(subtitles: subtitles)
                }
            case .preparing:
                SubtitlesStatus(systemImage: "captions.bubble", title: "Preparing subtitles…", progress: nil)
            case .downloading(let fraction):
                SubtitlesStatus(
                    systemImage: "arrow.down.circle",
                    title: "Downloading speech recognition for \(languageName)",
                    message: Text("The subtitles start as soon as the download is complete."),
                    progress: fraction)
            case .unknownLanguage:
                SubtitlesStatus(
                    systemImage: "questionmark.bubble",
                    title: "No subtitles for this channel",
                    message: Text("The channel list doesn't say which language this channel is in."))
            case .unsupported:
                SubtitlesStatus(
                    systemImage: "captions.bubble",
                    title: "No subtitles for \(languageName)",
                    message: Text("This device can't transcribe \(languageName)."))
            case .deviceUnsupported:
                SubtitlesStatus(
                    systemImage: "captions.bubble",
                    title: "No subtitles on this device",
                    message: Text(Self.deviceUnsupportedReason))
            case .failed(let reason):
                SubtitlesStatus(
                    systemImage: "exclamationmark.bubble",
                    title: "Unable to start the subtitles",
                    message: Text(verbatim: reason))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.background)
    }

    private var languageName: String {
        subtitles.language?.name ?? ""
    }

    /// Too old a system, or a device without on-device speech recognition.
    private static var deviceUnsupportedReason: String {
        guard #available(iOS 26, macOS 26, visionOS 26, *) else {
            #if os(macOS)
            let system = "macOS"
            #else
            let system = UIDevice.current.userInterfaceIdiom == .pad ? "iPadOS" : "iOS"
            #endif
            return String(localized: "Subtitles need \(system) 26 or later. Please update your device's software to use them.")
        }
        return String(localized: "This device doesn't support the on-device speech recognition that subtitles need.")
    }
}

/// What the subtitles are, shown once before they are turned on for the first time.
private struct SubtitlesIntro: View {
    @ObservedObject var subtitles: Subtitles

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: "captions.bubble")
                    .font(.system(size: 44))
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                Text("Subtitles")
                    .font(.title2.bold())
                    .accessibilityAddTraits(.isHeader)
                Text("The subtitles feature uses your phone's on-device capabilities to create subtitles for the audio received. Generating subtitles may increase battery usage. We do not control the quality of the transcription, but we are interested in your experience. Please write to us at [hello@c3lingo.org](mailto:hello@c3lingo.org).")
                Button("Turn On Subtitles") {
                    subtitles.turnOn()
                }
                .buttonStyle(.borderedProminent)
                .padding(.top, 8)
                Button("Not Now") {
                    subtitles.hide()
                }
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: 560)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .modifier(CenteredScrolling())
    }
}

/// Centres short content, and doesn't bounce it, where the system can.
private struct CenteredScrolling: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26, macOS 26, visionOS 26, *) {
            content
                .scrollBounceBehavior(.basedOnSize)
                .defaultScrollAnchor(.center)
        } else {
            content
        }
    }
}

/// Why there are no subtitles yet, or none at all.
private struct SubtitlesStatus: View {
    let systemImage: String
    let title: LocalizedStringKey
    var message: Text?
    /// A fraction for a progress bar, or nil for a spinner; no progress for a final state.
    var progress: Double??

    init(systemImage: String, title: LocalizedStringKey, message: Text? = nil) {
        self.systemImage = systemImage
        self.title = title
        self.message = message
        progress = .none
    }

    init(systemImage: String, title: LocalizedStringKey, message: Text? = nil, progress: Double?) {
        self.systemImage = systemImage
        self.title = title
        self.message = message
        self.progress = .some(progress)
    }

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(title)
                .font(.title3.bold())
            if let message {
                message
                    .foregroundStyle(.secondary)
            }
            switch progress {
            case .some(.some(let fraction)):
                ProgressView(value: fraction) {
                    EmptyView()
                } currentValueLabel: {
                    Text(fraction, format: .percent.precision(.fractionLength(0)))
                }
                .frame(maxWidth: 320)
                .padding(.top, 8)
            case .some(.none):
                ProgressView()
                    .padding(.top, 8)
            case .none:
                EmptyView()
            }
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: 560)
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}

/// The text as it comes in, kept at the latest unless scrolled back.
@available(iOS 26, macOS 26, visionOS 26, *)
private struct Transcript: View {
    @ObservedObject var subtitles: Subtitles

    @State private var position = ScrollPosition(edge: .bottom)
    /// Whether the end of the text is in view.
    @State private var isAtEnd = true
    /// Follows the text until scrolled back, and again once scrolled to the end. Only
    /// scrolling changes it, not text that comes in and pushes the end out of view.
    @State private var isFollowing = true

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                header
                ForEach(subtitles.paragraphs) { paragraph in
                    switch paragraph.kind {
                    case .text:
                        text(paragraph)
                    case .gap:
                        Marker(systemImage: "ellipsis", text: "Subtitles were interrupted. Some text may be missing here.")
                    case .language:
                        Marker(systemImage: "globe", text: "Now in \(paragraph.text)")
                    }
                }
                if !subtitles.pendingText.isEmpty, subtitles.paragraphs.last?.kind != .text {
                    Text(subtitles.pendingText)
                        .foregroundStyle(.secondary)
                }
                if subtitles.paragraphs.isEmpty, subtitles.pendingText.isEmpty {
                    Text("Subtitles appear here as soon as someone speaks.")
                        .foregroundStyle(.secondary)
                }
            }
            .font(.title3)
            .textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .scrollPosition($position)
        .onScrollGeometryChange(for: Bool.self) { geometry in
            geometry.contentOffset.y + geometry.containerSize.height >= geometry.contentSize.height - 32
        } action: { _, atEnd in
            isAtEnd = atEnd
        }
        .onScrollPhaseChange { _, phase in
            switch phase {
            case .interacting: isFollowing = false
            case .idle: isFollowing = isAtEnd
            default: break
            }
        }
        .onChange(of: subtitles.paragraphs) { follow() }
        .onChange(of: subtitles.pendingText) { follow() }
        .overlay(alignment: .bottomTrailing) {
            if !isFollowing {
                Button {
                    isFollowing = true
                    withAnimation { position.scrollTo(edge: .bottom) }
                } label: {
                    Label("Latest", systemImage: "arrow.down")
                }
                .buttonStyle(.borderedProminent)
                .padding()
                .transition(.opacity)
            }
        }
        .animation(.default, value: isFollowing)
    }

    private var header: some View {
        Label {
            if let language = subtitles.language {
                Text("Automatic subtitles in \([language.flag, language.name].compactMap { $0 }.joined(separator: " ")). They may contain mistakes.")
            } else {
                Text("Automatic subtitles. They may contain mistakes.")
            }
        } icon: {
            Image(systemName: "captions.bubble")
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
    }

    /// A paragraph, and if it is the last, what is being said right now.
    private func text(_ paragraph: Subtitles.Paragraph) -> some View {
        var text = AttributedString(paragraph.text)
        if paragraph.id == subtitles.paragraphs.last?.id, !subtitles.pendingText.isEmpty {
            var pending = AttributedString(" " + subtitles.pendingText)
            pending.foregroundColor = .secondary
            text += pending
        }
        if let tag = subtitles.language?.tag {
            text.languageIdentifier = tag
        }
        return Text(text)
    }

    private func follow() {
        if isFollowing {
            position.scrollTo(edge: .bottom)
        }
    }
}

/// Marks where text is missing or the language changed.
@available(iOS 26, macOS 26, visionOS 26, *)
private struct Marker: View {
    let systemImage: String
    let text: LocalizedStringKey

    var body: some View {
        HStack(spacing: 8) {
            line
            Label(text, systemImage: systemImage)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .layoutPriority(1)
            line
        }
        .padding(.vertical, 4)
    }

    private var line: some View {
        Rectangle()
            .fill(.separator)
            .frame(height: 1)
            .frame(minWidth: 16)
            .accessibilityHidden(true)
    }
}
