import Foundation
import Network

/// WSJT-X UDP transport for iOS: owns an outbound `NWConnection` for broadcasts,
/// an `NWListener` (when "accept requests" is on) bound to the configured port
/// for inbound requests, and turns those requests into callbacks the engine
/// wires to the transmit path.
///
/// Cadence (Status ~1 Hz, Heartbeat ~15 s) is driven by the caller (LiveEngine),
/// which already ticks per slot and holds the state a Status needs — so this
/// class stays a thin I/O shim. Inbound callbacks are delivered on the main
/// queue, matching the engine's `@MainActor` isolation.
///
/// Threading: all `connection`/`listener`/`replayCache`/`config` access is
/// confined to `queue` (a serial queue), so sends and the replay cache can't
/// race across the send callers and the listener's receive callbacks. A separate
/// lock-guarded flag backs the synchronous `isEnabled`.
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
    // All of the following are touched only on `queue`.
    private var connection: NWConnection?
    private var listener: NWListener?
    private var config = Config()
    private var replayCache: [WsjtxCodec.Decode] = []
    private let replayCacheCap = 1000

    // isEnabled is read synchronously from the (main-actor) engine, so it is backed
    // by a lock-guarded flag set eagerly in apply()/stop() rather than by reading the
    // queue-confined `connection`.
    private let stateLock = NSLock()
    private var enabledFlag = false

    // Inbound request handlers, invoked on the main queue.
    public var onReply: ((_ call: String, _ grid: String, _ snr: Int32, _ deltaFreq: UInt32) -> Void)?
    public var onReplay: (() -> Void)?
    public var onHaltTx: ((_ autoOnly: Bool) -> Void)?
    public var onFreeText: ((_ text: String, _ send: Bool) -> Void)?

    public init() {}

    public var isEnabled: Bool {
        stateLock.lock(); defer { stateLock.unlock() }
        return enabledFlag
    }

    private func setEnabled(_ v: Bool) {
        stateLock.lock(); enabledFlag = v; stateLock.unlock()
    }

    /// (Re)configure: tears down any existing connection/listener and reconnects
    /// if enabled. Safe to call repeatedly (Settings changes route through here).
    public func apply(_ cfg: Config) {
        // Set the synchronous flag eagerly so an isEnabled check right after apply()
        // reflects intent, then do the socket work on the serial queue.
        let willEnable = cfg.enabled && NWEndpoint.Port(rawValue: cfg.port) != nil
        setEnabled(willEnable)
        queue.async { [weak self] in
            guard let self else { return }
            self.teardownLocked()
            self.config = cfg
            guard willEnable, let port = NWEndpoint.Port(rawValue: cfg.port) else { return }

            // Outbound connection for Status/Decode/Heartbeat/Clear broadcasts.
            let conn = NWConnection(host: NWEndpoint.Host(cfg.host), port: port, using: .udp)
            self.connection = conn
            conn.start(queue: self.queue)

            // Inbound requests need a listener BOUND to the configured port — companion
            // apps send Reply/Halt/FreeText/Replay there. The outbound connection's
            // ephemeral local port is not discoverable, so it can never receive them.
            if cfg.acceptRequests {
                self.startListenerLocked(port: port)
            }
        }
    }

    public func stop() {
        setEnabled(false)
        queue.async { [weak self] in self?.teardownLocked() }
    }

    /// Must run on `queue`.
    private func teardownLocked() {
        connection?.cancel(); connection = nil
        listener?.cancel(); listener = nil
    }

    // MARK: - outbound

    /// Build (on `queue`, since the codec is pure) and send a datagram. No-op when
    /// there is no connection.
    private func enqueueSend(_ build: @escaping () -> Data) {
        queue.async { [weak self] in
            guard let self, let conn = self.connection else { return }
            conn.send(content: build(), completion: .idempotent)
        }
    }

    public func sendClear() { enqueueSend { WsjtxCodec.clear() } }

    public func sendStatus(_ s: WsjtxCodec.Status) { enqueueSend { WsjtxCodec.status(s) } }

    public func sendHeartbeat(version: String) {
        enqueueSend { WsjtxCodec.heartbeat(version: version, revision: "") }
    }

    public func sendDecode(_ d: WsjtxCodec.Decode) {
        queue.async { [weak self] in
            guard let self, let conn = self.connection else { return }
            conn.send(content: WsjtxCodec.decode(d), completion: .idempotent)
            if self.replayCache.count >= self.replayCacheCap { self.replayCache.removeFirst() }
            self.replayCache.append(d)
        }
    }

    public func sendQsoLogged(_ q: WsjtxCodec.QsoLogged, adif: String) {
        enqueueSend { WsjtxCodec.qsoLogged(q) }
        enqueueSend { WsjtxCodec.loggedAdif(adif) }
    }

    public func clearReplayCache() {
        queue.async { [weak self] in self?.replayCache.removeAll() }
    }

    /// Re-broadcast this session's decodes (marked not-new) for a Replay request.
    /// Must run on `queue`.
    private func replay() {
        guard let conn = connection else { return }
        for var d in replayCache {
            d.isNew = false
            conn.send(content: WsjtxCodec.decode(d), completion: .idempotent)
        }
    }

    // MARK: - inbound

    /// Must run on `queue`.
    private func startListenerLocked(port: NWEndpoint.Port) {
        do {
            let params = NWParameters.udp
            params.allowLocalEndpointReuse = true
            let l = try NWListener(using: params, on: port)
            l.newConnectionHandler = { [weak self] conn in
                guard let self else { conn.cancel(); return }
                conn.start(queue: self.queue)
                self.receive(on: conn)
            }
            l.start(queue: queue)
            listener = l
        } catch {
            // Port unavailable: inbound won't work, but outbound broadcasts still do.
            listener = nil
        }
    }

    /// Receive callbacks fire on `queue` (the connection was started on it), so
    /// `handleInbound` — including `replay()` — runs on `queue`.
    private func receive(on conn: NWConnection) {
        conn.receiveMessage { [weak self] data, _, _, error in
            guard let self else { return }
            if let data, !data.isEmpty { self.handleInbound(data) }
            if error == nil { self.receive(on: conn) }
        }
    }

    /// Must run on `queue`.
    private func handleInbound(_ data: Data) {
        guard let msg = WsjtxCodec.parseInbound(data) else { return }
        switch msg {
        case let .reply(call, grid, snr, deltaFreq):
            DispatchQueue.main.async { self.onReply?(call, grid, snr, deltaFreq) }
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
