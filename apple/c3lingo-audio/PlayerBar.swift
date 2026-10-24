import AVKit
import SwiftUI

/// The native playback controls, shown at the bottom while a channel is selected.
///
/// Narrow (portrait phones): the hall and language on one line, status and controls
/// below. Wide: hall, language, status and the current talk on the left, the controls
/// on the right. Below that, if needed, a message that explains the status.
struct PlayerBar: View {
    @EnvironmentObject private var player: Player
    /// What the frontend knows about the channel, if anything.
    let description: ChannelDescription?

    @State private var isWide = false
    private static let wideWidth: CGFloat = 520

    var body: some View {
        Group {
            if isWide {
                HStack(spacing: 16) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 12) {
                            title
                            status
                        }
                        .accessibilityElement(children: .combine)
                        talk
                        message
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    controls
                }
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    title
                    HStack(spacing: 16) {
                        status
                            .frame(maxWidth: .infinity, alignment: .leading)
                        controls
                    }
                    message
                }
            }
        }
        .labelStyle(.iconOnly)
        .buttonStyle(.borderless)
        .padding(.horizontal)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .background(GeometryReader { geometry in
            Color.clear.preference(key: WidthKey.self, value: geometry.size.width)
        })
        .onPreferenceChange(WidthKey.self) { width in
            isWide = width >= Self.wideWidth
        }
        .background(.bar)
    }

    private var title: some View {
        Text(description?.title ?? player.channel?.displayName ?? "")
            .font(.headline)
            .lineLimit(1)
            .truncationMode(.tail)
    }

    /// The current or next talk, a link to its page in the browser if the schedule has one.
    @ViewBuilder private var talk: some View {
        if let text = description?.talkText {
            Group {
                if let url = description?.talk?.url {
                    Link(text, destination: url)
                        .accessibilityHint("Opens the talk's page in the browser")
                } else {
                    Text(text)
                        .foregroundStyle(.secondary)
                }
            }
            .font(.subheadline)
            .lineLimit(1)
        }
    }

    private var status: some View {
        HStack(spacing: 6) {
            statusIndicator
            Text(statusText)
                .lineLimit(1)
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
    }

    /// Explains the status: that nothing is being broadcast, or why there's no connection.
    @ViewBuilder private var message: some View {
        if let text = messageText {
            Text(text)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var messageText: String? {
        switch player.status {
        case .waiting: String(localized: "Nothing is being broadcast on this channel right now. It starts playing as soon as it is.")
        case .connecting, .reconnecting: player.lastError
        default: nil
        }
    }

    private var controls: some View {
        HStack(spacing: 16) {
            Button {
                player.isMuted.toggle()
            } label: {
                Label(player.isMuted ? "Unmute" : "Mute",
                      systemImage: player.isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .replacingSymbolTransition()
            }

            Button {
                player.togglePause()
            } label: {
                Label(player.status == .paused ? "Play" : "Pause",
                      systemImage: player.status == .paused ? "play.fill" : "pause.fill")
                    .replacingSymbolTransition()
            }

            #if os(iOS) || os(macOS)
            RoutePicker()
                .frame(width: 28, height: 28)
                .accessibilityLabel("Audio output")
            #endif

            Button {
                player.stop()
            } label: {
                Label("Stop", systemImage: "xmark")
            }
        }
        .font(.title2)
    }

    @ViewBuilder private var statusIndicator: some View {
        switch player.status {
        case .playing:
            let image = Image(systemName: "dot.radiowaves.left.and.right")
                .foregroundStyle(.red)
            if #available(iOS 17, macOS 14, *) {
                image.symbolEffect(.variableColor.iterative, isActive: !player.isMuted)
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
        case .paused: String(localized: "Paused")
        case .reconnecting:
            // The message below says what's wrong.
            player.lastError != nil ? String(localized: "Connection problem") : String(localized: "Reconnecting…")
        }
    }
}

private nonisolated struct WidthKey: PreferenceKey {
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private extension View {
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
