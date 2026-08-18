import FT8Audio
import Foundation
import Network

/// UDP transport for a one-shot SNTP "Sync now" query. The byte build/parse and
/// the offset math live in the pure, unit-tested `Sntp` type in FT8Audio; this
/// service only owns the network I/O (open UDP:123, send the 48-byte request,
/// await one reply, time out). Mirrors the pure-codec / networking split used by
/// `WsjtxCodec` vs `WsjtxUdpService`.
///
/// Runs entirely off the audio/decode loop and resolves via an async
/// continuation, so a slow or dead time server never blocks receive/TX.
enum SntpSyncService {
    /// Default pool server. Apple's NTP pool is reachable on iOS without extra
    /// entitlements and is geographically load-balanced.
    static let defaultServer = "time.apple.com"
    static let defaultTimeout: TimeInterval = 5.0

    enum SyncError: LocalizedError {
        case timeout
        case network(String)
        case invalidResponse

        var errorDescription: String? {
            switch self {
            case .timeout: return "Time server did not respond"
            case .network(let m): return "Network error: \(m)"
            case .invalidResponse: return "Invalid time server response"
            }
        }
    }

    /// Query `server` and return the whole-clock offset (ms) to add to the
    /// device clock so it matches UTC. Never throws; failures come back as
    /// `.failure`. Guaranteed to resume its continuation exactly once.
    static func fetchOffsetMs(
        server: String = defaultServer,
        timeout: TimeInterval = defaultTimeout
    ) async -> Result<Int64, SyncError> {
        await withCheckedContinuation { continuation in
            let conn = NWConnection(
                host: NWEndpoint.Host(server),
                port: NWEndpoint.Port(rawValue: 123)!,
                using: .udp
            )
            let box = ResumeBox()

            func finish(_ result: Result<Int64, SyncError>) {
                guard box.complete() else { return } // resume at most once
                conn.cancel()
                continuation.resume(returning: result)
            }

            // Hard timeout independent of the connection's own state machine.
            let timer = DispatchSource.makeTimerSource(queue: .global())
            timer.schedule(deadline: .now() + timeout)
            timer.setEventHandler { finish(.failure(.timeout)) }
            timer.resume()
            box.timer = timer

            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    let request = Data(Sntp.makeRequest())
                    conn.send(content: request, completion: .contentProcessed { sendErr in
                        if let sendErr {
                            finish(.failure(.network(sendErr.localizedDescription)))
                            return
                        }
                        conn.receiveMessage { data, _, _, recvErr in
                            if let recvErr {
                                finish(.failure(.network(recvErr.localizedDescription)))
                                return
                            }
                            guard let data, data.count >= Sntp.packetSize else {
                                finish(.failure(.invalidResponse))
                                return
                            }
                            // Sample the device clock at reception; local NTP
                            // round-trips are short, so reference - now is a
                            // good offset (matches Android's simple approach).
                            let deviceNowMs = Int64(Date().timeIntervalSince1970 * 1000)
                            guard let refMs = Sntp.transmitTimeUnixMs(
                                fromResponse: [UInt8](data)
                            ) else {
                                finish(.failure(.invalidResponse))
                                return
                            }
                            finish(.success(Sntp.offsetMs(
                                referenceUnixMs: refMs, deviceNowMs: deviceNowMs)))
                        }
                    })
                case .failed(let err):
                    finish(.failure(.network(err.localizedDescription)))
                case .cancelled:
                    break
                default:
                    break
                }
            }
            conn.start(queue: .global())
        }
    }
}

/// Single-shot guard so the SNTP continuation is resumed exactly once across the
/// timeout timer and the connection's async callbacks.
private final class ResumeBox: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    var timer: DispatchSourceTimer?

    func complete() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if done { return false }
        done = true
        timer?.cancel()
        timer = nil
        return true
    }
}
