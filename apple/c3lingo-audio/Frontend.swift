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
    /// Replaced by a new one when the server changes, see `restart(_:)`.
    @Published private(set) var webView: WKWebView
    /// Why the page couldn't be loaded, in technical terms, shown below the explanation.
    @Published private(set) var loadError: String?
    @Published private(set) var hasLoaded = false
    /// Hall, language and talk per channel name, if the page sends them.
    @Published private(set) var descriptions: [String: ChannelDescription] = [:] {
        didSet { player?.descriptions = descriptions }
    }
    /// The server dialog, opened from the settings button on the page or the error screen.
    @Published var isShowingServerDialog = false
    /// The page's help text, if it sends one.
    @Published private(set) var help: Help?
    /// The help, opened from the page's help button, and by itself on the first launch.
    @Published var isShowingHelp = false
    /// Whether the app is in the background, e.g. with the screen locked. Playback doesn't
    /// need the page, and WebKit suspends it there unless the app runs a script in it, so
    /// it isn't sent the player's state until it can be seen again. The page catches up on
    /// its own then; the app sends it the current state.
    var isInBackground = false {
        didSet {
            guard isInBackground != oldValue else { return }
            Log.frontend.info("\(self.isInBackground ? "entering" : "leaving") the background")
            if !isInBackground {
                catchUp()
            }
        }
    }

    private var player: Player?
    private var playerObservation: AnyCancellable?
    private var url: URL?
    /// Set for the pages the app loads itself, which must be a c3lingo frontend; pages
    /// the user navigates to from there aren't checked.
    private var verifyPage = false
    /// The HTTP status of the page being loaded.
    private var pageStatus: Int?
    /// Whether the page has called `ready()` since it was last loaded.
    private var isPageReady = false
    /// A link that opened the app, kept until the page can take it, see `open(_:)`.
    private var pendingLink: AppLink?
    /// The web content process went away in the background; the page is loaded again
    /// when the app returns to the foreground.
    private var needsReload = false

    private static let messageHandlerName = "c3lingo"

    /// Tells the bridge the app's UI language, so the page uses the same one.
    private static var configScript: String {
        let config = ["language": Bundle.main.preferredLocalizations.first ?? "en"]
        guard let data = try? JSONSerialization.data(withJSONObject: config),
              let json = String(data: data, encoding: .utf8) else {
            return ""
        }
        return "window.__c3lingoNativeConfig = \(json);\n"
    }

    /// The page scrolls but can't be zoomed, neither by pinching nor by double tapping:
    /// limits the page's viewport to its initial scale, which WKWebView respects.
    private static let noZoomScript = """
        (() => {
            let viewport = document.querySelector('meta[name="viewport"]');
            if (!viewport) {
                viewport = document.createElement("meta");
                viewport.name = "viewport";
                viewport.content = "width=device-width, initial-scale=1";
                document.head.appendChild(viewport);
            }
            const kept = viewport.content.split(",").map((part) => part.trim())
                .filter((part) => part && !/^(minimum-scale|maximum-scale|user-scalable)\\s*=/i.test(part));
            viewport.content = [...kept, "maximum-scale=1", "user-scalable=no"].join(", ");
        })();
        """

    /// Whether the page is a c3lingo frontend: they all declare the native bridge
    /// version, whether or not they support the one the app speaks.
    private static let isFrontendScript = #"document.querySelector('meta[name="c3lingo-native-bridge"]') !== null"#

    override init() {
        webView = Self.makeWebView()
        super.init()
        configure(webView)
    }

    private static func makeWebView() -> WKWebView {
        let configuration = WKWebViewConfiguration()
        let contentController = WKUserContentController()
        if let url = Bundle.main.url(forResource: "FrontendBridge", withExtension: "js"),
           let source = try? String(contentsOf: url, encoding: .utf8) {
            contentController.addUserScript(WKUserScript(source: Self.configScript + source, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        } else {
            Log.frontend.fault("FrontendBridge.js is missing from the app bundle")
        }
        contentController.addUserScript(WKUserScript(source: Self.noZoomScript, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        configuration.userContentController = contentController
        #if os(macOS)
        return WKWebView(frame: .zero, configuration: configuration)
        #else
        return FrontendWebView(frame: .zero, configuration: configuration)
        #endif
    }

    private func configure(_ webView: WKWebView) {
        webView.configuration.userContentController.add(WeakScriptMessageHandler(self), name: Self.messageHandlerName)
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
        #if !os(macOS)
        (webView as? FrontendWebView)?.settingsButton.addAction(UIAction { [weak self] _ in
            self?.isShowingServerDialog = true
        }, for: .primaryActionTriggered)
        #endif
    }

    func attach(_ player: Player) {
        guard self.player !== player else { return }
        self.player = player
        player.descriptions = descriptions
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
        descriptions = [:]
        help = nil
        isPageReady = false
        verifyPage = true
        webView.load(URLRequest(url: url))
    }

    /// Starts over with `url` in a new web view, without anything left from before: no
    /// page state, history, cache or storage. Used when the server changes.
    func restart(_ url: URL) {
        Log.frontend.info("starting over with \(url)")
        let old = webView
        old.stopLoading()
        old.navigationDelegate = nil
        old.uiDelegate = nil
        old.configuration.userContentController.removeScriptMessageHandler(forName: Self.messageHandlerName)
        self.url = nil
        loadError = nil
        hasLoaded = false
        descriptions = [:]
        help = nil
        isPageReady = false
        let dataStore = WKWebsiteDataStore.default()
        dataStore.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) { [weak self] in
            guard let self else { return }
            let webView = Self.makeWebView()
            configure(webView)
            self.webView = webView
            load(url)
        }
    }

    func reload() {
        loadError = nil
        isPageReady = false
        verifyPage = true
        if hasLoaded {
            webView.reload()
        } else if let url {
            webView.load(URLRequest(url: url))
        }
    }

    /// Shows the help by itself once, the first time the app has it, after the page has
    /// loaded. For testing, `-helpShown YES` on the command line skips it.
    private func showHelpForFirstTime() {
        let key = "helpShown"
        guard help != nil, hasLoaded, !isShowingServerDialog, !UserDefaults.standard.bool(forKey: key) else { return }
        UserDefaults.standard.set(true, forKey: key)
        isShowingHelp = true
    }

    /// Opens a link to the app, e.g. `https://audio.c3lingo.org/#Saal_1_0_Floor`: plays the
    /// channel it names, and tells the page which channel, hall or talk it points to.
    func open(_ url: URL) {
        guard let link = AppLink(url: url) else { return }
        Log.frontend.info("opening \(url)")
        pendingLink = link
        openPendingLink()
    }

    /// Hands the pending link to the page once the page is ready and has described its
    /// channels, which tells a channel's name from a hall's.
    private func openPendingLink() {
        guard let link = pendingLink, isPageReady, !descriptions.isEmpty else { return }
        pendingLink = nil
        let detail: [String: String]
        switch link {
        case .talk(let guid):
            detail = ["talk": guid]
        case .name(let name) where descriptions[name] != nil:
            if let player, player.channel?.name != name || !player.isActive {
                player.play(Channel(name: name))
            }
            detail = ["channel": name]
        case .name(let name):
            detail = ["hall": name]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: detail),
              let json = String(data: data, encoding: .utf8) else {
            return
        }
        evaluate("window.__c3lingoNativeLink && window.__c3lingoNativeLink(\(json))")
    }

    private func catchUp() {
        if needsReload {
            needsReload = false
            webView.reload()
        } else if let player {
            sendState(of: player)
        }
    }

    // MARK: - Native to web

    private func sendState(of player: Player) {
        guard !isInBackground else { return }
        let state: String
        switch player.status {
        case .idle, .paused: state = "stopped"
        case .connecting, .reconnecting: state = "connecting"
        case .waiting: state = "waiting"
        case .playing: state = "playing"
        }
        let detail: [String: Any] = [
            "state": state,
            // A paused channel stays selected, which the protocol allows while stopped.
            "channel": player.channel?.name ?? NSNull(),
            "muted": player.isMuted,
            "error": player.lastError ?? NSNull(),
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
            isPageReady = true
            openPendingLink()
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
        case "describe":
            // Only information for display; it doesn't change the player's state.
            descriptions = ChannelDescription.all(json: body["channels"])
            openPendingLink()
        case "help":
            help = Help(json: body["content"])
            showHelpForFirstTime()
        case "showHelp":
            isShowingHelp = help != nil
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

    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse) async -> WKNavigationResponsePolicy {
        if navigationResponse.isForMainFrame {
            pageStatus = (navigationResponse.response as? HTTPURLResponse)?.statusCode
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
        endRefreshing()
        guard verifyPage else {
            pageLoaded()
            return
        }
        verifyPage = false
        if let status = pageStatus, status >= 400 {
            Log.frontend.error("\(webView.url?.absoluteString ?? "-") returned HTTP \(status)")
            notLoaded("HTTP \(status): \(HTTPURLResponse.localizedString(forStatusCode: status))")
            return
        }
        // The splash screen stays until then, so a page that isn't a frontend never shows.
        webView.evaluateJavaScript(Self.isFrontendScript) { [weak self] result, error in
            guard let self, webView === self.webView else { return }
            if result as? Bool == true {
                pageLoaded()
            } else {
                Log.frontend.error("\(webView.url?.absoluteString ?? "-") is not a c3lingo frontend\(error.map { ": \($0)" } ?? "")")
                notLoaded(String(localized: "The site is not compatible."))
            }
        }
    }

    private func notLoaded(_ reason: String) {
        hasLoaded = false
        loadError = reason
    }

    private func pageLoaded() {
        hasLoaded = true
        loadError = nil
        showHelpForFirstTime()
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
        // Nobody sees the page in the background; load it when it can be seen again.
        guard !isInBackground else {
            Log.frontend.error("web content process terminated, reloading in the foreground")
            needsReload = true
            return
        }
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

#if !os(macOS)
/// The web view with a small settings button in the bottom right corner of the page,
/// over the page and scrolling with it. On a page shorter than the view, it sits in the
/// bottom right corner of the view.
final class FrontendWebView: WKWebView {
    let settingsButton: UIButton = {
        var configuration = UIButton.Configuration.gray()
        configuration.image = UIImage(systemName: "gearshape.fill")
        configuration.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(textStyle: .subheadline)
        configuration.cornerStyle = .capsule
        let button = UIButton(configuration: configuration)
        button.accessibilityLabel = String(localized: "Server")
        return button
    }()
    private var contentSizeObservation: NSKeyValueObservation?

    override init(frame: CGRect, configuration: WKWebViewConfiguration) {
        super.init(frame: frame, configuration: configuration)
        scrollView.addSubview(settingsButton)
        contentSizeObservation = scrollView.observe(\.contentSize) { [weak self] _, _ in
            MainActor.assumeIsolated { self?.setNeedsLayout() }
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let size = settingsButton.intrinsicContentSize
        let margin: CGFloat = 12
        // The page may extend below the home indicator (viewport-fit=cover); stay clear of it.
        let insets = scrollView.adjustedContentInset
        let bottomInset = max(safeAreaInsets.bottom - insets.bottom, 0)
        let visibleHeight = scrollView.bounds.height - insets.top - insets.bottom
        let bottom = max(scrollView.contentSize.height, visibleHeight)
        settingsButton.frame = CGRect(
            x: scrollView.bounds.width - safeAreaInsets.right - margin - size.width,
            y: bottom - bottomInset - margin - size.height,
            width: size.width,
            height: size.height)
        // The web view adds its own content views as pages load.
        scrollView.bringSubviewToFront(settingsButton)
    }
}
#endif

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

/// What a link that opens the app points to, from its fragment: a talk by its GUID, e.g.
/// `https://audio.c3lingo.org/#4f9e1c2a-…`, or a channel or hall by its name, e.g.
/// `https://audio.c3lingo.org/#Saal_1_0_Floor` or `c3lingo://#Saal_1`.
nonisolated enum AppLink: Equatable, Sendable {
    case talk(String)
    case name(String)

    init?(url: URL) {
        guard let fragment = URLComponents(url: url, resolvingAgainstBaseURL: false)?.fragment, !fragment.isEmpty else {
            return nil
        }
        self = UUID(uuidString: fragment) != nil ? .talk(fragment) : .name(fragment)
    }
}
