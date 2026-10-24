import Combine
import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// The frontend server the app talks to.
///
/// Defaults to the production site. A custom URL for testing can be entered in the
/// Settings app (iOS, iPadOS, visionOS) or the app's Settings window (macOS), or passed
/// on the command line as `-serverURL https://example.org/`.
final class ServerSettings: ObservableObject {
    static let shared = ServerSettings()

    static let defaultURL = URL(string: "https://audio.c3lingo.org/")!
    static let key = "serverURL"

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
}
