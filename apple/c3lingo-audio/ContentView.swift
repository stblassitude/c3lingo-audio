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
                    // A new web view after a server change needs a new view.
                    .id(ObjectIdentifier(frontend.webView))
                    .ignoresSafeArea(edges: player.channel == nil ? .all : .top)
                if let error = frontend.loadError {
                    LoadErrorView(reason: error, url: settings.serverURL) {
                        frontend.reload()
                    } changeServer: {
                        frontend.isShowingServerDialog = true
                    }
                } else if !frontend.hasLoaded {
                    SplashView()
                        .transition(.opacity)
                }
            }
            .animation(.easeOut, value: frontend.hasLoaded)
            .sheet(isPresented: $frontend.isShowingHelp) {
                if let help = frontend.help {
                    HelpView(help: help)
                }
            }
            if let channel = player.channel {
                PlayerBar(description: frontend.descriptions[channel.name])
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.default, value: player.channel != nil)
        .sheet(isPresented: $frontend.isShowingServerDialog) {
            ServerDialog(reload: frontend.reload)
        }
        .onAppear {
            frontend.attach(player)
            frontend.load(settings.serverURL)
        }
        .onOpenURL { url in
            frontend.open(url)
        }
        .onReceive(settings.$serverURL.dropFirst().removeDuplicates()) { url in
            player.stop()
            frontend.restart(url)
        }
    }
}

/// Shown until the frontend has loaded. The logo sits where the launch screen puts it,
/// centred in the whole screen, so the handover from the launch screen is seamless.
private struct SplashView: View {
    var body: some View {
        Image("Logo")
            .accessibilityHidden(true)
            .overlay(alignment: .bottom) {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("Loading…")
                        .foregroundStyle(.secondary)
                }
                .fixedSize()
                // Place below the logo rather than over it.
                .alignmentGuide(.bottom) { $0[.top] - 32 }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(.background)
            .ignoresSafeArea()
    }
}

/// Explains in plain words what to check, with the technical reason and the URL below.
private struct LoadErrorView: View {
    let reason: String
    let url: URL
    let retry: () -> Void
    let changeServer: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text("Unable to load the channel list")
                .font(.title2.bold())
            Text("The configured server URL doesn't appear to be a c3lingo audio site. Please check your network connection and the URL you entered.")
                .foregroundStyle(.secondary)
            VStack(spacing: 4) {
                Text(reason)
                    .font(.footnote)
                Text(url.absoluteString)
                    .font(.footnote.monospaced())
            }
            .foregroundStyle(.secondary)
            Button("Try Again", action: retry)
                .buttonStyle(.borderedProminent)
                .padding(.top, 8)
            #if os(macOS)
            OpenSettingsButton(title: "Change Server…")
            #else
            Button("Change Server…", action: changeServer)
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
