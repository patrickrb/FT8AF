import XCTest
import CFT8

/// Guard against C-struct layout drift from the pinned ft8_lib (6f528128).
/// These sizes are what the C side expects on LP64 (arm64 device + simulator);
/// port of desktop dsp/mod.rs layout_tests. A mismatch means a header changed
/// under the pin and every FFI call is suspect.
final class MemoryLayoutTests: XCTestCase {
    func testStructSizes() {
        XCTAssertEqual(MemoryLayout<candidate_t>.size, 10, "candidate_t")
        XCTAssertEqual(MemoryLayout<ftx_message_t>.size, 12, "ftx_message_t")
        XCTAssertEqual(MemoryLayout<decode_status_t>.size, 8, "decode_status_t")
        // 5*int(20) + pad(4) + ptr(8) + int(4) + enum(4) = 40 with 8-byte ptr align.
        XCTAssertEqual(MemoryLayout<waterfall_t>.size, 40, "waterfall_t")
        XCTAssertEqual(MemoryLayout<monitor_config_t>.size, 24, "monitor_config_t")
    }
}
