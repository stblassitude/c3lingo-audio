import Combine
import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// The frontend server the app talks to.
///
/// A custom URL for testing can be entered in the app's server dialog, the Settings app
/// (iOS, iPadOS, visionOS) or the Settings window (macOS). Without one, the app uses the
/// default: `-serverURL https://example.org/` from the command line for that run, if
/// given, otherwise the production site.
final class ServerSettings: ObservableObject {
    static let shared = ServerSettings()

    static let builtInURL = URL(string: "https://audio.c3lingo.org/")!
    /// Linked from the server settings; the stores also require it to be in the app.
    static let privacyPolicyURL = URL(string: "https://github.com/stblassitude/c3lingo-audio/blob/main/PRIVACY.md")!
    static let key = "serverURL"

    /// The server used when no custom URL is stored.
    static var defaultURL: URL { commandLineURL ?? builtInURL }

    /// `-serverURL` from the command line. It is taken out of the user defaults, where it
    /// would hide the stored custom URL.
    private static let commandLineURL: URL? = {
        var arguments = UserDefaults.standard.volatileDomain(forName: UserDefaults.argumentDomain)
        let value = arguments.removeValue(forKey: key) as? String
        UserDefaults.standard.setVolatileDomain(arguments, forName: UserDefaults.argumentDomain)
        return url(from: value)
    }()

    /// The URL currently in effect.
    @Published private(set) var serverURL: URL

    /// The custom URL as stored in the user defaults; empty if the default is used.
    var customURLString: String {
        get { UserDefaults.standard.string(forKey: Self.key) ?? "" }
        set {
            UserDefaults.standard.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: Self.key)
            refresh()
        }
    }

    var isUsingDefault: Bool { serverURL == Self.defaultURL }

    private init() {
        // Before reading the stored URL, which the command line would hide.
        _ = Self.commandLineURL
        serverURL = Self.url(from: UserDefaults.standard.string(forKey: Self.key)) ?? Self.defaultURL
        // Changes made in the Settings app arrive when the app becomes active again, but
        // the change notification isn't reliable for them, so also check on activation.
        NotificationCenter.default.addObserver(forName: UserDefaults.didChangeNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { ServerSettings.shared.refresh() }
        }
        #if canImport(UIKit)
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { ServerSettings.shared.refresh() }
        }
        #endif
    }

    private func refresh() {
        let url = Self.url(from: UserDefaults.standard.string(forKey: Self.key)) ?? Self.defaultURL
        if url != serverURL {
            serverURL = url
        }
    }

    /// Parses user input into a usable server URL, or returns nil for empty or invalid input.
    /// A missing scheme defaults to https, and a trailing slash is added so relative URLs work.
    nonisolated static func url(from string: String?) -> URL? {
        guard var string = string?.trimmingCharacters(in: .whitespacesAndNewlines), !string.isEmpty else {
            return nil
        }
        if !string.contains("://") {
            string = "https://" + string
        }
        guard var components = URLComponents(string: string),
              let scheme = components.scheme?.lowercased(), scheme == "https" || scheme == "http",
              let host = components.host, !host.isEmpty else {
            return nil
        }
        if components.path.isEmpty {
            components.path = "/"
        }
        components.fragment = nil
        return components.url
    }

    /// How a server URL is shown for editing: without `https://` and without the slash
    /// of an empty path, e.g. `audio.c3lingo.org` or `http://localhost:8000`.
    nonisolated static func displayString(for url: URL) -> String {
        var string = url.absoluteString
        if url.scheme?.lowercased() == "https", let range = string.range(of: "://") {
            string.removeSubrange(string.startIndex..<range.upperBound)
        }
        if url.path == "/", url.query == nil, string.hasSuffix("/") {
            string.removeLast()
        }
        return string
    }

    /// Whether the host looks finished, e.g. `example.org`, `localhost` or an IP address,
    /// rather than still being typed, e.g. `exa`.
    nonisolated static func looksComplete(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        if host == "localhost" || host.contains(":") {
            return true
        }
        let labels = host.split(separator: ".", omittingEmptySubsequences: false)
        return labels.count >= 2 && labels.allSatisfy { !$0.isEmpty } && labels.last!.count >= 2
    }
}
