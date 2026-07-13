import XCTest
@testable import FT8Engine

final class DxccPrefixTests: XCTestCase {

    // MARK: - Core entities from the task brief

    func testUsaPrefixes() {
        XCTAssertEqual(DxccPrefix.entity(for: "W1AW")?.name, "United States")
        XCTAssertEqual(DxccPrefix.entity(for: "K5ABC")?.name, "United States")
        XCTAssertEqual(DxccPrefix.entity(for: "N0CALL")?.name, "United States")
        XCTAssertEqual(DxccPrefix.entity(for: "AA4XX")?.name, "United States")
        XCTAssertEqual(DxccPrefix.continent(for: "W1AW"), "NA")
    }

    func testJapan() {
        XCTAssertEqual(DxccPrefix.entity(for: "JA1ABC")?.name, "Japan")
        XCTAssertEqual(DxccPrefix.entity(for: "JR2XYZ")?.name, "Japan")
        XCTAssertEqual(DxccPrefix.entity(for: "7K1ABC")?.name, "Japan")
        XCTAssertEqual(DxccPrefix.continent(for: "JA1ABC"), "AS")
    }

    func testGermany() {
        XCTAssertEqual(DxccPrefix.entity(for: "DL4RCK")?.name, "Germany")
        XCTAssertEqual(DxccPrefix.entity(for: "DK9IP")?.name, "Germany")
        XCTAssertEqual(DxccPrefix.continent(for: "DL4RCK"), "EU")
    }

    func testEngland() {
        XCTAssertEqual(DxccPrefix.entity(for: "G4ABC")?.name, "England")
        XCTAssertEqual(DxccPrefix.entity(for: "M0XYZ")?.name, "England")
        XCTAssertEqual(DxccPrefix.entity(for: "2E0ABC")?.name, "England")
        XCTAssertEqual(DxccPrefix.continent(for: "G4ABC"), "EU")
    }

    func testUkRegionsBeatEngland() {
        // Longer prefixes (GM/GW/GI) must win over the 1-char England G.
        XCTAssertEqual(DxccPrefix.entity(for: "GM3ABC")?.name, "Scotland")
        XCTAssertEqual(DxccPrefix.entity(for: "GW4XYZ")?.name, "Wales")
        XCTAssertEqual(DxccPrefix.entity(for: "GI0AAA")?.name, "Northern Ireland")
        XCTAssertEqual(DxccPrefix.entity(for: "GD4BEG")?.name, "Isle of Man")
    }

    func testRussia() {
        XCTAssertEqual(DxccPrefix.entity(for: "UA3ABC")?.name, "European Russia")
        XCTAssertEqual(DxccPrefix.entity(for: "R2XYZ")?.name, "European Russia")
        XCTAssertEqual(DxccPrefix.continent(for: "UA3ABC"), "EU")
        // Asiatic call areas resolve via the longer prefix.
        XCTAssertEqual(DxccPrefix.entity(for: "UA9ABC")?.name, "Asiatic Russia")
        XCTAssertEqual(DxccPrefix.continent(for: "R0XYZ"), "AS")
    }

    func testTaskBriefSpotChecks() {
        XCTAssertEqual(DxccPrefix.entity(for: "VK3BDX")?.name, "Australia")
        XCTAssertEqual(DxccPrefix.continent(for: "VK3BDX"), "OC")
        XCTAssertEqual(DxccPrefix.entity(for: "PY2SEX")?.name, "Brazil")
        XCTAssertEqual(DxccPrefix.continent(for: "PY2SEX"), "SA")
        XCTAssertEqual(DxccPrefix.entity(for: "EA4FKR")?.name, "Spain")
        XCTAssertEqual(DxccPrefix.entity(for: "OH2BFO")?.name, "Finland")
        XCTAssertEqual(DxccPrefix.entity(for: "ZL1BYZ")?.name, "New Zealand")
        XCTAssertEqual(DxccPrefix.entity(for: "VE3XYZ")?.name, "Canada")
        XCTAssertEqual(DxccPrefix.continent(for: "ZS6ABC"), "AF")
    }

    // MARK: - Longest-prefix-match mechanics

    func testLongestPrefixWinsOverShorter() {
        // EA8 (Canary Islands, AF) must beat EA (Spain, EU).
        XCTAssertEqual(DxccPrefix.entity(for: "EA8ABC")?.name, "Canary Islands")
        XCTAssertEqual(DxccPrefix.continent(for: "EA8ABC"), "AF")
        // AP (Pakistan) must beat the 1-char USA "A".
        XCTAssertEqual(DxccPrefix.entity(for: "AP2AM")?.name, "Pakistan")
        // KH6 (Hawaii, OC) must beat K (USA, NA).
        XCTAssertEqual(DxccPrefix.entity(for: "KH6XYZ")?.name, "Hawaii")
        XCTAssertEqual(DxccPrefix.continent(for: "KH6XYZ"), "OC")
        // CT3 (Madeira, AF) must beat CT (Portugal, EU).
        XCTAssertEqual(DxccPrefix.entity(for: "CT3ABC")?.name, "Madeira Islands")
    }

    func testLowercaseInput() {
        XCTAssertEqual(DxccPrefix.entity(for: "ja1abc")?.name, "Japan")
        XCTAssertEqual(DxccPrefix.entity(for: "w1aw")?.name, "United States")
    }

    // MARK: - Portable designators

    func testPortablePrefixSegmentWins() {
        // The DXCC-determining segment has the longer matched prefix.
        XCTAssertEqual(DxccPrefix.entity(for: "EA8/W1AW")?.name, "Canary Islands")
        XCTAssertEqual(DxccPrefix.entity(for: "W1AW/KH6")?.name, "Hawaii")
    }

    func testPortableSuffixIgnored() {
        // "/P" matches nothing; "/M" ties at length 1 and loses to the first segment.
        XCTAssertEqual(DxccPrefix.entity(for: "W1AW/P")?.name, "United States")
        XCTAssertEqual(DxccPrefix.entity(for: "W1AW/M")?.name, "United States")
        XCTAssertEqual(DxccPrefix.entity(for: "DL4RCK/QRP")?.name, "Germany")
    }

    // MARK: - Unknowns

    func testUnknownPrefixReturnsNil() {
        XCTAssertNil(DxccPrefix.entity(for: "QQ1ABC"))
        XCTAssertNil(DxccPrefix.entity(for: ""))
        XCTAssertNil(DxccPrefix.continent(for: "QQ1ABC"))
    }
}
