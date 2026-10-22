import AVFoundation
import Combine
import MediaPlayer
import Network
import os

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

    private var session: GaleneSession?
    private var reconnectTask: Task<Void, Never>?
    private var attempt = 0
    private let pathMonitor = NWPathMonitor()
    private var networkAvailable = true
    private var observers: [NSObjectProtocol] = []

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
        case .joined:
            if status != .playing {
                status = .waiting
            }
        case .receiving:
            attempt = 0
            lastError = nil
            status = .playing
        case .waiting:
            status = .waiting
        case .failed(let error):
            lastError = error.localizedDescription
            scheduleReconnect()
        }
        updateNowPlaying()
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
        commands.stopCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }
        commands.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self, self.channel != nil else { return .noActionableNowPlayingItem }
            self.togglePause()
            return .success
        }
        for command in [commands.nextTrackCommand, commands.previousTrackCommand, commands.skipForwardCommand, commands.skipBackwardCommand, commands.seekForwardCommand, commands.seekBackwardCommand, commands.changePlaybackPositionCommand] {
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
        center.nowPlayingInfo = [
            MPMediaItemPropertyTitle: channel.displayName,
            MPMediaItemPropertyArtist: ServerSettings.shared.serverURL.host ?? "c3lingo",
            MPNowPlayingInfoPropertyIsLiveStream: true,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyPlaybackRate: running ? 1.0 : 0.0,
        ]
        #if os(macOS)
        center.playbackState = running ? .playing : .paused
        #endif
    }
}
