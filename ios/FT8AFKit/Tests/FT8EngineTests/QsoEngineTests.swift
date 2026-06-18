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

        // DX sends RR73 -> we send 73 and log.
        let out2 = e.processRx([msg("K0XYZ", "K1ABC", "RR73", "", -8)])
        guard case .completed(let rec)? = out2 else { return XCTFail("expected completed QSO") }
        XCTAssertEqual(rec.call, "K1ABC")
        XCTAssertEqual(rec.gridsquare, "FN42")
        XCTAssertEqual(rec.rstSent, "-08")
        XCTAssertEqual(rec.rstRcvd, "-12")
        XCTAssertEqual(rec.mode, "FT8")
        XCTAssertEqual(rec.stationCallsign, "K0XYZ")
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
}
