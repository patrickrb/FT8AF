import XCTest
import FT8DSP
@testable import FT8Engine

/// Covers the iOS analog of Android's `GeneralVariables.toModifier`: while a POTA
/// activation is running the CQ transmits as "CQ POTA <call> <grid>" instead of
/// a plain "CQ <call> <grid>".
final class PotaCqModifierTests: XCTestCase {

    // MARK: - Pure builder

    func testPlainCqWhenNoModifier() {
        XCTAssertEqual(
            buildCqMessage(modifier: "", myCall: "W1AW", myGrid: "FN31"),
            "CQ W1AW FN31")
    }

    func testPotaModifierProducesCqPota() {
        XCTAssertEqual(
            buildCqMessage(modifier: "POTA", myCall: "W1AW", myGrid: "FN31"),
            "CQ POTA W1AW FN31")
    }

    func testModifierIsUppercasedAndTrimmed() {
        XCTAssertEqual(
            buildCqMessage(modifier: "  pota ", myCall: "W1AW", myGrid: "FN31"),
            "CQ POTA W1AW FN31")
    }

    func testWhitespaceOnlyModifierDroppedToPlainCq() {
        XCTAssertEqual(
            buildCqMessage(modifier: "   ", myCall: "W1AW", myGrid: "FN31"),
            "CQ W1AW FN31")
    }

    func testOverlongModifierDroppedSoMessageNeverOverflows() {
        // FT8 only packs a 1–4 char alphanumeric CQ modifier; a 5-char modifier
        // is dropped rather than building an unencodable message.
        XCTAssertEqual(
            buildCqMessage(modifier: "PARKS", myCall: "W1AW", myGrid: "FN31"),
            "CQ W1AW FN31")
    }

    func testPunctuationModifierDropped() {
        XCTAssertEqual(
            buildCqMessage(modifier: "P-1", myCall: "W1AW", myGrid: "FN31"),
            "CQ W1AW FN31")
    }

    // MARK: - Engine integration (start CQ + auto-return to CQ)

    func testEngineStartCqUsesModifierWhenActive() {
        let e = QsoEngine(myCall: "W1AW", myGrid: "FN31")
        e.cqModifier = "POTA"
        e.startCq()
        XCTAssertEqual(e.txMessage, "CQ POTA W1AW FN31")
    }

    func testEngineStartCqPlainWhenInactive() {
        let e = QsoEngine(myCall: "W1AW", myGrid: "FN31")
        e.startCq() // cqModifier defaults to ""
        XCTAssertEqual(e.txMessage, "CQ W1AW FN31")
    }

    func testAutoReturnToCqKeepsModifier() {
        // After a completed QSO the engine returns to CQ; the POTA modifier must
        // survive so the run keeps calling "CQ POTA ...".
        let e = QsoEngine(myCall: "K0XYZ", myGrid: "EN37")
        e.cqModifier = "POTA"
        e.answer(DecodedMessage.cq(from: "K1ABC", grid: "FN42", snr: -5))
        _ = e.processRx([DecodedMessage.to("K0XYZ", from: "K1ABC", extra: "-12")])
        let out = e.processRx([DecodedMessage.to("K0XYZ", from: "K1ABC", extra: "RR73")])
        guard case .completed? = out else { return XCTFail("expected completed QSO") }
        e.notifyTransmitted()
        _ = e.processRx([])
        XCTAssertEqual(e.txMessage, "CQ POTA K0XYZ EN37")
    }
}

private extension DecodedMessage {
    static func cq(from: String, grid: String, snr: Int) -> DecodedMessage {
        to("CQ", from: from, extra: grid, grid: grid, snr: snr)
    }

    static func to(
        _ to: String, from: String, extra: String, grid: String = "", snr: Int = -10
    ) -> DecodedMessage {
        var m = DecodedMessage()
        m.callTo = to
        m.callFrom = from
        m.extra = extra
        m.grid = grid
        m.rawText = "\(to) \(from) \(extra)"
        m.snr = snr
        m.freqHz = 1500
        m.timeSec = 0.5
        return m
    }
}
