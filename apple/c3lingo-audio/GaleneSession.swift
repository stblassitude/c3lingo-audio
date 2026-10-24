import Foundation
import LiveKitWebRTC
import os

enum GaleneError: LocalizedError {
    case joinFailed(String)
    case kicked(String?)
    case timeout
    case connectionFailed
    case mediaTimeout

    var errorDescription: String? {
        switch self {
        case .joinFailed(let reason): "Unable to join the channel: \(reason)"
        case .kicked(let reason): reason ?? "The server closed the connection"
        case .timeout: "The server stopped responding"
        case .connectionFailed: "The audio connection failed"
        case .mediaTimeout: "The audio connection timed out"
        }
    }

    /// The server is reachable but won't let us join, as opposed to a network problem.
    var isUnavailable: Bool {
        switch self {
        case .joinFailed, .kicked: true
        case .timeout, .connectionFailed, .mediaTimeout: false
        }
    }
}

/// A receive-only client for one Galène group, speaking version 2 of the Galène protocol.
///
/// Galène sends one offer per remote stream; each one gets its own peer connection.
/// The session doesn't retry on its own: once it fails or closes it's done, and the
/// owner creates a new one.
final class GaleneSession {
    enum Event {
        /// Audio is flowing from at least one stream.
        case receiving
        /// Joined, but nobody is sending: no stream was offered after joining, or the
        /// last one was closed because the sender stopped. Audio follows when they start.
        case waiting
        case failed(Error)
    }

    var onEvent: ((Event) -> Void)?

    var isMuted = false {
        didSet { downstreams.values.forEach { $0.setMuted(isMuted) } }
    }

    private let endpoint: URL
    private let group: String
    private let id = "app-" + UUID().uuidString.prefix(8).lowercased()
    private var socket: URLSessionWebSocketTask?
    private var downstreams: [String: Downstream] = [:]
    private var iceServers: [LKRTCIceServer] = []
    private var keepalive: Task<Void, Never>?
    private var offerTimeout: Task<Void, Never>?
    /// Streams offered but not yet connected, failing when ICE doesn't get through.
    private var mediaTimeouts: [String: Task<Void, Never>] = [:]
    private var lastReceived = Date.now
    private var isClosed = false

    private static let pingInterval: UInt64 = 5_000_000_000
    private static let timeout: TimeInterval = 15
    /// After joining, Galène offers the streams that are already there right away.
    private static let offerTimeout: UInt64 = 2_000_000_000
    /// Time for ICE to connect after answering an offer.
    private static let mediaTimeout: UInt64 = 15_000_000_000

    init(endpoint: URL, group: String) {
        self.endpoint = endpoint
        self.group = group
    }

