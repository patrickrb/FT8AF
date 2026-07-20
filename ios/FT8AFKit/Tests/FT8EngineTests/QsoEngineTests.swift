import XCTest
import FT8DSP
@testable import FT8Engine

final class QsoEngineTests: XCTestCase {

    /// Build a decoded-message fixture (port of qso.rs tests' `msg` helper).
    private func msg(_ to: String, _ from: String, _ extra: String, _ grid: String, _ snr: Int) -> DecodedMessage {
        var m = DecodedMessage()
        m.callTo = to
        m.callFrom = from
        m.extra = extra
        m.grid = grid
        m.rawText = "\(to) \(from) \(extra)"
        m.snr = snr
        m.freqHz = 1500
        m.timeSec = 0.5
        m.score = 30
        m.i3 = 1
        m.n3 = 0
        return m
    }

    // MARK: ports of the desktop qso.rs unit tests

    func testAnswererFullSequenceLogsQso() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        // Operator answers DX K1ABC who CQ'd from FN42.
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -5))
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ EN37")
        XCTAssertEqual(e.status().stage, .grid)

        // DX reports our signal -12.
        let out1 = e.processRx([msg("K0XYZ", "K1ABC", "-12", "", -8)])
        XCTAssertNil(out1)
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ R-08")
        XCTAssertEqual(e.status().reportRcvd, -12)

        // DX sends RR73 -> we log AND queue the courtesy 73 to transmit.
        let out2 = e.processRx([msg("K0XYZ", "K1ABC", "RR73", "", -8)])
        guard case .completed(let rec)? = out2 else { return XCTFail("expected completed QSO") }
        XCTAssertEqual(rec.call, "K1ABC")
        XCTAssertEqual(rec.gridsquare, "FN42")
        XCTAssertEqual(rec.rstSent, "-08")
        XCTAssertEqual(rec.rstRcvd, "-12")
        XCTAssertEqual(rec.mode, "FT8")
        XCTAssertEqual(rec.stationCallsign, "K0XYZ")
        // The final 73 is queued (not clobbered by the auto-return)...
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ 73")
        XCTAssertEqual(e.status().stage, .bye73)
        // ...the scheduler actually transmits it (signalled here)...
        e.notifyTransmitted()
        // ...and once its TX slot has passed, the next slot returns to CQ.
        XCTAssertNil(e.processRx([]))
        XCTAssertEqual(e.txMessage, "CQ K0XYZ EN37")
    }

    func testRr73WithDecoderGridStillCompletesQso() {
        // Regression for the sign-off-vs-grid classification bug (ported from
        // desktop qso.rs `rr73_with_decoder_grid_still_completes_qso`). Live, the
        // DX's RR73 arrives with grid == "RR73" because looksLikeGrid("RR73") is
        // true and the decoder copies it into the grid field. If classify checked
        // grid first it returned Content.grid, the RReport arm hit `default:
        // break`, and the QSO hung retransmitting our R-report forever instead of
        // logging.
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -5))
        _ = e.processRx([msg("K0XYZ", "K1ABC", "-12", "", -8)])
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ R-08")
        XCTAssertEqual(e.status().stage, .rReport)

        // DX signs off with RR73 exactly as the decoder emits it live (grid set).
        let out = e.processRx([msg("K0XYZ", "K1ABC", "RR73", "RR73", -8)])
        guard case .completed(let rec)? = out else {
            return XCTFail("RR73 sign-off must complete the QSO")
        }
        XCTAssertEqual(rec.call, "K1ABC")
        XCTAssertEqual(rec.gridsquare, "FN42")
        XCTAssertEqual(rec.rstSent, "-08")
        XCTAssertEqual(rec.rstRcvd, "-12")
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ 73")
        XCTAssertEqual(e.status().stage, .bye73)

        // A genuine 4-char grid reply is still classified as a grid, not a
        // sign-off — the fix reorders the checks without weakening grid handling.
        // As the CQ initiator, an answerer's grid must advance us to send a report.
        let e2 = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e2.startCq()
        _ = e2.processRx([msg("K0XYZ", "K1ABC", "FN31", "FN31", -10)])
        XCTAssertEqual(e2.txMessage, "K1ABC K0XYZ -10")
        XCTAssertEqual(e2.status().stage, .report)
    }

    func testBye73NotFinishedUntilActuallyTransmitted() {
        // Regression: processRx must not wrap up on stage == .bye73 alone. If the
        // courtesy 73 couldn't be transmitted this slot (late TX window, rig busy,
        // encode failure) the QSO must stay armed and retransmit it, not clobber it
        // by returning to CQ. Ported from desktop qso.rs
        // `bye73_not_finished_until_actually_transmitted`.
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -5))
        _ = e.processRx([msg("K0XYZ", "K1ABC", "-12", "", -8)])
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ R-08")

        // DX sends RR73 -> QSO logs and the courtesy 73 is queued.
        let out = e.processRx([msg("K0XYZ", "K1ABC", "RR73", "", -8)])
        guard case .completed? = out else { return XCTFail("expected completed QSO") }
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ 73")
        XCTAssertEqual(e.status().stage, .bye73)

        // The 73 did NOT go out this slot (no notifyTransmitted). The next slot
        // must keep the 73 queued rather than returning to CQ, and must not log the
        // QSO a second time.
        XCTAssertNil(e.processRx([]))
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ 73")
        XCTAssertEqual(e.status().stage, .bye73)
        // Still pending after another silent slot.
        XCTAssertNil(e.processRx([]))
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ 73")
        XCTAssertEqual(e.status().stage, .bye73)

        // Once the scheduler reports the 73 actually transmitted, the next slot
        // wraps the QSO up and returns to CQ.
        e.notifyTransmitted()
        XCTAssertNil(e.processRx([]))
        XCTAssertEqual(e.txMessage, "CQ K0XYZ EN37")
        XCTAssertEqual(e.status().stage, .cq)
    }

    func testNotifyTransmittedIsNoOpOffBye73() {
        // notifyTransmitted only latches the courtesy 73; it must not disturb an
        // in-progress sequence (e.g. after answering, stage == .grid). Ported from
        // desktop qso.rs `notify_transmitted_is_noop_off_bye73`.
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -5))
        XCTAssertEqual(e.status().stage, .grid)
        e.notifyTransmitted()
        // Still awaiting the DX report; nothing changed.
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ EN37")
        XCTAssertEqual(e.status().stage, .grid)
        // The DX report still advances us normally.
        _ = e.processRx([msg("K0XYZ", "K1ABC", "-12", "", -8)])
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ R-08")
        XCTAssertEqual(e.status().stage, .rReport)
    }

    func testInitiatorCqSequenceLogsQso() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.startCq()
        XCTAssertEqual(e.txMessage, "CQ K0XYZ EN37")

        // K1ABC answers our CQ with their grid.
        _ = e.processRx([msg("K0XYZ", "K1ABC", "FN42", "FN42", -10)])
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ -10")
        XCTAssertEqual(e.status().stage, .report)

        // K1ABC sends R-report.
        _ = e.processRx([msg("K0XYZ", "K1ABC", "R-15", "", -10)])
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ RR73")
        XCTAssertEqual(e.status().reportRcvd, -15)

        // K1ABC sends 73 -> complete, log, back to CQ.
        let out = e.processRx([msg("K0XYZ", "K1ABC", "73", "", -10)])
        guard case .completed(let rec)? = out else { return XCTFail("expected completed QSO") }
        XCTAssertEqual(rec.call, "K1ABC")
        XCTAssertEqual(rec.rstSent, "-10")
        XCTAssertEqual(rec.rstRcvd, "-15")
        // auto-returned to CQ
        XCTAssertEqual(e.txMessage, "CQ K0XYZ EN37")
    }

    func testIgnoresMessagesForOthers() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.startCq()
        // traffic between two other stations
        XCTAssertNil(e.processRx([msg("W1AW", "K1ABC", "FN42", "FN42", -3)]))
        XCTAssertEqual(e.txMessage, "CQ K0XYZ EN37")
    }

    // MARK: additional coverage for new code paths

    func testIdleEngineIgnoresDecodes() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        XCTAssertNil(e.processRx([msg("K0XYZ", "K1ABC", "FN42", "FN42", -3)]))
        XCTAssertNil(e.txMessage)
    }

    func testSetStageRequiresTarget() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.setStage(.report) // no station selected yet
        XCTAssertNil(e.txMessage)
        XCTAssertFalse(e.status().active)
    }

    func testSetStageCqAndIdleFallThrough() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.setStage(.cq)
        XCTAssertEqual(e.txMessage, "CQ K0XYZ EN37")
        XCTAssertTrue(e.status().active)
        e.setStage(.idle)
        XCTAssertNil(e.txMessage)
        XCTAssertFalse(e.status().active)
        XCTAssertEqual(e.status().stage, .idle)
    }

    func testSetStageManualPickAfterAnswer() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -7))
        e.setStage(.rr73)
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ RR73")
        XCTAssertEqual(e.status().stage, .rr73)
        e.setStage(.bye73)
        XCTAssertEqual(e.txMessage, "K1ABC K0XYZ 73")
        XCTAssertEqual(e.status().stage, .bye73)
    }

    func testSetFreeTextUppercasesAndActivates() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.setFreeText("test msg 123")
        XCTAssertEqual(e.txMessage, "TEST MSG 123")
        XCTAssertTrue(e.status().active)
    }

    func testReportClampedAndFormatted() {
        // Very weak signal: report clamps to -30 and formats as "-30".
        let weak = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        weak.startCq()
        _ = weak.processRx([msg("K0XYZ", "K1ABC", "FN42", "FN42", -99)])
        XCTAssertEqual(weak.txMessage, "K1ABC K0XYZ -30")

        // Very strong signal: clamps to +30, formats as "+30".
        let strong = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        strong.startCq()
        _ = strong.processRx([msg("K0XYZ", "K1ABC", "FN42", "FN42", 99)])
        XCTAssertEqual(strong.txMessage, "K1ABC K0XYZ +30")
    }

    func testNoReplyThisSlotKeepsTxMessage() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.startCq()
        // A reply not addressed to us doesn't advance the sequence.
        XCTAssertNil(e.processRx([msg("W1AW", "N0CALL", "RR73", "", -3)]))
        XCTAssertEqual(e.txMessage, "CQ K0XYZ EN37")
        XCTAssertEqual(e.status().stage, .cq)
    }

    func testStopClearsState() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.startCq()
        e.stop()
        XCTAssertFalse(e.status().active)
        XCTAssertNil(e.txMessage)
        XCTAssertEqual(e.status().stage, .idle)
    }

    func testGridAndCallUppercasedOnInit() {
        let e = QsoEngine(myCall: "k0xyz", myGrid: "en37mt") // grid truncated to 4 + upper
        e.startCq()
        XCTAssertEqual(e.txMessage, "CQ K0XYZ EN37")
    }

    func testStopClearsTargetSoStaleStageIsNoOp() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -5)) // sets target K1ABC
        e.stop()
        e.setStage(.report) // target was cleared by stop -> no-op, no stale TX
        XCTAssertNil(e.txMessage)
        XCTAssertFalse(e.status().active)
        XCTAssertNil(e.status().target)
    }

    func testAnsweredGridNormalizedToFourCharUpper() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.answer(msg("CQ", "K1ABC", "fn42lk", "fn42lk", -5)) // 6-char lowercase locator
        _ = e.processRx([msg("K0XYZ", "K1ABC", "-12", "", -8)])
        let out = e.processRx([msg("K0XYZ", "K1ABC", "RR73", "", -8)])
        guard case .completed(let rec)? = out else { return XCTFail("expected completed QSO") }
        XCTAssertEqual(rec.gridsquare, "FN42")
    }

    func testInitiatorCapturedGridNormalized() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.startCq()
        _ = e.processRx([msg("K0XYZ", "K1ABC", "fn42", "fn42", -10)]) // lowercase grid
        _ = e.processRx([msg("K0XYZ", "K1ABC", "R-15", "", -10)])
        let out = e.processRx([msg("K0XYZ", "K1ABC", "73", "", -10)])
        guard case .completed(let rec)? = out else { return XCTFail("expected completed QSO") }
        XCTAssertEqual(rec.gridsquare, "FN42")
    }

    func testFT8TimeFormatsUtc() {
        XCTAssertEqual(FT8Time.yyyymmdd(fromMs: 0), "19700101")
        XCTAssertEqual(FT8Time.hhmmss(fromMs: 0), "000000")
        // 1_700_000_000 s == 2023-11-14T22:13:20Z
        XCTAssertEqual(FT8Time.yyyymmdd(fromMs: 1_700_000_000_000), "20231114")
        XCTAssertEqual(FT8Time.hhmmss(fromMs: 1_700_000_000_000), "221320")
    }

    // MARK: forceLog / abandonToCq (LOG and ✕ panel actions)

    func testForceLogAfterReportsExchangedLogsAndReturnsToCq() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -5))
        _ = e.processRx([msg("K0XYZ", "K1ABC", "-12", "", -8)]) // we sent R-08 → .rReport
        XCTAssertEqual(e.status().stage, .rReport)

        guard case .completed(let rec)? = e.forceLog() else {
            return XCTFail("expected a logged record after reports were exchanged")
        }
        XCTAssertEqual(rec.call, "K1ABC")
        XCTAssertEqual(rec.rstSent, "-08")
        XCTAssertEqual(rec.rstRcvd, "-12")
        // autoReturnToCq (default true) → back at the CQ baseline.
        XCTAssertEqual(e.status().stage, .cq)
        XCTAssertEqual(e.txMessage, "CQ K0XYZ EN37")
        XCTAssertNil(e.status().target)
    }

    func testForceLogTooEarlyDoesNotLogButStillMovesOn() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -5)) // stage .grid, no reports yet
        XCTAssertNil(e.forceLog())
        XCTAssertNil(e.status().target)
        XCTAssertEqual(e.status().stage, .cq)
    }

    func testForceLogWithNoTargetIsNil() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.startCq()
        XCTAssertNil(e.forceLog())
        // Untouched: still calling CQ.
        XCTAssertEqual(e.txMessage, "CQ K0XYZ EN37")
    }

    func testForceLogStopsWhenAutoReturnOff() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.autoReturnToCq = false
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -5))
        _ = e.processRx([msg("K0XYZ", "K1ABC", "-12", "", -8)])
        XCTAssertNotNil(e.forceLog())
        XCTAssertFalse(e.status().active)
        XCTAssertNil(e.txMessage)
        XCTAssertEqual(e.status().stage, .idle)
    }

    func testForceLogDuringCourtesy73WindowDoesNotDoubleLog() {
        // RR73-received flow: complete() logs the QSO and queues the courtesy
        // 73 (stage .bye73, target still set). A LOG tap in that window must
        // NOT emit a second identical record.
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -5))
        _ = e.processRx([msg("K0XYZ", "K1ABC", "-12", "", -8)]) // → .rReport
        guard case .completed? = e.processRx([msg("K0XYZ", "K1ABC", "RR73", "", -8)]) else {
            return XCTFail("expected the RR73 to complete and log the QSO")
        }
        XCTAssertEqual(e.status().stage, .bye73)
        XCTAssertEqual(e.status().target, "K1ABC")

        // LOG during the courtesy-73 window: no second record, just move on.
        XCTAssertNil(e.forceLog())
        XCTAssertNil(e.status().target)
        XCTAssertEqual(e.status().stage, .cq)
    }

    func testForceLogMidQsoStillEmitsExactlyOneRecord() {
        // Genuine mid-QSO force-log (reports exchanged, NOT completed):
        // exactly one record, and a repeat LOG produces nothing further.
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -5))
        _ = e.processRx([msg("K0XYZ", "K1ABC", "-12", "", -8)]) // → .rReport
        guard case .completed(let rec)? = e.forceLog() else {
            return XCTFail("expected a logged record from a mid-QSO force-log")
        }
        XCTAssertEqual(rec.call, "K1ABC")
        XCTAssertNil(e.forceLog()) // target cleared — nothing more to log
    }

    func testForceLogAfterManualBye73StillLogsOnce() {
        // Operator manually picked Tx5 (73) before the sequencer completed:
        // nothing has been logged yet, so LOG must still emit the record.
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -5))
        _ = e.processRx([msg("K0XYZ", "K1ABC", "-12", "", -8)]) // → .rReport
        e.setStage(.bye73)
        guard case .completed(let rec)? = e.forceLog() else {
            return XCTFail("expected a record — this QSO was never logged")
        }
        XCTAssertEqual(rec.call, "K1ABC")
    }

    func testNextQsoAfterCourtesy73LogsNormally() {
        // The record-emitted latch must reset with the QSO context: a full
        // second QSO with a new station logs its own record.
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -5))
        _ = e.processRx([msg("K0XYZ", "K1ABC", "-12", "", -8)])
        _ = e.processRx([msg("K0XYZ", "K1ABC", "RR73", "", -8)]) // logged, .bye73
        e.notifyTransmitted()                                    // courtesy 73 actually keyed
        _ = e.processRx([]) // courtesy 73 slot passed → back to CQ

        _ = e.processRx([msg("K0XYZ", "W9DEF", "EN52", "EN52", -3)])
        _ = e.processRx([msg("K0XYZ", "W9DEF", "R-06", "", -3)])
        let out = e.processRx([msg("K0XYZ", "W9DEF", "73", "", -3)])
        guard case .completed(let rec)? = out else {
            return XCTFail("expected the second QSO to complete and log")
        }
        XCTAssertEqual(rec.call, "W9DEF")
    }

    func testAbandonToCqDropsTargetWithoutLogging() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.answer(msg("CQ", "K1ABC", "FN42", "FN42", -5))
        _ = e.processRx([msg("K0XYZ", "K1ABC", "-12", "", -8)])
        e.abandonToCq()
        XCTAssertNil(e.status().target)
        XCTAssertEqual(e.status().stage, .cq)
        XCTAssertEqual(e.txMessage, "CQ K0XYZ EN37")
    }

    func testAutoReturnToCqOffStopsAfterCompletion() {
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.autoReturnToCq = false
        e.startCq()
        _ = e.processRx([msg("K0XYZ", "K1ABC", "FN42", "FN42", -10)])
        _ = e.processRx([msg("K0XYZ", "K1ABC", "R-15", "", -10)])
        guard case .completed? = e.processRx([msg("K0XYZ", "K1ABC", "73", "", -10)]) else {
            return XCTFail("expected completed QSO")
        }
        XCTAssertFalse(e.status().active)
        XCTAssertNil(e.txMessage)
    }
}
