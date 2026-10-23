import AVKit
import SwiftUI

/// The native playback controls, shown at the bottom while a channel is selected.
struct PlayerBar: View {
    @EnvironmentObject private var player: Player

    var body: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                Text(player.channel?.displayName ?? "")
                    .font(.headline)
                    .lineLimit(1)
                    .truncationMode(.tail)
                HStack(spacing: 6) {
                    statusIndicator
                    Text(statusText)
                        .lineLimit(1)
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)

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
        .labelStyle(.iconOnly)
        .font(.title2)
        .buttonStyle(.borderless)
        .padding(.horizontal)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .background(.bar)
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
        case .waiting: String(localized: "Waiting for audio…")
        case .playing: player.isMuted ? String(localized: "Live, muted") : String(localized: "Live")
        case .paused: String(localized: "Paused")
        case .reconnecting:
            if let error = player.lastError {
                String(localized: "Reconnecting: \(error)")
            } else {
                String(localized: "Reconnecting…")
            }
        }
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