    func start() {
        Log.galene.info("connecting to \(self.endpoint) for group \(self.group)")
        let socket = URLSession.shared.webSocketTask(with: endpoint)
        self.socket = socket
        socket.resume()
        send(["type": "handshake", "version": ["2"], "id": id])
        Task { await receive(from: socket) }
        keepalive = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.pingInterval)
                guard let self, !Task.isCancelled else { return }
                if Date.now.timeIntervalSince(lastReceived) > Self.timeout {
                    fail(GaleneError.timeout)
                    return
                }
                send(["type": "ping"])
                #if DEBUG
                downstreams.values.forEach { $0.logStatistics() }
                #endif
            }
        }
    }

    func close() {
        guard !isClosed else { return }
        isClosed = true
        keepalive?.cancel()
        offerTimeout?.cancel()
        mediaTimeouts.values.forEach { $0.cancel() }
        mediaTimeouts.removeAll()
        downstreams.values.forEach { $0.close() }
        downstreams.removeAll()
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        Log.galene.info("closed session for group \(self.group)")
    }

    private func fail(_ error: Error) {
        guard !isClosed else { return }
        Log.galene.error("session for group \(self.group) failed: \(error)")
        close()
        onEvent?(.failed(error))
    }

    // MARK: - Signalling

    private func receive(from socket: URLSessionWebSocketTask) async {
        while !isClosed {
            do {
                let message = try await socket.receive()
                lastReceived = .now
                // Messages are handled strictly in order: ICE candidates follow right
                // after an offer and need its peer connection.
                switch message {
                case .string(let text):
                    await handle(text)
                case .data(let data):
                    await handle(String(decoding: data, as: UTF8.self))
                @unknown default:
                    break
                }
            } catch {
                fail(error)
                return
            }
        }
    }

    private func send(_ message: [String: Any]) {
        guard !isClosed, let socket,
              let data = try? JSONSerialization.data(withJSONObject: message),
              let text = String(data: data, encoding: .utf8) else {
            return
        }
        socket.send(.string(text)) { [weak self] error in
            if let error {
                Task { @MainActor [self] in self?.fail(error) }
            }
        }
    }

    private func handle(_ text: String) async {
        guard let data = text.data(using: .utf8),
              let message = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = message["type"] as? String else {
            Log.galene.warning("ignoring malformed message \(text)")
            return
        }
        switch type {
        case "handshake":
            send(["type": "join", "kind": "join", "group": group, "username": id])
        case "ping":
            send(["type": "pong"])
        case "joined":
            handleJoined(message)
        case "offer":
            await handleOffer(message)
        case "ice":
            handleRemoteCandidate(message)
        case "close":
            if let streamID = message["id"] as? String {
                closeDownstream(streamID)
            }
        case "usermessage":
            if message["kind"] as? String == "error" {
                Log.galene.error("server error: \(String(describing: message["value"]))")
            }
        default:
            break
        }
    }

    private func handleJoined(_ message: [String: Any]) {
        switch message["kind"] as? String {
        case "join":
            iceServers = Self.iceServers(from: message["rtcConfiguration"])
            send(["type": "request", "request": ["": ["audio"]]])
            offerTimeout = Task { [weak self] in
                try? await Task.sleep(nanoseconds: Self.offerTimeout)
                guard let self, !Task.isCancelled, !isClosed, downstreams.isEmpty else { return }
                onEvent?(.waiting)
            }
        case "fail":
            fail(GaleneError.joinFailed(message["value"] as? String ?? "unknown reason"))
        case "leave":
            fail(GaleneError.kicked(message["value"] as? String))
        default:
            break
        }
    }

    private func handleOffer(_ message: [String: Any]) async {
        guard let streamID = message["id"] as? String, let sdp = message["sdp"] as? String else {
            return
        }
        // Galène's web client labels its streams "camera", WHIP streams have no label.
        if let label = message["label"] as? String, !label.isEmpty, label != "camera" {
            Log.galene.info("ignoring stream \(streamID) with label \(label)")
            send(["type": "abort", "id": streamID])
            return
        }
        if let replaced = message["replace"] as? String, !replaced.isEmpty {
            closeDownstream(replaced)
        }

        let downstream: Downstream
        if let existing = downstreams[streamID] {
            downstream = existing
        } else {
            guard let created = Downstream(id: streamID, iceServers: iceServers, session: self) else {
                fail(GaleneError.connectionFailed)
                return
            }
            created.setMuted(isMuted)
            downstreams[streamID] = created
            downstream = created
            offerTimeout?.cancel()
            mediaTimeouts[streamID] = Task { [weak self] in
                try? await Task.sleep(nanoseconds: Self.mediaTimeout)
                guard let self, !Task.isCancelled else { return }
                fail(GaleneError.mediaTimeout)
            }
        }

        do {
            let answer = try await downstream.answer(offer: sdp)
            Log.galene.info("answering offer for stream \(streamID)")
            send(["type": "answer", "id": streamID, "sdp": answer])
        } catch {
            Log.galene.error("unable to answer offer for stream \(streamID): \(error)")
            closeDownstream(streamID)
            send(["type": "abort", "id": streamID])
        }
    }

    private func handleRemoteCandidate(_ message: [String: Any]) {
        guard let streamID = message["id"] as? String,
              let candidate = message["candidate"] as? [String: Any],
              let sdp = candidate["candidate"] as? String, !sdp.isEmpty else {
            return
        }
        let ice = LKRTCIceCandidate(
            sdp: sdp,
            sdpMLineIndex: Int32(candidate["sdpMLineIndex"] as? Int ?? 0),
            sdpMid: candidate["sdpMid"] as? String)
        Log.galene.debug("stream \(streamID) remote candidate \(sdp)")
        downstreams[streamID]?.add(remoteCandidate: ice)
    }

    private func closeDownstream(_ streamID: String) {
        guard let downstream = downstreams.removeValue(forKey: streamID) else { return }
        mediaTimeouts.removeValue(forKey: streamID)?.cancel()
        downstream.close()
        if downstreams.isEmpty {
            onEvent?(.waiting)
        }
    }

    // MARK: - Events from the peer connections

    fileprivate func send(localCandidate candidate: LKRTCIceCandidate, for streamID: String) {
        var candidateInit: [String: Any] = ["candidate": candidate.sdp, "sdpMLineIndex": candidate.sdpMLineIndex]
        if let mid = candidate.sdpMid {
            candidateInit["sdpMid"] = mid
        }
        send(["type": "ice", "id": streamID, "candidate": candidateInit])
    }

    fileprivate func downstream(_ streamID: String, didChange state: LKRTCPeerConnectionState) {
        guard downstreams[streamID] != nil else { return }
        switch state {
        case .connected:
            mediaTimeouts.removeValue(forKey: streamID)?.cancel()
            onEvent?(.receiving)
        case .failed:
            fail(GaleneError.connectionFailed)
        default:
            break
        }
    }

    private static func iceServers(from configuration: Any?) -> [LKRTCIceServer] {
        guard let configuration = configuration as? [String: Any],
              let servers = configuration["iceServers"] as? [[String: Any]] else {
            return []
        }
        return servers.compactMap { server in
            let urls = (server["urls"] as? [String]) ?? (server["urls"] as? String).map { [$0] } ?? []
            guard !urls.isEmpty else { return nil }
            return LKRTCIceServer(urlStrings: urls, username: server["username"] as? String, credential: server["credential"] as? String)
        }
    }
}

