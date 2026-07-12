import Foundation
import Network

/// WSJT-X UDP transport for iOS: owns an `NWConnection`, sends outbound datagrams
/// built by ``WsjtxCodec``, and (when "accept requests" is on) runs a receive
/// loop that turns inbound requests into callbacks the engine wires to the
/// transmit path.
///
/// Cadence (Status ~1 Hz, Heartbeat ~15 s) is driven by the caller (LiveEngine),
/// which already ticks per slot and holds the state a Status needs — so this
/// class stays a thin, non-actor I/O shim. Inbound callbacks are delivered on the
/// main queue, matching the engine's `@MainActor` isolation.
public final class WsjtxUdpService {
    public struct Config: Equatable {
        public var enabled: Bool
        public var host: String
        public var port: UInt16
        public var acceptRequests: Bool
        public init(enabled: Bool = false, host: String = "127.0.0.1",
                    port: UInt16 = 2237, acceptRequests: Bool = false) {
            self.enabled = enabled; self.host = host; self.port = port
            self.acceptRequests = acceptRequests
        }
    }

    private let queue = DispatchQueue(label: "radio.ks3ckc.ft8af.wsjtx")
    private var connection: NWConnection?
    private var config = Config()
    private var replayCache: [WsjtxCodec.Decode] = []
    private let replayCacheCap = 1000

    // Inbound request handlers, invoked on the main queue.
    public var onReply: ((_ call: String, _ grid: String, _ snr: Int32) -> Void)?
    public var onReplay: (() -> Void)?
    public var onHaltTx: ((_ autoOnly: Bool) -> Void)?
    public var onFreeText: ((_ text: String, _ send: Bool) -> Void)?

    public init() {}

    public var isEnabled: Bool { connection != nil }

    /// (Re)configure: tears down any existing connection and reconnects if
    /// enabled. Safe to call repeatedly (Settings changes route through here).
    public func apply(_ cfg: Config) {
        stop()
        config = cfg
        guard cfg.enabled, let port = NWEndpoint.Port(rawValue: cfg.port) else { return }
        let conn = NWConnection(host: NWEndpoint.Host(cfg.host), port: port, using: .udp)
        connection = conn
        conn.start(queue: queue)
        if cfg.acceptRequests { receiveLoop() }
    }

    public func stop() {
        connection?.cancel()
        connection = nil
    }

    // MARK: - outbound

    private func send(_ datagram: Data) {
        connection?.send(content: datagram, completion: .idempotent)
    }

    public func sendClear() { if isEnabled { send(WsjtxCodec.clear()) } }

    public func sendStatus(_ s: WsjtxCodec.Status) { if isEnabled { send(WsjtxCodec.status(s)) } }

    public func sendHeartbeat(version: String) {
        if isEnabled { send(WsjtxCodec.heartbeat(version: version, revision: "")) }
    }

    public func sendDecode(_ d: WsjtxCodec.Decode) {
        guard isEnabled else { return }
        send(WsjtxCodec.decode(d))
        if replayCache.count >= replayCacheCap { replayCache.removeFirst() }
        replayCache.append(d)
    }

    public func sendQsoLogged(_ q: WsjtxCodec.QsoLogged, adif: String) {
        guard isEnabled else { return }
        send(WsjtxCodec.qsoLogged(q))
        send(WsjtxCodec.loggedAdif(adif))
    }

    public func clearReplayCache() { replayCache.removeAll() }

    /// Re-broadcast this session's decodes (marked not-new) for a Replay request.
    private func replay() {
        guard isEnabled else { return }
        for var d in replayCache { d.isNew = false; send(WsjtxCodec.decode(d)) }
    }

    // MARK: - inbound

    private func receiveLoop() {
        connection?.receiveMessage { [weak self] data, _, _, error in
            guard let self = self else { return }
            if let data = data, !data.isEmpty { self.handleInbound(data) }
            // Keep listening until the connection is torn down.
            if error == nil, self.connection != nil { self.receiveLoop() }
        }
    }

    private func handleInbound(_ data: Data) {
        guard let msg = WsjtxCodec.parseInbound(data) else { return }
        switch msg {
        case let .reply(call, grid, snr):
            DispatchQueue.main.async { self.onReply?(call, grid, snr) }
        case let .haltTx(autoOnly):
            DispatchQueue.main.async { self.onHaltTx?(autoOnly) }
        case let .freeText(text, send):
            DispatchQueue.main.async { self.onFreeText?(text, send) }
        case .replay:
            replay()
            DispatchQueue.main.async { self.onReplay?() }
        }
    }
}
