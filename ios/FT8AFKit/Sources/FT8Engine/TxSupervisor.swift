import Foundation

/// Pure counting/decision logic for the two TX safety nets (port of Android's
/// `launchSupervision` watchdog + `noReplyLimit` stop-after-N in
/// `FT8TransmitSignal`/`GeneralVariables`):
///
/// - **Watchdog**: auto-stop any repeating TX (CQ or QSO retries) after N
///   minutes of continuous transmission cycles. The clock starts when TX is
///   armed and is refreshed on events that prove the operator/sequencer is
///   making progress (new CQ run, new target, completed QSO with auto-CQ,
///   hunt idle-listening) — a *stuck* QSO deliberately keeps aging it.
/// - **Stop-after-N**: stop calling after N consecutive transmissions of the
///   *same* message with no reply in between. A stage advance (message text
///   changes) or a reply from the target resets the streak.
///
/// The type holds no timers and does no I/O: the caller feeds it events with
/// explicit `nowMs` timestamps and asks `stopReason` before each TX cycle, so
/// every rule is unit-testable.
public struct TxSupervisor: Equatable, Sendable {
    public enum StopReason: Equatable, Sendable {
        /// Continuous TX exceeded the watchdog timeout.
        case watchdog
        /// The same message went unanswered `stopAfterAttempts` times.
        case noReply
    }

    /// Epoch ms when the watchdog clock last (re)started.
    public private(set) var watchdogStartMs: Int64
    /// Consecutive transmissions of `lastMessage` without an intervening reply.
    public private(set) var noReplyCount: Int = 0
    /// The message text whose no-reply streak is being counted.
    public private(set) var lastMessage: String?

    public init(nowMs: Int64) {
        watchdogStartMs = nowMs
    }

    /// Restart the watchdog clock (TX armed, target changed, QSO completed
    /// with auto-CQ, hunt idle-listening slot, …).
    public mutating func resetWatchdog(nowMs: Int64) {
        watchdogStartMs = nowMs
    }

    /// A reply from the current target arrived — the run is alive, so the
    /// no-reply streak resets (the watchdog is deliberately *not* refreshed
    /// here; see the type doc).
    public mutating func noteReply() {
        noReplyCount = 0
    }

    /// Record one transmission of `message`. Repeating the same text grows
    /// the no-reply streak; any change of message (stage advance, new target)
    /// starts a fresh streak of 1.
    public mutating func noteTransmission(_ message: String) {
        if message == lastMessage {
            noReplyCount += 1
        } else {
            lastMessage = message
            noReplyCount = 1
        }
    }

    /// Clear the no-reply streak entirely (new QSO/target/CQ run).
    public mutating func resetAttempts() {
        noReplyCount = 0
        lastMessage = nil
    }

    /// Whether TX must stop *before* the next transmission cycle.
    ///
    /// - Parameters:
    ///   - nowMs: current epoch ms
    ///   - watchdogMin: watchdog timeout in minutes, 0 = off
    ///   - stopAfterAttempts: max consecutive no-reply sends of one message, 0 = off
    public func stopReason(nowMs: Int64, watchdogMin: Int, stopAfterAttempts: Int) -> StopReason? {
        if watchdogMin > 0, nowMs - watchdogStartMs > Int64(watchdogMin) * 60_000 {
            return .watchdog
        }
        if stopAfterAttempts > 0, noReplyCount >= stopAfterAttempts {
            return .noReply
        }
        return nil
    }
}
