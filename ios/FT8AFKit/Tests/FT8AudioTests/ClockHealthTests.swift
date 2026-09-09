import XCTest
@testable import FT8Audio

final class ClockHealthTests: XCTestCase {

    // MARK: - Level thresholds around 0.3 s (good) and 1.0 s (fair)

    func testUnknownWhenNil() {
        XCTAssertEqual(ClockHealth.level(offsetSec: nil), .unknown)
    }

    func testNonFiniteIsUnknown() {
        XCTAssertEqual(ClockHealth.level(offsetSec: .nan), .unknown)
        XCTAssertEqual(ClockHealth.level(offsetSec: .infinity), .unknown)
    }

    func testGoodBoundary() {
        XCTAssertEqual(ClockHealth.level(offsetSec: 0.0), .good)
        XCTAssertEqual(ClockHealth.level(offsetSec: 0.29), .good)
        XCTAssertEqual(ClockHealth.level(offsetSec: 0.3), .good)   // inclusive
        XCTAssertEqual(ClockHealth.level(offsetSec: -0.3), .good)  // magnitude
    }

    func testFairBand() {
        XCTAssertEqual(ClockHealth.level(offsetSec: 0.31), .fair)
        XCTAssertEqual(ClockHealth.level(offsetSec: 1.0), .fair)   // inclusive
        XCTAssertEqual(ClockHealth.level(offsetSec: -0.8), .fair)
    }

    func testPoorAboveFair() {
        XCTAssertEqual(ClockHealth.level(offsetSec: 1.01), .poor)
        XCTAssertEqual(ClockHealth.level(offsetSec: -2.5), .poor)
    }

    // MARK: - Offset label

    func testOffsetLabel() {
        XCTAssertEqual(ClockHealth.offsetLabel(offsetSec: nil), "—")
        XCTAssertEqual(ClockHealth.offsetLabel(offsetSec: .nan), "—")
        XCTAssertEqual(ClockHealth.offsetLabel(offsetSec: 0.1), "+0.1 s")
        XCTAssertEqual(ClockHealth.offsetLabel(offsetSec: -1.3), "-1.3 s")
        XCTAssertEqual(ClockHealth.offsetLabel(offsetSec: 0.0), "0.0 s")
        // Rounds to zero -> no sign (never "-0.0 s").
        XCTAssertEqual(ClockHealth.offsetLabel(offsetSec: -0.02), "0.0 s")
    }

    // MARK: - Status text

    func testStatusText() {
        XCTAssertEqual(ClockHealth.statusText(.good), "In sync")
        XCTAssertEqual(ClockHealth.statusText(.fair), "Resync soon")
        XCTAssertEqual(ClockHealth.statusText(.poor), "Clock off")
        XCTAssertEqual(ClockHealth.statusText(.unknown), "Unknown")
    }
}
