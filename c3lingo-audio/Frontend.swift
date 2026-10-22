import Combine
import os
import SwiftUI
import WebKit

/// Hosts the web frontend loaded from the server, and connects it to the native player
/// through `FrontendBridge.js`, which implements version 2 of the frontend's native
/// bridge protocol (`docs/playback-api.md` in listen-frontend).
///
/// The player's state is the only source of truth: commands from the page go to the
/// player, and every state change, whatever caused it, is reported back in full.
final class Frontend: NSObject, ObservableObject {
    let webView: WKWebView
    @Published private(set) var loadError: String?
    @Published private(set) var hasLoaded = false

    private var player: Player?
    private var playerObservation: AnyCancellable?
    private var url: URL?

    private static let messageHandlerName = "c3lingo"

    override init() {
        let configuration = WKWebViewConfiguration()
        let contentController = WKUserContentController()
        if let url = Bundle.main.url(forResource: "FrontendBridge", withExtension: "js"),
           let source = try? String(contentsOf: url, encoding: .utf8) {
            contentController.addUserScript(WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        } else {
            Log.frontend.fault("FrontendBridge.js is missing from the app bundle")
        }
        configuration.userContentController = contentController
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()

        contentController.add(WeakScriptMessageHandler(self), name: Self.messageHandlerName)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = false
        #if DEBUG
        if #available(iOS 16.4, macOS 13.3, *) {
            webView.isInspectable = true
        }
        #endif
        #if os(iOS)
        let refreshControl = UIRefreshControl()
        refreshControl.addAction(UIAction { [weak self] _ in self?.reload() }, for: .valueChanged)
        webView.scrollView.refreshControl = refreshControl
        #endif
    }

    func attach(_ player: Player) {
        guard self.player !== player else { return }
        self.player = player
        // objectWillChange fires before the change; deliver on the next turn of the run loop.
        playerObservation = player.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self, weak player] _ in
                guard let self, let player else { return }
                self.sendState(of: player)
            }
    }

    func load(_ url: URL) {
        guard url != self.url else { return }
        Log.frontend.info("loading \(url)")
        self.url = url
        loadError = nil
        hasLoaded = false
        webView.load(URLRequest(url: url))
    }

    func reload() {
        loadError = nil
        if hasLoaded {
            webView.reload()
        } else if let url {
            webView.load(URLRequest(url: url))
        }
    }

    // MARK: - Native to web

    private func sendState(of player: Player) {
        let state: String
        switch player.status {
        case .idle, .paused: state = "stopped"
        case .connecting, .waiting, .reconnecting: state = "connecting"
        case .playing: state = "playing"
        }
        let error = player.status == .waiting ? String(localized: "Waiting for audio") : player.lastError
        let detail: [String: Any] = [
            "state": state,
            // A paused channel stays selected, which the protocol allows while stopped.
            "channel": player.channel?.name ?? NSNull(),
            "muted": player.isMuted,
            "error": error ?? NSNull(),
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: detail),
              let json = String(data: data, encoding: .utf8) else {
            return
        }
        evaluate("window.__c3lingoNativeState && window.__c3lingoNativeState(\(json))")
    }

    private func evaluate(_ script: String) {
        webView.evaluateJavaScript(script) { _, error in
            if let error {
                Log.frontend.error("script failed: \(error)")
            }
        }
    }

    // MARK: - Web to native

    fileprivate func receive(_ message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame,
              let body = message.body as? [String: Any],
              let command = body["cmd"] as? String else {
            return
        }
        Log.frontend.debug("bridge command \(command)")
        switch command {
        case "ready":
            // Also brings a reloaded page up to date while audio keeps playing.
            if let player {
                sendState(of: player)
            }
        case "play":
            guard let json = body["channel"] as? [String: Any],
                  let channel = Channel(json: json, relativeTo: webView.url) else {
                Log.frontend.error("invalid channel in play message: \(String(describing: body["channel"]))")
                return
            }
            player?.play(channel)
        case "stop":
            player?.stop()
        case "setMuted":
            if let muted = body["muted"] as? Bool, player?.isMuted != muted {
                player?.isMuted = muted
            }
        default:
            Log.frontend.warning("unknown bridge command \(command)")
        }
    }

    private func isFrontend(_ url: URL) -> Bool {
        guard let base = self.url else { return false }
        return url.host == base.host && url.port == base.port && url.scheme == base.scheme
    }

    private func openExternally(_ url: URL) {
        #if os(macOS)
        NSWorkspace.shared.open(url)
        #else
        UIApplication.shared.open(url)
        #endif
    }
}

extension Frontend: WKNavigationDelegate, WKUIDelegate {
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
        guard let url = navigationAction.request.url else { return .cancel }
        // Links leaving the frontend, e.g. to the schedule, open in the browser.
        if navigationAction.targetFrame?.isMainFrame == true, navigationAction.navigationType == .linkActivated, !isFrontend(url) {
            openExternally(url)
            return .cancel
        }
        return .allow
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url {
            openExternally(url)
        }
        return nil
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        hasLoaded = true
        loadError = nil
        endRefreshing()
        // For testing: `-autoplay <channel>` selects a channel once the page has loaded.
        if let channel = UserDefaults.standard.string(forKey: "autoplay"), !channel.isEmpty,
           let data = try? JSONSerialization.data(withJSONObject: [channel]),
           let json = String(data: data, encoding: .utf8) {
            evaluate("setTimeout(() => { location.hash = \(json)[0]; }, 500)")
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        failed(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        failed(error)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        Log.frontend.error("web content process terminated, reloading")
        webView.reload()
    }

    private func failed(_ error: Error) {
        endRefreshing()
        if (error as NSError).code == NSURLErrorCancelled {
            return
        }
        Log.frontend.error("loading failed: \(error)")
        loadError = error.localizedDescription
    }

    private func endRefreshing() {
        #if os(iOS)
        webView.scrollView.refreshControl?.endRefreshing()
        #endif
    }
}

/// The user content controller keeps its message handlers alive; this avoids a retain cycle.
private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    private weak var frontend: Frontend?

    init(_ frontend: Frontend) {
        self.frontend = frontend
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        frontend?.receive(message)
    }
}

#if os(macOS)
struct FrontendView: NSViewRepresentable {
    let frontend: Frontend

    func makeNSView(context: Context) -> WKWebView { frontend.webView }
    func updateNSView(_ webView: WKWebView, context: Context) {}
}
#else
struct FrontendView: UIViewRepresentable {
    let frontend: Frontend

    func makeUIView(context: Context) -> WKWebView { frontend.webView }
    func updateUIView(_ webView: WKWebView, context: Context) {}
}
#endif
