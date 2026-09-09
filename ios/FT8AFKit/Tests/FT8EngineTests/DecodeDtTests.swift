import XCTest
@testable import FT8Engine

final class DecodeDtTests: XCTestCase {

    func testFormatSignedOneDecimal() {
        XCTAssertEqual(formatDecodeDt(1.3), "+1.3")
        XCTAssertEqual(formatDecodeDt(-1.3), "-1.3")
        XCTAssertEqual(formatDecodeDt(0.2), "+0.2")
        XCTAssertEqual(formatDecodeDt(-0.24), "-0.2")
    }

    func testFormatRoundsToTenths() {
        XCTAssertEqual(formatDecodeDt(1.26), "+1.3")
        XCTAssertEqual(formatDecodeDt(-0.95), "-1.0")
    }

    func testNearZeroRendersUnsigned() {
        // "-0.0" is noise: anything rounding to zero shows plain "0.0".
        XCTAssertEqual(formatDecodeDt(0.0), "0.0")
        XCTAssertEqual(formatDecodeDt(-0.02), "0.0")
        XCTAssertEqual(formatDecodeDt(0.04), "0.0")
    }

    func testNonFiniteRendersDashes() {
        XCTAssertEqual(formatDecodeDt(.nan), "--")
        XCTAssertEqual(formatDecodeDt(.infinity), "--")
    }

    func testNotableThreshold() {
        // Fair threshold is ±1.0 s; strictly greater is notable.
        XCTAssertFalse(isDecodeDtNotable(0.0))
        XCTAssertFalse(isDecodeDtNotable(1.0))
        XCTAssertFalse(isDecodeDtNotable(-1.0))
        XCTAssertTrue(isDecodeDtNotable(1.1))
        XCTAssertTrue(isDecodeDtNotable(-1.4))
        XCTAssertFalse(isDecodeDtNotable(.nan))
        XCTAssertFalse(isDecodeDtNotable(.infinity))
    }
}
