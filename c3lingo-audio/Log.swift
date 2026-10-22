import os

nonisolated enum Log {
    private static let subsystem = "org.c3lingo.audio"

    static let galene = Logger(subsystem: subsystem, category: "galene")
    static let player = Logger(subsystem: subsystem, category: "player")
    static let audio = Logger(subsystem: subsystem, category: "audio")
    static let frontend = Logger(subsystem: subsystem, category: "frontend")
}
