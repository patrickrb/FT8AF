import XCTest
@testable import FT8Engine

final class DecodeAnnotatorTests: XCTestCase {

    private let myCall = "KD2OGR"

    /// Logbook: W1AW worked on 20M (FN31), DL4RCK on 40M (JO31). K5AAA/K1AAA are
    /// grid-less "cover" QSOs added when NEW ZONE / NEW PREFIX highlighting landed:
    /// they mark the K5/K1 call areas' CQ zones and WPX prefixes as already worked
    /// so the grid/state/worked isolation tests below still exercise the one
    /// category they name, rather than tripping the (higher-priority) new-zone /
    /// new-prefix pills. They add no new worked state or grid (empty locator).
    private var index: LogbookIndex {
        LogbookIndex(qsos: [
            LoggedQso(call: "W1AW", grid: "FN31", band: "20M"),
            LoggedQso(call: "DL4RCK", grid: "JO31", band: "40M"),
            LoggedQso(call: "K5AAA", grid: "", band: "20M"),
            LoggedQso(call: "K1AAA", grid: "", band: "20M"),
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
        // New-DXCC station activating a park → the POTA block wins (Android order),
        // outranking NEW DXCC. A bare "CQ POTA" with no park ref stays plain POTA;
        // when a park ref is present and unhunted it refines to NEW POTA (see
        // testNewPota* — this second case changed from .pota when NEW POTA landed).
        XCTAssertEqual(classify(from: "JA1ABC", to: "CQ POTA", grid: "PM95"), .pota)
        XCTAssertEqual(classify(from: "JA1ABC", to: "CQ", grid: "PM95", extra: "K-1234"), .newPota)
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
        // Every logbook "new" category toggled off → plain CQ. (Expanded from the
        // original DXCC/grid/band/worked set to also cover the new zone/state/
        // prefix/POTA toggles, which now sit in the priority chain.)
        let off = HighlightToggles(
            newPota: false, newDxcc: false, newZone: false, newState: false,
            newGrid: false, newPrefix: false, newBand: false, worked: false
        )
        XCTAssertEqual(classify(from: "JA1ABC", to: "CQ", grid: "PM95", toggles: off), .cq)
    }

    func testNilForUninterestingThirdPartyTraffic() {
        // Mid-QSO with someone else, everything already worked → no pill.
        XCTAssertEqual(classify(from: "W1AW", to: "K5XYZ", grid: "FN31", band: "20M",
                                toggles: HighlightToggles(worked: false)), nil)
    }

    // MARK: - Toggle gating (each category falls through when disabled)

    func testNewDxccToggleFallsThrough() {
        // With NEW DXCC gated off, Japan's (unworked) CQ zone is the next-highest
        // category — NEW ZONE now sits directly below NEW DXCC. (Was .newGrid
        // before NEW ZONE existed.)
        let t = HighlightToggles(newDxcc: false)
        XCTAssertEqual(classify(from: "JA1ABC", to: "CQ", grid: "PM95", toggles: t), .newZone)
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

    func testIndexTracksWorkedZonesAndPrefixes() {
        // W1AW → US zone 5, prefix W1; DL4RCK → Germany zone 14, prefix DL4.
        let idx = LogbookIndex(qsos: [
            LoggedQso(call: "W1AW", grid: "FN31", band: "20M"),
            LoggedQso(call: "DL4RCK", grid: "JO31", band: "40M"),
        ])
        XCTAssertEqual(idx.workedZones, [5, 14])
        XCTAssertEqual(idx.workedPrefixes, ["W1", "DL4"])
    }

    func testIndexTracksWorkedParks() {
        // A park-to-park QSO carries the hunted park in `park` (ADIF SIG_INFO);
        // it lands in workedParks uppercased. A plain QSO adds nothing.
        let idx = LogbookIndex(qsos: [
            LoggedQso(call: "W1AW", grid: "FN31", band: "20M", park: "k-1234"),
            LoggedQso(call: "DL4RCK", grid: "JO31", band: "40M"),
        ])
        XCTAssertEqual(idx.workedParks, ["K-1234"])
    }

    // MARK: - New Zone (Worked All Zones)

    /// A logbook with only zones 5 (W1AW) and 14 (DL4RCK) worked — no K5/K1 cover
    /// QSOs — so a US call outside those zones reads as a genuinely new zone.
    private var wazIndex: LogbookIndex {
        LogbookIndex(qsos: [
            LoggedQso(call: "W1AW", grid: "FN31", band: "20M"),
            LoggedQso(call: "DL4RCK", grid: "JO31", band: "40M"),
        ])
    }

    func testNewZoneBeatsNewStateAndGrid() {
        // W0XYZ: US entity worked, but call area 0 is CQ zone 4 — unworked in an
        // index that only has zones 5/14. NEW ZONE outranks NEW STATE / NEW GRID.
        XCTAssertEqual(classify(from: "W0XYZ", to: "CQ", grid: "EM12", index: wazIndex), .newZone)
    }

    func testNewDxccBeatsNewZone() {
        // VK3ABC (Australia) is an unworked entity → NEW DXCC still wins over its
        // (also unworked) zone.
        XCTAssertEqual(classify(from: "VK3ABC", to: "CQ", grid: "QF22", index: wazIndex), .newDxcc)
    }

    func testNewZoneToggleFallsThrough() {
        // With NEW ZONE gated off, W0XYZ in EM12 (→ TX, unworked) falls to NEW STATE.
        let t = HighlightToggles(newZone: false)
        XCTAssertEqual(classify(from: "W0XYZ", to: "CQ", grid: "EM12", toggles: t, index: wazIndex), .newState)
    }

    func testWorkedZoneIsNotNew() {
        // W1XYZ: US zone 5 already worked (W1AW), grid FN31 + state CT worked,
        // prefix W1 worked → nothing new remains → plain CQ.
        XCTAssertEqual(classify(from: "W1XYZ", to: "CQ", grid: "FN31", index: wazIndex), .cq)
    }

    // MARK: - New Prefix (Worked All Prefixes / WPX)

    func testNewPrefixBelowNewGrid() {
        // K1XYZ against a W1-only logbook: US entity + zone 5 (area 1) worked,
        // grid FN31 + state CT worked, but the WPX prefix "K1" is unworked (only
        // "W1" is) → NEW PREFIX, the last "new" category before NEW BAND.
        XCTAssertEqual(classify(from: "K1XYZ", to: "CQ", grid: "FN31", index: wazIndex), .newPrefix)
    }

    func testNewGridBeatsNewPrefix() {
        // K1XYZ in IO91: zone 5 worked, but IO91 is a new grid → NEW GRID outranks
        // the (also-new) prefix, confirming grid sits above prefix in priority.
        XCTAssertEqual(classify(from: "K1XYZ", to: "CQ", grid: "IO91", index: wazIndex), .newGrid)
    }

    func testNewPrefixToggleFallsThrough() {
        // With NEW PREFIX gated off, K1XYZ (everything else worked) → plain CQ.
        let t = HighlightToggles(newPrefix: false)
        XCTAssertEqual(classify(from: "K1XYZ", to: "CQ", grid: "FN31", toggles: t, index: wazIndex), .cq)
    }

    // MARK: - New POTA (a park not yet hunted)

    func testNewPotaWhenParkUnhunted() {
        // POTA CQ carrying an unhunted park ref → NEW POTA (outranks plain POTA).
        XCTAssertEqual(classify(from: "K5ABC", to: "CQ", grid: "EM12", extra: "K-1234", index: wazIndex), .newPota)
        XCTAssertEqual(classify(from: "K5ABC", to: "CQ POTA K-1234", grid: "EM12", index: wazIndex), .newPota)
    }

    func testWorkedParkStaysPlainPota() {
        // The same park already hunted (in workedParks) → plain POTA, not NEW POTA.
        let idx = LogbookIndex(qsos: [LoggedQso(call: "W1AW", grid: "FN31", band: "20M", park: "K-1234")])
        XCTAssertEqual(classify(from: "K5ABC", to: "CQ", grid: "EM12", extra: "K-1234", index: idx), .pota)
    }

    func testPotaWithoutParkRefIsPlainPota() {
        // A bare "CQ POTA" with no extractable park ref stays plain POTA.
        XCTAssertEqual(classify(from: "K5ABC", to: "CQ POTA", grid: "EM12", index: wazIndex), .pota)
    }

    func testNewPotaToggleFallsThroughToPota() {
        // NEW POTA gated off → still POTA (the POTA row is always shown).
        let t = HighlightToggles(newPota: false)
        XCTAssertEqual(classify(from: "K5ABC", to: "CQ", grid: "EM12", extra: "K-1234", toggles: t, index: wazIndex), .pota)
    }

    // MARK: - WPX prefix helper

    func testWpxPrefixSimpleCalls() {
        XCTAssertEqual(wpxPrefix("W1AW"), "W1")
        XCTAssertEqual(wpxPrefix("VE3XYZ"), "VE3")
        XCTAssertEqual(wpxPrefix("DL1ABC"), "DL1")
        XCTAssertEqual(wpxPrefix("2E0ABC"), "2E0")
        XCTAssertEqual(wpxPrefix("9A1AA"), "9A1")
        XCTAssertEqual(wpxPrefix("3DA0RS"), "3DA0")
        XCTAssertEqual(wpxPrefix("raem"), "RA0")   // no numeral → first two letters + 0
    }

    func testWpxPrefixPortableCalls() {
        XCTAssertEqual(wpxPrefix("W1AW/4"), "W4")       // portable number
        XCTAssertEqual(wpxPrefix("VE3ABC/7"), "VE7")
        XCTAssertEqual(wpxPrefix("9A1AA/7"), "9A7")
        XCTAssertEqual(wpxPrefix("DL/W1AW"), "DL0")     // portable prefix, no numeral
        XCTAssertEqual(wpxPrefix("PJ4/K1ABC"), "PJ4")
        XCTAssertEqual(wpxPrefix("W1AW/KH6"), "KH6")
        XCTAssertEqual(wpxPrefix("G4XYZ/P"), "G4")      // operational suffix ignored
    }

    func testWpxPrefixRejectsNonCalls() {
        XCTAssertNil(wpxPrefix(""))
        XCTAssertNil(wpxPrefix("FN42"))    // grid, not a call (digit-terminated)
        XCTAssertNil(wpxPrefix("73"))      // all-digit token
        XCTAssertNil(wpxPrefix("W1"))      // bare prefix, no letter suffix
        XCTAssertNil(wpxPrefix("W1/7"))    // partial token, not a whole call
    }

    // MARK: - POTA park-ref extraction

    func testPotaParkRefExtraction() {
        XCTAssertEqual(potaParkRef(callTo: "CQ", extra: "K-1234"), "K-1234")
        XCTAssertEqual(potaParkRef(callTo: "CQ POTA K-1234", extra: ""), "K-1234")
        XCTAssertNil(potaParkRef(callTo: "CQ POTA", extra: ""))    // no ref
        XCTAssertNil(potaParkRef(callTo: "CQ", extra: "FN31"))     // not a park ref, not POTA
        XCTAssertNil(potaParkRef(callTo: "W1AW", extra: "K-1234")) // not a CQ
    }
}
