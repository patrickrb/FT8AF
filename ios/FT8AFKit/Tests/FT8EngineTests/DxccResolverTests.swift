import XCTest
@testable import FT8Engine

/// Exercises the cty.dat-backed resolver: entity + CQ-zone resolution, per-token
/// overrides, exact `=CALL` wins, longest-prefix-wins, and portable handling.
/// Complements `DxccPrefixTests` (which pins the public entity-name surface).
final class DxccResolverTests: XCTestCase {

    // MARK: - Entity + CQ zone via the bundled dataset

    func testCommonCallsEntityAndCqZone() {
        assertEntity("W1AW", name: "United States", cq: 5, continent: "NA")
        assertEntity("G0XYZ", name: "England", cq: 14, continent: "EU")
        assertEntity("JA1XYZ", name: "Japan", cq: 25, continent: "AS")
        assertEntity("DL1ABC", name: "Germany", cq: 14, continent: "EU")
        assertEntity("VK3BDX", name: "Australia", cq: 30, continent: "OC")
        assertEntity("PY2ABC", name: "Brazil", cq: 11, continent: "SA")
        // UA (European Russia) header CQ zone is 16.
        assertEntity("UA3ABC", name: "European Russia", cq: 16, continent: "EU")
    }

    func testItuZoneResolved() {
        XCTAssertEqual(DxccPrefix.entity(for: "W1AW")?.ituZone, 8)
        XCTAssertEqual(DxccPrefix.entity(for: "G0XYZ")?.ituZone, 27)
    }

    // MARK: - Exact `=CALL` override wins over the prefix match

    func testExactCallOverride() {
        // =NH7RO/M is listed under United States even though NH7 is normally
        // Hawaii — the exact full-call entry must win.
        let hawaii = DxccPrefix.entity(for: "NH7XYZ") // plain NH7 prefix
        XCTAssertEqual(hawaii?.name, "Hawaii")
        let exact = DxccPrefix.entity(for: "NH7RO/M")
        XCTAssertEqual(exact?.name, "United States")
    }

    // MARK: - Per-prefix CQ-zone override

    func testCqZoneOverridePrefix() {
        // US call area 0 carries a (4)[7] override: CQ 4, ITU 7 — not the
        // country default (5/8). Android drops this; we apply it.
        let w0 = DxccPrefix.entity(for: "W0ABC")
        XCTAssertEqual(w0?.name, "United States")
        XCTAssertEqual(w0?.cqZone, 4)
        XCTAssertEqual(w0?.ituZone, 7)
        // Asiatic Russia carries per-district CQ overrides; R0A… is (18)[32]
        // (a more-specific token than the R0 → 19 default), which longest-prefix
        // match correctly prefers — either way a non-default zone via override.
        let r0 = DxccPrefix.entity(for: "R0ABC")
        XCTAssertEqual(r0?.name, "Asiatic Russia")
        XCTAssertEqual(r0?.cqZone, 18)
        XCTAssertEqual(r0?.ituZone, 32)
        XCTAssertEqual(r0?.continent, "AS")
    }

    // MARK: - Longest-prefix-wins across a split entity

    func testLongestPrefixSplit() {
        // KH6 (Hawaii, zone 31, OC) is a longer prefix than K (US) and a
        // different zone/continent; the exact =KH6DM entry flips back to US.
        let hi = DxccPrefix.entity(for: "KH6XYZ")
        XCTAssertEqual(hi?.name, "Hawaii")
        XCTAssertEqual(hi?.cqZone, 31)
        XCTAssertEqual(hi?.continent, "OC")

        let usExact = DxccPrefix.entity(for: "KH6DM")
        XCTAssertEqual(usExact?.name, "United States")
        XCTAssertEqual(usExact?.cqZone, 4)
    }

    // MARK: - Portable handling

    func testPortableResolvesToOperatingPrefix() {
        XCTAssertEqual(DxccPrefix.entity(for: "EA8/W1AW")?.name, "Canary Islands")
        XCTAssertEqual(DxccPrefix.entity(for: "K1ABC/VE3")?.name, "Canada")
        XCTAssertEqual(DxccPrefix.entity(for: "VE3/K1ABC")?.name, "Canada")
        // Bare mobile/portable suffixes are ignored.
        XCTAssertEqual(DxccPrefix.entity(for: "W1AW/P")?.name, "United States")
        XCTAssertEqual(DxccPrefix.entity(for: "DL1ABC/QRP")?.name, "Germany")
    }

    // MARK: - Parser unit coverage (synthetic cty.dat)

    func testParserAppliesOverrides() {
        let cat = """
        Testland:                 05:  08:  NA:   37.53:   -91.67:     5.0:  T:
            T,TA0(4)[7]{SA}<10.0/-20.0>,=TX1ZZ(9),
            =TX2YY;
        """
        let resolver = DxccResolver(catText: cat)

        // Bare prefix → country defaults.
        let t = resolver.entity(for: "T5ABC")
        XCTAssertEqual(t?.name, "Testland")
        XCTAssertEqual(t?.cqZone, 5)
        XCTAssertEqual(t?.ituZone, 8)
        XCTAssertEqual(t?.continent, "NA")
        // Header lon is positive-west 91.67 → exposed east-positive.
        XCTAssertEqual(t?.longitude ?? 0, 91.67, accuracy: 0.001)

        // Overridden prefix: CQ 4, ITU 7, continent SA, coords <10/-20>.
        let ta0 = resolver.entity(for: "TA0BC")
        XCTAssertEqual(ta0?.cqZone, 4)
        XCTAssertEqual(ta0?.ituZone, 7)
        XCTAssertEqual(ta0?.continent, "SA")
        XCTAssertEqual(ta0?.latitude ?? 0, 10.0, accuracy: 0.001)
        XCTAssertEqual(ta0?.longitude ?? 0, 20.0, accuracy: 0.001)

        // Exact call with CQ override wins over the bare-T prefix.
        let tx1 = resolver.entity(for: "TX1ZZ")
        XCTAssertEqual(tx1?.name, "Testland")
        XCTAssertEqual(tx1?.cqZone, 9)
        // Exact call spanning a continuation line.
        XCTAssertEqual(resolver.entity(for: "TX2YY")?.name, "Testland")
        // A call that only matches the bare T prefix, not the exact list.
        XCTAssertEqual(resolver.entity(for: "TX9AA")?.cqZone, 5)
    }

    func testEmptyResolverReturnsNil() {
        let resolver = DxccResolver(catText: "")
        XCTAssertNil(resolver.entity(for: "W1AW"))
    }

    // MARK: - Helpers

    private func assertEntity(
        _ call: String, name: String, cq: Int, continent: String,
        file: StaticString = #filePath, line: UInt = #line
    ) {
        let e = DxccPrefix.entity(for: call)
        XCTAssertEqual(e?.name, name, "\(call) name", file: file, line: line)
        XCTAssertEqual(e?.cqZone, cq, "\(call) cqZone", file: file, line: line)
        XCTAssertEqual(e?.continent, continent, "\(call) continent", file: file, line: line)
    }
}
