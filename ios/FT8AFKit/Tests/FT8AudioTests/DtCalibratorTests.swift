import XCTest
@testable import FT8Audio

final class DtCalibratorTests: XCTestCase {

    // A consistent bias needs CONFIRM_SLOTS qualifying slots before it lands, so
    // most tests feed the same slot twice. Helper: run one slot and return the
    // (possibly nil) new whole-clock auto offset.
    private func slot(_ cal: inout DtCalibrator, _ dts: [Float], base: Int64 = 0) -> Int64? {
        cal.calibrate(autoOffsetMs: base, decodedDtSec: dts)
    }

    // MARK: - Sample-count / deadband gates

    func testTooFewDecodesDoesNothing() {
        var cal = DtCalibrator()
        let dts: [Float] = [0.5, 0.5, 0.5] // < minDecodes (4)
        XCTAssertNil(slot(&cal, dts))
        XCTAssertNil(slot(&cal, dts)) // still nothing, even repeated
    }

    func testWithinDeadbandDoesNothing() {
        var cal = DtCalibrator()
        // Median DT 40 ms < 60 ms deadband -> no change on either slot.
        let dts: [Float] = [0.04, 0.04, 0.04, 0.04, 0.04]
        XCTAssertNil(slot(&cal, dts, base: 123))
        XCTAssertNil(slot(&cal, dts, base: 123))
    }

    // MARK: - Confirmation streak

    func testSingleQualifyingSlotDoesNotMoveClock() {
        var cal = DtCalibrator()
        // One slot with a real +0.5 s bias: qualifies, but must be CONFIRMED by a
        // second same-sign slot before any correction is applied.
        let dts = [Float](repeating: 0.5, count: 4)
        XCTAssertNil(slot(&cal, dts))
    }

    func testConsistentBiasAppliesAfterConfirmSlots() {
        var cal = DtCalibrator()
        let dts = [Float](repeating: 0.5, count: 4) // +500 ms median
        XCTAssertNil(slot(&cal, dts))               // slot 1: streak building
        // slot 2 confirms: whole-clock offset SUBTRACTS the damped correction
        // (positive DT = fast clock). round(500 * 0.6) = 300, from base 0 -> -300.
        XCTAssertEqual(slot(&cal, dts), -300)
    }

    func testStreakResetsAfterApplyingSoItReconfirms() {
        var cal = DtCalibrator()
        let dts = [Float](repeating: 0.5, count: 4)
        XCTAssertNil(slot(&cal, dts))          // slot 1
        XCTAssertEqual(slot(&cal, dts), -300)  // slot 2 applies, streak resets
        XCTAssertNil(slot(&cal, dts))          // slot 3 must re-confirm
        XCTAssertEqual(slot(&cal, dts), -300)  // slot 4 applies again
    }

    func testSignFlipRestartsTheStreak() {
        var cal = DtCalibrator()
        // One +0.5 s slot, then a -0.5 s slot: the direction flip restarts the
        // streak, so nothing is applied on the flip itself.
        XCTAssertNil(slot(&cal, [Float](repeating: 0.5, count: 4)))
        XCTAssertNil(slot(&cal, [Float](repeating: -0.5, count: 4)))
        // A second negative slot now confirms the negative bias: base 0 + 300.
        XCTAssertEqual(slot(&cal, [Float](repeating: -0.5, count: 4)), 300)
    }

    func testDeadbandSlotBreaksAStreak() {
        var cal = DtCalibrator()
        XCTAssertNil(slot(&cal, [Float](repeating: 0.5, count: 4))) // streak = 1
        // A healthy (in-deadband) slot resets the streak to noise...
        XCTAssertNil(slot(&cal, [Float](repeating: 0.02, count: 4)))
        // ...so the next biased slot is only the first of a fresh streak.
        XCTAssertNil(slot(&cal, [Float](repeating: 0.5, count: 4)))
    }

    // MARK: - MAD outlier rejection

    func testSingleWildOutlierIsRejectedAndClockDoesNotMove() {
        var cal = DtCalibrator()
        // Four tight in-deadband decodes plus one 5 s flyer. MAD rejection drops
        // the flyer; the surviving median is ~0.03 s (in deadband) -> no move,
        // even across two slots.
        let dts: [Float] = [0.03, 0.03, 0.03, 0.03, 5.0]
        XCTAssertNil(slot(&cal, dts))
        XCTAssertNil(slot(&cal, dts))
    }

    func testOutlierRejectionCanStarveTheSampleGate() {
        // 4 raw decodes but one is an outlier -> 3 survivors < minDecodes (4),
        // so the slot produces no correction.
        let survivors = DtCalibrator.rejectOutliers([0.5, 0.5, 0.5, 3.0])
        XCTAssertEqual(survivors.count, 3)
        var cal = DtCalibrator()
        XCTAssertNil(slot(&cal, [0.5, 0.5, 0.5, 3.0]))
    }

    func testOutlierDoesNotDragTheMedian() {
        // Real +0.5 s bias with one -4 s flyer. The flyer is rejected, the
        // median stays 0.5, and two confirming slots apply -300.
        let dts: [Float] = [0.5, 0.5, 0.5, 0.5, 0.5, -4.0]
        var cal = DtCalibrator()
        XCTAssertNil(slot(&cal, dts))
        XCTAssertEqual(slot(&cal, dts), -300)
    }

    func testRejectOutliersKeepsATightCluster() {
        // MAD near 0: the floor (0.2 s) keeps the whole tight cluster rather than
        // rejecting everything but the exact median.
        let kept = DtCalibrator.rejectOutliers([0.30, 0.31, 0.29, 0.30, 0.32])
        XCTAssertEqual(kept.count, 5)
    }

    // MARK: - Clamping

    func testClampsToMaxOffset() {
        var cal = DtCalibrator()
        // Huge negative median (fast-clock direction pushes the auto offset down)
        // from a base near the floor clamps to -maxOffsetMs.
        let dts = [Float](repeating: 9.0, count: 4) // +9000 ms median
        XCTAssertNil(cal.calibrate(autoOffsetMs: -3_900, decodedDtSec: dts))
        let out = cal.calibrate(autoOffsetMs: -3_900, decodedDtSec: dts)
        XCTAssertEqual(out, -DtCalibrator.maxOffsetMs)
    }

    func testClampsToMinOffsetOtherDirection() {
        var cal = DtCalibrator()
        let dts = [Float](repeating: -9.0, count: 4) // -9000 ms median
        XCTAssertNil(cal.calibrate(autoOffsetMs: 3_900, decodedDtSec: dts))
        let out = cal.calibrate(autoOffsetMs: 3_900, decodedDtSec: dts)
        XCTAssertEqual(out, DtCalibrator.maxOffsetMs)
    }

    // MARK: - Pure median helper

    func testMedianUsesUpperMiddleAndIsOrderIndependent() {
        // 5 values; index 5/2 = 2 of the sorted list is the median (0.5).
        XCTAssertEqual(DtCalibrator.median([0.9, 0.1, 0.5, 0.5, 0.5]), 0.5)
        XCTAssertEqual(DtCalibrator.median([]), 0)
    }
}
