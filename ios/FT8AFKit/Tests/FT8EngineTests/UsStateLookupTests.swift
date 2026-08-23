import XCTest
@testable import FT8Engine

/// Coverage for `UsStateLookup.state(forGrid:)` — maps a Maidenhead grid to a
/// US state code using the bundled `us_grid_states.json` resource. A faithful
/// port of the Android `UsStateLookupTest` cases: the table is keyed by the
/// uppercased first four characters of the grid, and only stable, documented
/// city grids are asserted directly (BL11 -> HI Honolulu, BP51 -> AK Anchorage).
final class UsStateLookupTests: XCTestCase {

    func testNilGridReturnsNil() {
        XCTAssertNil(UsStateLookup.state(forGrid: nil))
    }

    func testEmptyGridReturnsNil() {
        XCTAssertNil(UsStateLookup.state(forGrid: ""))
    }

    func testTooShortGridReturnsNil() {
        // Fewer than four characters cannot key the 4-char map.
        XCTAssertNil(UsStateLookup.state(forGrid: "BL1"))
    }

    func testKnownHawaiiGridReturnsHI() {
        XCTAssertEqual(UsStateLookup.state(forGrid: "BL11"), "HI")
    }

    func testKnownAlaskaGridReturnsAK() {
        XCTAssertEqual(UsStateLookup.state(forGrid: "BP51"), "AK")
    }

    func testKnownTexasGridReturnsTX() {
        XCTAssertEqual(UsStateLookup.state(forGrid: "EM12"), "TX")
    }

    func testKnownConnecticutGridReturnsCT() {
        XCTAssertEqual(UsStateLookup.state(forGrid: "FN31"), "CT")
    }

    func testLowercaseGridIsUppercasedBeforeLookup() {
        XCTAssertEqual(UsStateLookup.state(forGrid: "bl11"), "HI")
    }

    func testUsesOnlyFirstFourCharacters() {
        // A 6-character grid (subsquare appended) keys on its first four chars.
        XCTAssertEqual(UsStateLookup.state(forGrid: "BL11ah"), "HI")
    }

    func testNonUsGridReturnsNil() {
        // JO31 (Germany) is a valid locator but not in the US-only table.
        XCTAssertNil(UsStateLookup.state(forGrid: "JO31"))
    }

    func testUnknownGridReturnsNil() {
        // A syntactically valid grid that is not in the US table.
        XCTAssertNil(UsStateLookup.state(forGrid: "ZZ99"))
    }

    // MARK: - Location line

    func testLocationLinePrefersUsState() {
        // K5ABC is a US call and EM12 -> TX, so the line reads "TX, USA".
        XCTAssertEqual(decodeLocationText(callFrom: "K5ABC", grid: "EM12"), "TX, USA")
    }

    func testLocationLineFallsBackToEntityWhenNoState() {
        // JA1ABC (Japan), non-US grid -> entity name.
        XCTAssertEqual(decodeLocationText(callFrom: "JA1ABC", grid: "PM95"), "Japan")
    }

    func testLocationLineAbbreviatesUsaWithoutState() {
        // US call but no resolvable US grid -> "USA" shorthand.
        XCTAssertEqual(decodeLocationText(callFrom: "W1AW", grid: ""), "USA")
    }

    func testLocationLineNilWhenNothingKnown() {
        XCTAssertNil(decodeLocationText(callFrom: "", grid: ""))
    }

    func testLocationLineNormalizesWhitespaceAndCase() {
        // Untrimmed / lowercase call and grid must still resolve (the decode
        // path can hand us padded tokens) — otherwise the DXCC lookup misses
        // and the line blanks out.
        XCTAssertEqual(decodeLocationText(callFrom: " k5abc ", grid: " EM12 "), "TX, USA")
    }
}
