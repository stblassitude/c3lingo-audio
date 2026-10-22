import Combine
import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var player: Player
    @StateObject private var frontend = Frontend()
    @ObservedObject private var settings = ServerSettings.shared

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                FrontendView(frontend: frontend)
                    .ignoresSafeArea(edges: player.channel == nil ? .all : .top)
                if let error = frontend.loadError {
                    LoadErrorView(message: error, url: settings.serverURL) {
                        frontend.reload()
                    }
                }
            }
            if player.channel != nil {
                PlayerBar()
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.default, value: player.channel != nil)
        .onAppear {
            frontend.attach(player)
            frontend.load(settings.serverURL)
        }
        .onReceive(settings.$serverURL.dropFirst().removeDuplicates()) { url in
            player.stop()
            frontend.load(url)
        }
    }
}

private struct LoadErrorView: View {
    let message: String
    let url: URL
    let retry: () -> Void

    #if !os(macOS)
    @Environment(\.openURL) private var openURL
    #endif

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text("Unable to load the channel list")
                .font(.title2.bold())
            Text(message)
                .foregroundStyle(.secondary)
            Text(url.absoluteString)
                .font(.footnote.monospaced())
                .foregroundStyle(.secondary)
            Button("Try Again", action: retry)
                .buttonStyle(.borderedProminent)
                .padding(.top, 8)
            #if os(macOS)
            OpenSettingsButton(title: "Change Server…")
            #else
            Button("Change Server in Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    openURL(url)
                }
            }
            #endif
        }
        .multilineTextAlignment(.center)
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.background)
    }
}

#if os(macOS)
/// Opens the app's Settings window.
struct OpenSettingsButton: View {
    let title: LocalizedStringKey

    var body: some View {
        if #available(macOS 14, *) {
            SettingsLink { Text(title) }
        } else {
            Button(title) {
                let action = if #available(macOS 13, *) { "showSettingsWindow:" } else { "showPreferencesWindow:" }
                NSApp.sendAction(Selector((action)), to: nil, from: nil)
            }
        }
    }
}
#endif

#Preview {
    ContentView()
        .environmentObject(Player())
}
