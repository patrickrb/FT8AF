import XCTest
@testable import FT8Engine

final class BeamHeadingTests: XCTestCase {

    // MARK: - Pure great-circle bearing (cardinal sanity)

    func testCardinalBearings() {
        XCTAssertEqual(greatCircleBearing(lat1: 0, lon1: 0, lat2: 10, lon2: 0), 0, accuracy: 1e-6)     // north
        XCTAssertEqual(greatCircleBearing(lat1: 0, lon1: 0, lat2: 0, lon2: 10), 90, accuracy: 1e-6)    // east
        XCTAssertEqual(greatCircleBearing(lat1: 0, lon1: 0, lat2: -10, lon2: 0), 180, accuracy: 1e-6)  // south
        XCTAssertEqual(greatCircleBearing(lat1: 0, lon1: 0, lat2: 0, lon2: -10), 270, accuracy: 1e-6)  // west
    }

    func testDueNorthIsZeroNot360() {
        // The final modulo must fold a 360 result back to 0.
        XCTAssertEqual(greatCircleBearing(lat1: 10, lon1: 5, lat2: 20, lon2: 5), 0, accuracy: 1e-6)
    }

    func testLongPathIsReciprocal() {
        XCTAssertEqual(longPathBearing(50), 230, accuracy: 1e-9)
        XCTAssertEqual(longPathBearing(230), 50, accuracy: 1e-9)
    }

    func testNormalizeWrapsAndRounds() {
        XCTAssertEqual(normalizeHeadingDeg(359.6), 0)
        XCTAssertEqual(normalizeHeadingDeg(360.0), 0)
        XCTAssertEqual(normalizeHeadingDeg(-1.0), 359)
        XCTAssertEqual(normalizeHeadingDeg(46.7), 47)
    }

    // MARK: - Grid-pair headings

    func testFn31ToJo31IsNortheast() {
        // FN31 (Newington CT, ~41.5N/73W) → JO31 (western Germany, ~51.5N/7E):
        // a short-path initial bearing of ~50°.
        let deg = beamHeadingShortPathDeg(fromGrid: "FN31", toGrid: "JO31")
        XCTAssertNotNil(deg)
        XCTAssertEqual(Double(deg ?? -999), 50, accuracy: 3)
        XCTAssertEqual(beamHeadingText(fromGrid: "FN31", toGrid: "JO31"), "\(deg!)°")
    }

    func testMissingOrEqualGridsReturnNil() {
        XCTAssertNil(beamHeadingShortPathDeg(fromGrid: "", toGrid: "JO31"))
        XCTAssertNil(beamHeadingShortPathDeg(fromGrid: "FN31", toGrid: ""))
        XCTAssertNil(beamHeadingShortPathDeg(fromGrid: "FN31", toGrid: "FN31"))  // bearing to self
        XCTAssertNil(beamHeadingShortPathDeg(fromGrid: "FN31", toGrid: "RR73"))  // sign-off token
        XCTAssertEqual(beamHeadingText(fromGrid: "FN31", toGrid: ""), "")
    }
}
