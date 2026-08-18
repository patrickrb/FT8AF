import XCTest
@testable import FT8Engine

final class DecodeAnnotatorTests: XCTestCase {

    private let myCall = "KD2OGR"

    /// Logbook: W1AW worked on 20M (FN31), DL4RCK on 40M (JO31).
    private var index: LogbookIndex {
        LogbookIndex(qsos: [
            LoggedQso(call: "W1AW", grid: "FN31", band: "20M"),
            LoggedQso(call: "DL4RCK", grid: "JO31", band: "40M"),
        ])
    }

    private func classify(
        from: String, to: String, grid: String = "", extra: String = "",
        band: String = "20M",
        toggles: HighlightToggles = HighlightToggles(),
        index: LogbookIndex? = nil
    ) -> DecodeHighlight? {
        classifyDecode(
            callFrom: from, callTo: to, grid: grid, extra: extra,
            myCall: myCall, band: band, index: index ?? self.index, toggles: toggles
        )
    }

    // MARK: - Message-shape helpers

    func testIsCQMessage() {
        XCTAssertTrue(isCQMessage(callTo: "CQ"))
        XCTAssertTrue(isCQMessage(callTo: "CQ POTA"))
        XCTAssertTrue(isCQMessage(callTo: "CQ DX"))
        XCTAssertFalse(isCQMessage(callTo: "CQX1AB"))
        XCTAssertFalse(isCQMessage(callTo: "W1AW"))
        XCTAssertFalse(isCQMessage(callTo: ""))
    }

    func testIsDirectedToMe() {
        XCTAssertTrue(isDirectedToMe(callTo: "kd2ogr", myCall: myCall))
        XCTAssertFalse(isDirectedToMe(callTo: "W1AW", myCall: myCall))
        XCTAssertFalse(isDirectedToMe(callTo: "KD2OGR", myCall: ""))
    }

    func testParkRefShape() {
        XCTAssertTrue(looksLikeParkRef("K-1234"))
        XCTAssertTrue(looksLikeParkRef("VE-5082"))
        XCTAssertTrue(looksLikeParkRef("DL-03000"))
        XCTAssertFalse(looksLikeParkRef("RR73"))
        XCTAssertFalse(looksLikeParkRef("K-12"))       // number too short
        XCTAssertFalse(looksLikeParkRef("KABCD-1234")) // program too long
        XCTAssertFalse(looksLikeParkRef("K-12A4"))     // non-digit number
        XCTAssertFalse(looksLikeParkRef(""))
    }

    func testIsPotaCq() {
        XCTAssertTrue(isPotaCq(callTo: "CQ POTA", extra: ""))
        XCTAssertTrue(isPotaCq(callTo: "CQ", extra: "K-1234"))
        XCTAssertFalse(isPotaCq(callTo: "CQ", extra: "FN31"))
        XCTAssertFalse(isPotaCq(callTo: "W1AW", extra: "K-1234")) // not a CQ
    }

    // MARK: - Priority order

    func testPendingBeatsEverything() {
        // Directed at me, from a station that is also new DXCC → PENDING wins.
        XCTAssertEqual(classify(from: "JA1ABC", to: "KD2OGR", grid: "PM95"), .pending)
    }

    func testPotaBeatsNewDxcc() {
        // New-DXCC station activating a park → POTA wins (Android order).
        XCTAssertEqual(classify(from: "JA1ABC", to: "CQ POTA", grid: "PM95"), .pota)
        XCTAssertEqual(classify(from: "JA1ABC", to: "CQ", grid: "PM95", extra: "K-1234"), .pota)
    }

    func testNewDxccBeatsNewGrid() {
        // Japan not in logbook, grid also new → NEW DXCC wins.
        XCTAssertEqual(classify(from: "JA1ABC", to: "CQ", grid: "PM95"), .newDxcc)
    }

    func testNewGridWhenEntityWorked() {
        // USA entity is worked (W1AW). IO91 is a new grid that resolves to no US
        // state (non-US locator), so NEW GRID is the highest category left.
        // (EM12 would now win as NEW STATE — see testNewStateBeatsNewGrid.)
        XCTAssertEqual(classify(from: "K5ABC", to: "CQ", grid: "IO91"), .newGrid)
    }

    func testNewBandWhenGridAndEntityWorked() {
        // DL4RCK worked on 40M only; on 20M with his already-worked grid → NEW BAND.
        XCTAssertEqual(classify(from: "DL4RCK", to: "CQ", grid: "JO31", band: "20M"), .newBand)
    }

    func testWorkedOnThisBand() {
        // W1AW worked on 20M, known grid → WORKED.
        XCTAssertEqual(classify(from: "W1AW", to: "CQ", grid: "FN31", band: "20M"), .worked)
    }

    func testPlainCqFallback() {
        // Entity/grid toggles off → plain CQ.
        let off = HighlightToggles(newDxcc: false, newGrid: false, newBand: false, worked: false)
        XCTAssertEqual(classify(from: "JA1ABC", to: "CQ", grid: "PM95", toggles: off), .cq)
    }