/// One stream sent by the server, received on its own peer connection.
private final class Downstream {
    let id: String
    private let connection: LKRTCPeerConnection
    private let observer: PeerConnectionObserver
    private var pendingCandidates: [LKRTCIceCandidate] = []
    private var hasRemoteDescription = false

    init?(id: String, iceServers: [LKRTCIceServer], session: GaleneSession) {
        let configuration = LKRTCConfiguration()
        configuration.sdpSemantics = .unifiedPlan
        configuration.iceServers = iceServers
        configuration.continualGatheringPolicy = .gatherContinually
        let observer = PeerConnectionObserver(streamID: id, session: session)
        guard let connection = WebRTC.factory.peerConnection(
            with: configuration,
            constraints: LKRTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil),
            delegate: observer) else {
            return nil
        }
        self.id = id
        self.connection = connection
        self.observer = observer
    }

    /// Applies the server's offer and returns the SDP of the answer.
    func answer(offer sdp: String) async throws -> String {
        try await connection.setRemoteDescription(LKRTCSessionDescription(type: .offer, sdp: sdp))
        hasRemoteDescription = true
        pendingCandidates.forEach(addNow)
        pendingCandidates.removeAll()
        let answer = try await connection.answer(for: LKRTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
        try await connection.setLocalDescription(answer)
        return answer.sdp
    }

    func add(remoteCandidate candidate: LKRTCIceCandidate) {
        // Candidates can arrive while the offer is still being applied.
        if hasRemoteDescription {
            addNow(candidate)
        } else {
            pendingCandidates.append(candidate)
        }
    }

    private func addNow(_ candidate: LKRTCIceCandidate) {
        connection.add(candidate) { error in
            if let error {
                Log.galene.warning("unable to add remote candidate: \(error)")
            }
        }
    }

    func setMuted(_ muted: Bool) {
        for transceiver in connection.transceivers {
            transceiver.receiver.track?.isEnabled = !muted
        }
    }

    func close() {
        connection.close()
    }

    #if DEBUG
    func logStatistics() {
        connection.statistics { [id] report in
            for stats in report.statistics.values where stats.type == "inbound-rtp" {
                let value = { (key: String) in stats.values[key]?.description ?? "-" }
                Log.galene.debug("stream \(id): \(value("bytesReceived"), privacy: .public) bytes, \(value("totalSamplesReceived"), privacy: .public) samples, level \(value("audioLevel"), privacy: .public)")
            }
        }
    }
    #endif
}

/// Forwards peer connection callbacks, which arrive on WebRTC's signalling thread, to the main actor.
private nonisolated final class PeerConnectionObserver: NSObject, LKRTCPeerConnectionDelegate, @unchecked Sendable {
    private let streamID: String
    private weak var session: GaleneSession?

    init(streamID: String, session: GaleneSession) {
        self.streamID = streamID
        self.session = session
    }

    func peerConnection(_ peerConnection: LKRTCPeerConnection, didGenerate candidate: LKRTCIceCandidate) {
        Log.galene.debug("stream \(self.streamID) local candidate \(candidate.sdp)")
        Task { @MainActor in
            self.session?.send(localCandidate: candidate, for: self.streamID)
        }
    }

    @objc(peerConnection:didChangeConnectionState:)
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didChange newState: LKRTCPeerConnectionState) {
        Log.galene.info("stream \(self.streamID) connection state \(newState.rawValue)")
        Task { @MainActor in
            self.session?.downstream(self.streamID, didChange: newState)
        }
    }

    func peerConnection(_ peerConnection: LKRTCPeerConnection, didAdd rtpReceiver: LKRTCRtpReceiver, streams mediaStreams: [LKRTCMediaStream]) {
        Log.galene.info("stream \(self.streamID) added track \(rtpReceiver.track?.kind ?? "-")")
    }

    func peerConnection(_ peerConnection: LKRTCPeerConnection, didChange stateChanged: LKRTCSignalingState) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didAdd stream: LKRTCMediaStream) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didRemove stream: LKRTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: LKRTCPeerConnection) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didChange newState: LKRTCIceConnectionState) {
        Log.galene.debug("stream \(self.streamID) ICE connection state \(newState.rawValue)")
    }
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didChange newState: LKRTCIceGatheringState) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didRemove candidates: [LKRTCIceCandidate]) {}
    func peerConnection(_ peerConnection: LKRTCPeerConnection, didOpen dataChannel: LKRTCDataChannel) {}
}
