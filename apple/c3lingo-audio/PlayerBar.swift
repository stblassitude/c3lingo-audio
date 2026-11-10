import AVKit
import SwiftUI

/// The native playback controls, shown at the bottom while a channel is selected.
///
/// Narrow (portrait phones): everything centred, the hall and language, the current
/// talk, the status and, if needed, a message that explains it, with the controls below.
/// Wide: the same information on the left, the controls on the right, once the
/// information has at least twice as much room as the controls take.
struct PlayerBar: View {
    @EnvironmentObject private var player: Player
    /// What the frontend knows about the channel, if anything.
    let description: ChannelDescription?
    /// Stops playback; true if the Stop button had keyboard focus.
    let stop: (Bool) -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The bar's width, and that of the controls, which grow with the text size.
    @State private var width: CGFloat = 0
    @State private var controlsWidth: CGFloat = 0
    private static let padding: CGFloat = 16
    private static let spacing: CGFloat = 16

    private var isWide: Bool {
        guard controlsWidth > 0 else { return false }
        let informationWidth = width - 2 * Self.padding - Self.spacing - controlsWidth
        return informationWidth >= 2 * controlsWidth
    }

    /// At the accessibility text sizes, text wraps instead of being cut off, to twice as
    /// many lines, which leaves room for the page even with a long talk title.
    private func lines(_ count: Int) -> Int {
        dynamicTypeSize.isAccessibilitySize ? count * 2 : count
    }

    var body: some View {
        Group {
            if isWide {
                HStack(spacing: Self.spacing) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 12) {
                            title
                            status
                        }
                        talk
                        message
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    controls
                }
            } else {
                VStack(spacing: 2) {
                    title
                    talk
                    status
                    message
                    controls
                        .padding(.top, 8)
                }
                .multilineTextAlignment(.center)
            }
        }
        .labelStyle(.iconOnly)
        .buttonStyle(.borderless)
        .padding(.horizontal, Self.padding)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .background(GeometryReader { geometry in
            Color.clear.preference(key: WidthKey.self, value: geometry.size.width)
        })
        .onPreferenceChange(WidthKey.self) { width = $0 }
        .onPreferenceChange(ControlsWidthKey.self) { controlsWidth = $0 }
        #if os(visionOS)
        // On the bar material, which is opaque and light there, secondary text all but disappears.
        .background(.thickMaterial)
        #else
        .background(.bar)
        #endif
        #if os(iOS)
        // Separates the bar from the page, like a tab bar or toolbar.
        .overlay(alignment: .top) { Divider() }
        #endif
        // The page doesn't announce the state while the app plays: it leaves that to the app.
        .onAppear { announce(announcement) }
        .onChange(of: announcement) { announce($0) }
    }

    /// Capped from outside, so that the controls' own scaled size is capped too, where
    /// the five of them still fit across a small phone.
    private var controls: some View {
        PlayerControls(stop: stop)
            .dynamicTypeSize(...DynamicTypeSize.xLarge)
    }

    private var name: String {
        description?.title ?? player.channel?.displayName ?? ""
    }

    private var title: some View {
        Text(name)
            .font(.headline)
            .lineLimit(lines(1))
            .truncationMode(.tail)
            // Wraps rather than giving way to the page above.
            .fixedSize(horizontal: false, vertical: true)
    }

    /// The current or next talk, a link to its page in the browser if the schedule has one.
    @ViewBuilder private var talk: some View {
        if let text = description?.talkText {
            Group {
                if let url = description?.talk?.url {
                    // Taller to tap than the line it takes up.
                    Link(destination: url) {
                        Text(text)
                            .padding(.vertical, 12)
                            .contentShape(Rectangle())
                    }
                    .padding(.vertical, -12)
                    .accessibilityHint("Opens the talk's page in the browser")
                } else {
                    Text(text)
                        .foregroundStyle(.secondary)
                }
            }
            .font(.subheadline)
            .lineLimit(lines(1))
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// The status, and for screen readers the message below it, which explains it.
    private var status: some View {
        HStack(spacing: 6) {
            statusIndicator
            Text(statusText)
                .lineLimit(lines(1))
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.subheadline)
        #if os(visionOS)
        // Over the glass, whatever is behind the window shows through; keep this readable.
        .foregroundStyle(.primary)
        #else
        .foregroundStyle(.secondary)
        #endif
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([statusText, messageText].compactMap { $0 }.joined(separator: ". "))
    }

    /// Explains the status: that nothing is being broadcast or can be heard, or why there's no connection.
    @ViewBuilder private var message: some View {
        if let text = messageText {
            Text(text)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(lines(2))
                .fixedSize(horizontal: false, vertical: true)
                // Read with the status.
                .accessibilityHidden(true)
        }
    }

    private var messageText: String? {
        switch player.status {
        case .waiting: String(localized: "Nothing is being broadcast on this channel right now. It starts playing as soon as it is.")
        case .silent: String(localized: "Connected, but no sound is coming through on this channel right now.")
        case .connecting, .reconnecting: player.lastError
        default: nil
        }
    }

    /// What screen readers announce when the state changes, worded like the frontend's
    /// own announcements: the status with the channel, or what is wrong.
    private var announcement: String? {
        switch player.status {
        case .idle:
            nil
        case .waiting, .silent:
            messageText.map { "\(name): \($0)" }
        case .connecting, .reconnecting:
            player.lastError.map { "\(statusText): \($0)" } ?? "\(statusText) \(name)"
        case .playing, .paused:
            "\(statusText) \(name)"
        }
    }

    private func announce(_ text: String?) {
        guard let text, !text.isEmpty else { return }
        #if os(macOS)
        NSAccessibility.post(element: NSApp.mainWindow as Any, notification: .announcementRequested, userInfo: [
            .announcement: text,
            .priority: NSAccessibilityPriorityLevel.medium.rawValue,
        ])
        #else
        // Queued, so it doesn't cut off what VoiceOver is saying, e.g. a button's new label.
        UIAccessibility.post(notification: .announcement, argument: NSAttributedString(string: text, attributes: [
            .accessibilitySpeechQueueAnnouncement: true,
        ]))
        #endif
    }

    @ViewBuilder private var statusIndicator: some View {
        switch player.status {
        case .playing:
            let image = Image(systemName: "dot.radiowaves.left.and.right")
                .foregroundStyle(.red)
            if #available(iOS 17, macOS 14, *) {
                image.symbolEffect(.variableColor.iterative, isActive: !player.isMuted && !reduceMotion)
            } else {
                image
            }
        case .connecting, .reconnecting:
            ProgressView()
                .controlSize(.mini)
        default:
            EmptyView()
        }
    }

    private var statusText: String {
        switch player.status {
        case .idle: ""
        case .connecting: String(localized: "Connecting…")
        case .waiting: String(localized: "Nothing playing")
        case .playing: player.isMuted ? String(localized: "Live, muted") : String(localized: "Live")
        case .silent: String(localized: "No sound")
        case .paused: String(localized: "Paused")
        case .reconnecting:
            // The message below says what's wrong.
            player.lastError != nil ? String(localized: "Connection problem") : String(localized: "Reconnecting…")
        }
    }
}

