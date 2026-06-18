import XCTest
@testable import FT8Audio

final class DtCalibratorTests: XCTestCase {

    func testTooFewDecodesLeavesOffsetUnchanged() {
        let dts: [Float] = [0.5, 0.5, 0.5] // < minDecodes (4)
        XCTAssertEqual(DtCalibrator.calibrate(rxOffsetMs: 0, decodedDtSec: dts), 0)
    }

    func testWithinDeadbandLeavesOffsetUnchanged() {
        // Median DT 40 ms < 60 ms deadband -> no change.
        let dts: [Float] = [0.04, 0.04, 0.04, 0.04, 0.04]
        XCTAssertEqual(DtCalibrator.calibrate(rxOffsetMs: 123, decodedDtSec: dts), 123)
    }

    func testNudgesTowardMedianByAlpha() {
        // Median DT = 0.5 s = 500 ms; nudge = round(500 * 0.6) = 300.
        let dts: [Float] = [0.5, 0.5, 0.5, 0.5]
        XCTAssertEqual(DtCalibrator.calibrate(rxOffsetMs: 0, decodedDtSec: dts), 300)
    }

    func testNegativeMedianNudgesNegative() {
        let dts: [Float] = [-0.2, -0.2, -0.2, -0.2] // -200 ms median
        // round(-200 * 0.6) = -120, from a starting offset of 100 -> -20.
        XCTAssertEqual(DtCalibrator.calibrate(rxOffsetMs: 100, decodedDtSec: dts), -20)
    }

    func testMedianIsOrderIndependentAndUsesUpperMiddle() {
        // 5 values; index 5/2 = 2 of the sorted list is the median (0.5).
        let unsorted: [Float] = [0.9, 0.1, 0.5, 0.5, 0.5]
        XCTAssertEqual(DtCalibrator.calibrate(rxOffsetMs: 0, decodedDtSec: unsorted), 300)
    }

    func testClampsToMaxOffset() {
        // Huge positive median pushes well past the +4000 ms clamp.
        let dts = [Float](repeating: 9.0, count: 4) // 9000 ms median
        let out = DtCalibrator.calibrate(rxOffsetMs: 3_900, decodedDtSec: dts)
        XCTAssertEqual(out, DtCalibrator.maxOffsetMs)
    }

    func testClampsToMinOffset() {
        let dts = [Float](repeating: -9.0, count: 4)
        let out = DtCalibrator.calibrate(rxOffsetMs: -3_900, decodedDtSec: dts)
        XCTAssertEqual(out, -DtCalibrator.maxOffsetMs)
    }
}
