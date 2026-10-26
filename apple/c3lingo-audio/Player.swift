import AVFoundation
import Combine
import MediaPlayer
import Network
import os
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// Plays one channel at a time and keeps it playing: reconnects with backoff when the
/// connection drops, after network changes and after audio interruptions.
final class Player: ObservableObject {
    enum Status: Equatable {
        case idle
        case connecting
        /// Joined, but nobody is sending audio right now.
        case waiting
        case playing
        case paused
        case reconnecting
    }

    @Published private(set) var channel: Channel?
    @Published private(set) var status: Status = .idle
    @Published private(set) var lastError: String?

    @Published var isMuted = false {
        didSet { session?.isMuted = isMuted }
    }

    /// Hall, language and talk per channel name, if the frontend sends them, for the
    /// system controls. Only for display, so not published as part of the state.
    var descriptions: [String: ChannelDescription] = [:] {
        didSet {
            if descriptions != oldValue { updateNowPlaying() }
        }
    }

    private var session: GaleneSession?
    private var reconnectTask: Task<Void, Never>?
    private var attempt = 0
    private let pathMonitor = NWPathMonitor()
    private var networkAvailable = true
    private var observers: [NSObjectProtocol] = []

    /// The c3lingo logo in place of an album cover, on the lock screen and the watch.
    private static let artwork: MPMediaItemArtwork? = {
        #if canImport(UIKit)
        guard let image = UIImage(named: "Artwork") else { return nil }
        #else
        guard let image = NSImage(named: "Artwork") else { return nil }
        #endif
        return MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }()

    init() {
        configureRemoteCommands()
        observeNetwork()
        observeAudioSession()
    }

    // MARK: - Controls

    func play(_ channel: Channel) {
        Log.player.info("play \(channel.name)")
        self.channel = channel
        attempt = 0
        lastError = nil
        connect(status: .connecting)
    }

    /// Disconnects but keeps the channel, so playback can be resumed.
    func pause() {
        guard channel != nil else { return }
        teardown()
        status = .paused
        updateNowPlaying()
    }

    func resume() {
        guard let channel else { return }
        play(channel)
    }

    func togglePause() {
        status == .paused ? resume() : pause()
    }

    /// Stops playback and forgets the channel.
    func stop() {
        guard channel != nil else { return }
        Log.player.info("stop")
        teardown()
        channel = nil
        status = .idle
        lastError = nil
        updateNowPlaying()
    }

    var isActive: Bool {
        channel != nil && status != .paused
    }

    // MARK: - Connection handling

    private func connect(status newStatus: Status) {
        guard let channel else { return }
        teardown()
        status = newStatus
        updateNowPlaying()
        let server = ServerSettings.shared.serverURL
        reconnectTask = Task { [weak self] in
            do {
                let endpoint = try await channel.resolveEndpoint(server: server)
                guard let self, !Task.isCancelled, self.channel == channel else { return }
                let session = GaleneSession(endpoint: endpoint, group: channel.name)
                session.isMuted = self.isMuted
                session.onEvent = { [weak self, weak session] event in
                    guard let self, let session, session === self.session else { return }
                    self.handle(event)
                }
                self.session = session
                session.start()
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.handle(.failed(error))
            }
        }
    }

    private func handle(_ event: GaleneSession.Event) {
        switch event {
        case .receiving:
            attempt = 0
            lastError = nil
            status = .playing
        case .waiting:
            // Everything works, there is just nothing to hear.
            attempt = 0
            lastError = nil
            status = .waiting
        case .failed(let error):
            lastError = Self.message(for: error)
            scheduleReconnect()
        }
        updateNowPlaying()
    }

    /// Tells listeners whose problem it is, worded like the frontend; details go to the log.
    private static func message(for error: Error) -> String {
        if let error = error as? GaleneError, error.isUnavailable {
            String(localized: "This channel isn't available right now. Retrying…")
        } else {
            String(localized: "Can't connect. Make sure you are on the event Wi-Fi. Retrying…")
        }
    }

