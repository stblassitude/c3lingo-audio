import Foundation

/// An audio channel, i.e. a Galène group, as listed in the server's `public-groups.json`.
nonisolated struct Channel: Equatable, Sendable {
    /// The Galène group name, e.g. `Saal_1_0_Floor` or `test`.
    let name: String
    let displayName: String
    /// The Galène WebSocket endpoint, e.g. `wss://example.org/ws`.
    let endpoint: URL?
    /// The group URL, e.g. `https://example.org/group/test/`.
    let location: URL?

    init(name: String, displayName: String? = nil, endpoint: URL? = nil, location: URL? = nil) {
        self.name = name
        self.displayName = displayName?.isEmpty == false ? displayName! : name
        self.endpoint = endpoint
        self.location = location
    }

    /// Creates a channel from the JSON object the web frontend passes to the bridge.
    init?(json: [String: Any], relativeTo base: URL?) {
        guard let name = json["name"] as? String, !name.isEmpty else {
            return nil
        }
        func url(_ key: String) -> URL? {
            (json[key] as? String).flatMap { URL(string: $0, relativeTo: base)?.absoluteURL }
        }
        self.init(name: name, displayName: json["displayName"] as? String, endpoint: url("endpoint"), location: url("location"))
    }

    /// Returns the WebSocket endpoint, asking the server for the group status if the frontend didn't provide it.
    func resolveEndpoint(server: URL) async throws -> URL {
        if let endpoint {
            return endpoint
        }
        let group = location ?? server.appendingPathComponent("group").appendingPathComponent(name, isDirectory: true)
        let (data, _) = try await URLSession.shared.data(from: group.appendingPathComponent(".status"))
        struct Status: Decodable { let endpoint: String }
        let status = try JSONDecoder().decode(Status.self, from: data)
        guard let url = URL(string: status.endpoint, relativeTo: group)?.absoluteURL else {
            throw URLError(.badURL)
        }
        return url
    }
}
