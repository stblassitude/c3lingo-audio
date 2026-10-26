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

/// What the frontend knows about a channel beyond its name: the hall, the language being
/// interpreted into and the current talk, from the bridge's optional `describe` message.
nonisolated struct ChannelDescription: Equatable, Sendable {
    struct Language: Equatable, Sendable {
        let name: String
        let flag: String?
    }

    struct Talk: Equatable, Sendable {
        let title: String
        /// The talk's page in the schedule, only if it is an `http` or `https` URL.
        let url: URL?
        /// The start date and time in ISO 8601 with the event's UTC offset, e.g.
        /// `2026-12-27T14:30:00+01:00`; older frontends send only the local time, e.g. `14:30`.
        let start: String
        let started: Bool
        /// The schedule day, if the talk isn't today.
        let day: Int?
    }

    /// The hall's short name, e.g. `One`.
    let room: String?
    let language: Language?
    let talk: Talk?

    init(json: [String: Any]) {
        func string(_ json: [String: Any]?, _ key: String) -> String? {
            (json?[key] as? String).flatMap { $0.isEmpty ? nil : $0 }
        }
        room = string(json, "room")
        let language = json["language"] as? [String: Any]
        self.language = string(language, "name").map { Language(name: $0, flag: string(language, "flag")) }
        let talk = json["talk"] as? [String: Any]
        if let title = string(talk, "title"), let start = string(talk, "start") {
            let url = string(talk, "url").flatMap(URL.init(string:)).flatMap { ["http", "https"].contains($0.scheme?.lowercased()) ? $0 : nil }
            self.talk = Talk(title: title, url: url, start: start, started: talk?["started"] as? Bool ?? false, day: talk?["day"] as? Int)
        } else {
            self.talk = nil
        }
    }

    /// The hall and language, e.g. "One · 🇬🇧 English", or nil if the language isn't known.
    var title: String? {
        guard let language else { return nil }
        let name = [language.flag, language.name].compactMap { $0 }.joined(separator: " ")
        return [room, name].compactMap { $0 }.joined(separator: " · ")
    }

    /// The current or next talk, e.g. "Since 14:30: Opening" or "Next: Day 2, 10:00: Keynote",
    /// worded like the frontend's hall cards.
    var talkText: String? {
        guard let talk else { return nil }
        let start = Self.startText(talk.start)
        let when = talk.day.map { String(localized: "Day \($0), \(start)") } ?? start
        return talk.started ? String(localized: "Since \(when): \(talk.title)") : String(localized: "Next: \(when): \(talk.title)")
    }

    /// The start time in the device's time zone, as people write it in the app's language:
    /// `1:45pm` in English, `13:45` otherwise.
    private static func startText(_ start: String) -> String {
        guard let (hour, minute) = startTime(start) else { return start }
        if Bundle.main.preferredLocalizations.first?.hasPrefix("en") ?? true {
            return String(format: "%d:%02d%@", hour % 12 == 0 ? 12 : hour % 12, minute, hour < 12 ? "am" : "pm")
        }
        return String(format: "%02d:%02d", hour, minute)
    }

    /// The hour and minute `start` gives, in the device's time zone.
    private static func startTime(_ start: String) -> (Int, Int)? {
        if let date = ISO8601DateFormatter().date(from: start) {
            let components = Calendar.current.dateComponents([.hour, .minute], from: date)
            guard let hour = components.hour, let minute = components.minute else { return nil }
            return (hour, minute)
        }
        // Older frontends send only the local time.
        let parts = start.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, parts[1].count == 2, let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0..<24).contains(hour), (0..<60).contains(minute) else {
            return nil
        }
        return (hour, minute)
    }

    /// Parses the `channels` object of a `describe` message, keyed by channel name.
    static func all(json: Any?) -> [String: ChannelDescription] {
        guard let json = json as? [String: Any] else { return [:] }
        return json.compactMapValues { ($0 as? [String: Any]).map(ChannelDescription.init(json:)) }
    }
}