    private func scheduleReconnect() {
        guard isActive else { return }
        teardown()
        status = .reconnecting
        attempt += 1
        let delay = min(pow(2, Double(attempt - 1)), 15)
        Log.player.info("reconnecting in \(delay) s (attempt \(self.attempt))")
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self, !Task.isCancelled else { return }
            self.connect(status: .reconnecting)
        }
    }

    /// Reconnects right away, e.g. because the network came back.
    private func reconnectNow() {
        guard isActive, status == .reconnecting || status == .waiting else { return }
        Log.player.info("reconnecting now")
        connect(status: .reconnecting)
    }

    private func teardown() {
        reconnectTask?.cancel()
        reconnectTask = nil
        session?.close()
        session = nil
    }

    private func observeNetwork() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                defer { self.networkAvailable = satisfied }
                if satisfied && !self.networkAvailable {
                    self.reconnectNow()
                }
            }
        }
        pathMonitor.start(queue: .main)
    }

    private func observeAudioSession() {
        #if !os(macOS)
        let center = NotificationCenter.default
        // Sent when an interruption, e.g. a phone call, is over.
        if #available(iOS 27, visionOS 27, *) {
            observers.append(center.addObserver(forName: AVAudioSession.resumptionRecommendationNotification, object: nil, queue: .main) { [weak self] note in
                let context = note.userInfo?[AVAudioSession.resumptionContextKey] as? AVAudioSession.ResumptionContext
                let shouldResume = context?.recommendation == .shouldResume
                MainActor.assumeIsolated { self?.interruptionEnded(shouldResume: shouldResume) }
            })
        } else {
            observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
                let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
                let options = AVAudioSession.InterruptionOptions(rawValue: note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
                guard type == .ended else { return }
                MainActor.assumeIsolated { self?.interruptionEnded(shouldResume: options.contains(.shouldResume)) }
            })
        }
        // Like Music and Podcasts, pause when the headphones go away instead of
        // carrying on through the speaker.
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
            guard reason == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated {
                guard let self, self.isActive else { return }
                Log.player.info("audio output went away, pausing")
                self.pause()
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.isActive else { return }
                self.connect(status: .reconnecting)
            }
        })
        #endif
    }

    private func interruptionEnded(shouldResume: Bool) {
        guard isActive else { return }
        // Live audio can't continue where it left off; reconnect if allowed, otherwise wait for the user.
        if shouldResume {
            connect(status: .reconnecting)
        } else {
            pause()
        }
    }

    // MARK: - Lock screen and Control Center

    private func configureRemoteCommands() {
        let commands = MPRemoteCommandCenter.shared()
        commands.playCommand.addTarget { [weak self] _ in
            guard let self, self.channel != nil else { return .noActionableNowPlayingItem }
            self.resume()
            return .success
        }
        commands.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }
        commands.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self, self.channel != nil else { return .noActionableNowPlayingItem }
            self.togglePause()
            return .success
        }
        // With stop enabled, the lock screen shows stop instead of pause for a live stream.
        for command in [commands.stopCommand, commands.nextTrackCommand, commands.previousTrackCommand, commands.skipForwardCommand, commands.skipBackwardCommand, commands.seekForwardCommand, commands.seekBackwardCommand, commands.changePlaybackPositionCommand] {
            command.isEnabled = false
        }
    }

    private func updateNowPlaying() {
        let center = MPNowPlayingInfoCenter.default()
        guard let channel else {
            center.nowPlayingInfo = nil
            #if os(macOS)
            center.playbackState = .stopped
            #endif
            return
        }
        let running = status != .paused
        let description = descriptions[channel.name]
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: description?.title ?? channel.displayName,
            MPMediaItemPropertyArtist: description?.talkText ?? ServerSettings.shared.serverURL.host ?? "c3lingo",
            MPNowPlayingInfoPropertyIsLiveStream: true,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyPlaybackRate: running ? 1.0 : 0.0,
        ]
        if let artwork = Self.artwork {
            info[MPMediaItemPropertyArtwork] = artwork
        }
        center.nowPlayingInfo = info
        #if os(macOS)
        center.playbackState = running ? .playing : .paused
        #endif
    }
}
