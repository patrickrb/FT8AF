import XCTest
@testable import FT8DSP

/// Callsign-hash goldens — FT8Hash.n22/h12/h10 must match the frozen WSJT-X
/// hashes (test_golden_encode.c test_hash_golden).
final class HashGoldenTests: XCTestCase {
    func testCallsignHashesMatchGolden() {
        for (k, call) in Golden.calls.enumerated() {
            let g = Golden.hashes[k]
            XCTAssertEqual(FT8Hash.n22(call), g.h22, "[\(call)] h22")
            XCTAssertEqual(FT8Hash.h12(call), g.h12, "[\(call)] h12")
            XCTAssertEqual(FT8Hash.h10(call), g.h10, "[\(call)] h10")
        }
    }

    /// Lowercase input must hash identically (the algorithm upcases a-z).
    func testHashIsCaseInsensitive() {
        XCTAssertEqual(FT8Hash.n22("k1abc"), FT8Hash.n22("K1ABC"))
    }
}
