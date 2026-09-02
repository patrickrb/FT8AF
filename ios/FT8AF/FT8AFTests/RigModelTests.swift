import XCTest
@testable import FT8AF

final class RigModelTests: XCTestCase {
    func testFt891IsPresentInAllCases() {
        XCTAssertTrue(RigModel.allCases.contains(.ft891))
    }

    func testFt891RawValue() {
        XCTAssertEqual(RigModel.ft891.rawValue, "FT-891")
    }

    func testFt891RoundTripsByRawValue() {
        // Persistence is by rawValue; the label must decode back to the case.
        XCTAssertEqual(RigModel(rawValue: "FT-891"), .ft891)
    }
}