/// Mute, play or pause, subtitles, audio output and stop.
///
/// They grow with the text size up to xLarge (capped by `PlayerBar`), where they still
/// fit across a phone; beyond that, holding one shows it enlarged in the Large Content Viewer, as in
/// the system's own bars.
private struct PlayerControls: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var subtitles: Subtitles
    let stop: (Bool) -> Void

    @FocusState private var isStopFocused: Bool
    /// Twice the usual size of a `.title2` symbol, so the controls are easy to hit.
    @ScaledMetric(relativeTo: .title2) private var controlSize: CGFloat = 44

    var body: some View {
        HStack(spacing: 16) {
            Button {
                player.isMuted.toggle()
            } label: {
                Label(player.isMuted ? "Unmute" : "Mute",
                      systemImage: player.isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .replacingSymbolTransition()
            }
            .largeContentViewer()

            Button {
                player.togglePause()
            } label: {
                Label(player.status == .paused ? "Play" : "Pause",
                      systemImage: player.status == .paused ? "play.fill" : "pause.fill")
                    .replacingSymbolTransition()
            }
            .largeContentViewer()

            // Also where the device can't make subtitles: they then explain why.
            Button {
                subtitles.toggle()
            } label: {
                Label(subtitles.isShowing ? "Hide Subtitles" : "Show Subtitles",
                      systemImage: subtitles.isShowing ? "captions.bubble.fill" : "captions.bubble")
                    .replacingSymbolTransition()
            }
            .disabled(!subtitles.isAvailable)
            .largeContentViewer()

            #if os(iOS) || os(macOS)
            RoutePicker()
                .frame(width: controlSize * 1.25, height: controlSize * 1.25)
                .accessibilityLabel("Audio output")
            #endif

            Button {
                stop(isStopFocused)
            } label: {
                Label("Stop", systemImage: "stop.fill")
            }
            .focused($isStopFocused)
            .largeContentViewer()
        }
        .font(.system(size: controlSize))
        // The same in both layouts, so measuring it doesn't change the layout it is measured in.
        .fixedSize()
        .background(GeometryReader { geometry in
            Color.clear.preference(key: ControlsWidthKey.self, value: geometry.size.width)
        })
    }
}

private nonisolated struct WidthKey: PreferenceKey {
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private nonisolated struct ControlsWidthKey: PreferenceKey {
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private extension View {
    @ViewBuilder func largeContentViewer() -> some View {
        #if os(iOS)
        accessibilityShowsLargeContentViewer()
        #else
        self
        #endif
    }

    @ViewBuilder func replacingSymbolTransition() -> some View {
        if #available(iOS 17, macOS 14, *) {
            contentTransition(.symbolEffect(.replace))
        } else {
            self
        }
    }
}

#if os(iOS)
/// AirPlay and Bluetooth output selection.
private struct RoutePicker: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.prioritizesVideoDevices = false
        return view
    }

    func updateUIView(_ view: AVRoutePickerView, context: Context) {}
}
#elseif os(macOS)
/// AirPlay output selection.
private struct RoutePicker: NSViewRepresentable {
    func makeNSView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.isRoutePickerButtonBordered = false
        return view
    }

    func updateNSView(_ view: AVRoutePickerView, context: Context) {}
}
#endif
