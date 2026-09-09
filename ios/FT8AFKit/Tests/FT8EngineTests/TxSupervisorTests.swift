import XCTest
@testable import FT8Engine

final class TxSupervisorTests: XCTestCase {

    // MARK: watchdog

    func testWatchdogOffNeverStops() {
        var s = TxSupervisor(nowMs: 0)
        s.noteTransmission("CQ K1ABC FN42")
        XCTAssertNil(s.stopReason(nowMs: 100 * 60_000, watchdogMin: 0, stopAfterAttempts: 0))
    }

    func testWatchdogStopsAfterTimeout() {
        let s = TxSupervisor(nowMs: 0)
        // 5 min watchdog: exactly at the boundary keeps going, past it stops.
        XCTAssertNil(s.stopReason(nowMs: 5 * 60_000, watchdogMin: 5, stopAfterAttempts: 0))
        XCTAssertEqual(
            s.stopReason(nowMs: 5 * 60_000 + 1, watchdogMin: 5, stopAfterAttempts: 0),
            .watchdog
        )
    }

    func testWatchdogResetRestartsClock() {
        var s = TxSupervisor(nowMs: 0)
        s.resetWatchdog(nowMs: 4 * 60_000)
        // 5 min after the reset, not the construction.
        XCTAssertNil(s.stopReason(nowMs: 8 * 60_000, watchdogMin: 5, stopAfterAttempts: 0))
        XCTAssertEqual(
            s.stopReason(nowMs: 10 * 60_000, watchdogMin: 5, stopAfterAttempts: 0),
            .watchdog
        )
    }

    // MARK: stop-after-N

    func testStopAfterNSameMessageNoReply() {
        var s = TxSupervisor(nowMs: 0)
        let msg = "CQ K1ABC FN42"
        s.noteTransmission(msg)
        s.noteTransmission(msg)
        XCTAssertNil(s.stopReason(nowMs: 0, watchdogMin: 0, stopAfterAttempts: 3))
        s.noteTransmission(msg)
        XCTAssertEqual(
            s.stopReason(nowMs: 0, watchdogMin: 0, stopAfterAttempts: 3),
            .noReply
        )
    }

    func testStopAfterOffNeverStops() {
        var s = TxSupervisor(nowMs: 0)
        for _ in 0..<50 { s.noteTransmission("CQ K1ABC FN42") }
        XCTAssertNil(s.stopReason(nowMs: 0, watchdogMin: 0, stopAfterAttempts: 0))
    }

    func testMessageChangeResetsStreak() {
        var s = TxSupervisor(nowMs: 0)
        s.noteTransmission("CQ K1ABC FN42")
        s.noteTransmission("CQ K1ABC FN42")
        // Stage advanced — new message starts a fresh streak of 1.
        s.noteTransmission("W9XYZ K1ABC -10")
        XCTAssertEqual(s.noReplyCount, 1)
        XCTAssertNil(s.stopReason(nowMs: 0, watchdogMin: 0, stopAfterAttempts: 2))
    }

    func testReplyResetsStreakButNotWatchdog() {
        var s = TxSupervisor(nowMs: 0)
        s.noteTransmission("W9XYZ K1ABC -10")
        s.noteTransmission("W9XYZ K1ABC -10")
        s.noteReply()
        XCTAssertEqual(s.noReplyCount, 0)
        XCTAssertNil(s.stopReason(nowMs: 0, watchdogMin: 0, stopAfterAttempts: 2))
        // Same message repeated after the reply counts from 1 again.
        s.noteTransmission("W9XYZ K1ABC -10")
        XCTAssertEqual(s.noReplyCount, 1)
        // The watchdog clock is NOT refreshed by a reply — a stuck QSO ages it.
        XCTAssertEqual(
            s.stopReason(nowMs: 10 * 60_000, watchdogMin: 5, stopAfterAttempts: 0),
            .watchdog
        )
    }

    func testResetAttemptsClearsMessageMemory() {
        var s = TxSupervisor(nowMs: 0)
        s.noteTransmission("CQ K1ABC FN42")
        s.noteTransmission("CQ K1ABC FN42")
        s.resetAttempts()
        XCTAssertEqual(s.noReplyCount, 0)
        s.noteTransmission("CQ K1ABC FN42")
        XCTAssertEqual(s.noReplyCount, 1)
    }

    func testWatchdogTakesPrecedenceOverNoReply() {
        var s = TxSupervisor(nowMs: 0)
        for _ in 0..<5 { s.noteTransmission("CQ K1ABC FN42") }
        XCTAssertEqual(
            s.stopReason(nowMs: 60 * 60_000, watchdogMin: 5, stopAfterAttempts: 3),
            .watchdog
        )
    }
}
