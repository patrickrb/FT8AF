import XCTest
@testable import FT8Engine

final class DisplayFormattersTests: XCTestCase {

    // MARK: - Relative age buckets

    func testAgeNow() {
        XCTAssertEqual(relativeAge(secondsAgo: 0), "now")
        XCTAssertEqual(relativeAge(secondsAgo: 4), "now")
    }

    func testAgeSeconds() {
        XCTAssertEqual(relativeAge(secondsAgo: 5), "5s")
        XCTAssertEqual(relativeAge(secondsAgo: 32), "32s")
        XCTAssertEqual(relativeAge(secondsAgo: 59), "59s")
    }

    func testAgeMinutes() {
        XCTAssertEqual(relativeAge(secondsAgo: 60), "1m")
        XCTAssertEqual(relativeAge(secondsAgo: 5 * 60 + 20), "5m")
        XCTAssertEqual(relativeAge(secondsAgo: 3599), "59m")
    }

    func testAgeHours() {
        XCTAssertEqual(relativeAge(secondsAgo: 3600), "1h")
        XCTAssertEqual(relativeAge(secondsAgo: 2 * 3600 + 100), "2h")
        XCTAssertEqual(relativeAge(secondsAgo: 86399), "23h")
    }

    func testAgeDays() {
        XCTAssertEqual(relativeAge(secondsAgo: 86400), "1d")
        XCTAssertEqual(relativeAge(secondsAgo: 3 * 86400), "3d")
    }

    func testAgeClampsNegative() {
        XCTAssertEqual(relativeAge(secondsAgo: -7), "now")
    }

    // MARK: - Grid distance

    func testGridDistanceKnownPath() {
        // FN31 (Connecticut) → JO31 (western Germany): ~5900 km great circle.
        let km = gridDistanceKm(from: "FN31", to: "JO31")
        XCTAssertNotNil(km)
        XCTAssertEqual(km!, 5900, accuracy: 200)
    }

    func testGridDistanceZeroForSameGrid() {
        let km = gridDistanceKm(from: "FN31", to: "FN31")
        XCTAssertEqual(km!, 0, accuracy: 0.001)
    }

    func testGridDistanceNilForMalformedGrid() {
        XCTAssertNil(gridDistanceKm(from: "FN31", to: ""))
        XCTAssertNil(gridDistanceKm(from: "??", to: "FN31"))
        // RR73 is a sign-off token, not a locator.
        XCTAssertNil(gridDistanceKm(from: "FN31", to: "RR73"))
    }

    // MARK: - Distance formatting & unit conversion

    func testKilometersToMiles() {
        XCTAssertEqual(kilometersToMiles(100), 62.1371, accuracy: 0.001)
    }

    func testFormatDistanceKm() {
        XCTAssertEqual(formatQsoDistance(km: 1832.4, inMiles: false), "1832 km")
        XCTAssertEqual(formatQsoDistance(km: 1832.6, inMiles: false), "1833 km")
    }

    func testFormatDistanceMiles() {
        // 1000 km → 621.371 mi → "621 mi"
        XCTAssertEqual(formatQsoDistance(km: 1000, inMiles: true), "621 mi")
    }

    func testFormatDistanceEmptyForZero() {
        XCTAssertEqual(formatQsoDistance(km: 0, inMiles: false), "")
        XCTAssertEqual(formatQsoDistance(km: -5, inMiles: true), "")
        // Rounds to zero → suppressed rather than "0 mi".
        XCTAssertEqual(formatQsoDistance(km: 0.2, inMiles: true), "")
    }

    func testGridDistanceTextEndToEnd() {
        XCTAssertEqual(gridDistanceText(from: "FN31", to: "", inMiles: false), "")
        let text = gridDistanceText(from: "FN31", to: "JO31", inMiles: false)
        XCTAssertTrue(text.hasSuffix(" km"))
        XCTAssertFalse(text.isEmpty)
    }
}
