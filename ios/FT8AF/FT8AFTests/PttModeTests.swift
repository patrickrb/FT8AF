import XCTest
@testable import FT8AF

final class PttModeTests: XCTestCase {
    func testOnlyVoxIsSelectableOnIOS() {
        XCTAssertEqual(PttMode.selectableOnIOS, [.vox])
    }

    func testNonVoxCasesAreRetained() {
        // Kept for a future Wi-Fi/rigctld bridge even though they're not offered.
        XCTAssertTrue(PttMode.allCases.contains(.cat))
        XCTAssertTrue(PttMode.allCases.contains(.rts))
        XCTAssertTrue(PttMode.allCases.contains(.dtr))
    }
}