    func testNilForUninterestingThirdPartyTraffic() {
        // Mid-QSO with someone else, everything already worked → no pill.
        XCTAssertEqual(classify(from: "W1AW", to: "K5XYZ", grid: "FN31", band: "20M",
                                toggles: HighlightToggles(worked: false)), nil)
    }

    // MARK: - Toggle gating (each category falls through when disabled)

    func testNewDxccToggleFallsThrough() {
        let t = HighlightToggles(newDxcc: false)
        XCTAssertEqual(classify(from: "JA1ABC", to: "CQ", grid: "PM95", toggles: t), .newGrid)
    }

    func testNewGridToggleFallsThrough() {
        let t = HighlightToggles(newGrid: false)
        // K5ABC: entity worked, non-US grid IO91 new but gated off, never
        // worked → CQ. (IO91 resolves to no state, so NEW STATE stays out of it.)
        XCTAssertEqual(classify(from: "K5ABC", to: "CQ", grid: "IO91", toggles: t), .cq)
    }

    func testNewBandToggleFallsThrough() {
        let t = HighlightToggles(newBand: false)
        XCTAssertEqual(classify(from: "DL4RCK", to: "CQ", grid: "JO31", band: "20M", toggles: t), .worked)
    }

    func testWorkedToggleFallsThrough() {
        let t = HighlightToggles(worked: false)
        XCTAssertEqual(classify(from: "W1AW", to: "CQ", grid: "FN31", band: "20M", toggles: t), .cq)
    }

    // MARK: - New State (Worked All States)

    func testNewStateBeatsNewGrid() {
        // K5ABC: US entity worked; EM12 -> TX is an unworked state (index only
        // has CT). NEW STATE outranks NEW GRID, matching Android.
        XCTAssertEqual(classify(from: "K5ABC", to: "CQ", grid: "EM12"), .newState)
    }

    func testNewDxccBeatsNewState() {
        // VK3ABC (Australia) is an unworked entity even though EM12 -> TX is an
        // unworked state → NEW DXCC still wins (higher priority).
        XCTAssertEqual(classify(from: "VK3ABC", to: "CQ", grid: "EM12"), .newDxcc)
    }

    func testNewStateToggleFallsThrough() {
        // With NEW STATE gated off, EM12 is still an unworked grid → NEW GRID.
        let t = HighlightToggles(newState: false)
        XCTAssertEqual(classify(from: "K5ABC", to: "CQ", grid: "EM12", toggles: t), .newGrid)
    }

    func testWorkedStateIsNotNew() {
        // K1XYZ (unworked US call) in FN31 -> CT, which is already worked; the
        // grid FN31 is also worked, so nothing "new" remains → plain CQ.
        XCTAssertEqual(classify(from: "K1XYZ", to: "CQ", grid: "FN31"), .cq)
    }

    func testIndexTracksWorkedStates() {
        // W1AW FN31 -> CT is a worked state; DL4RCK JO31 is non-US (no state).
        XCTAssertEqual(index.workedStates, ["CT"])
    }

    // MARK: - Grid validity

    func testRr73IsNotANewGrid() {
        // "RR73" parses like a locator but is a sign-off; must not classify as NEW GRID.
        XCTAssertEqual(classify(from: "K5ABC", to: "CQ", grid: "RR73",
                                toggles: HighlightToggles(worked: false)), .cq)
    }

    func testShortGridIsNotANewGrid() {
        XCTAssertEqual(classify(from: "K5ABC", to: "CQ", grid: "FN",
                                toggles: HighlightToggles(worked: false)), .cq)
    }

    func testSixCharGridMatchesWorkedSquare() {
        // FN31pr truncates to FN31, which is worked → not a new grid.
        XCTAssertEqual(classify(from: "K5ABC", to: "CQ", grid: "FN31pr",
                                toggles: HighlightToggles(worked: false)), .cq)
    }

    // MARK: - LogbookIndex construction

    func testIndexNormalizesCase() {
        let idx = LogbookIndex(qsos: [LoggedQso(call: "w1aw", grid: "fn31pr", band: "20m")])
        XCTAssertTrue(idx.workedCalls.contains("W1AW"))
        XCTAssertTrue(idx.workedGrids.contains("FN31"))
        XCTAssertEqual(idx.bandsByCall["W1AW"], ["20M"])
        XCTAssertTrue(idx.workedEntities.contains("United States"))
    }

    func testIndexSkipsEmptyRecords() {
        let idx = LogbookIndex(qsos: [LoggedQso(call: "", grid: "", band: "")])
        XCTAssertTrue(idx.workedCalls.isEmpty)
        XCTAssertTrue(idx.workedGrids.isEmpty)
        XCTAssertTrue(idx.workedEntities.isEmpty)
    }
}
