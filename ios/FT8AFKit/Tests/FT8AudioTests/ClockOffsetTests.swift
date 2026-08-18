import XCTest
@testable import FT8Audio

final class ClockOffsetTests: XCTestCase {

    // MARK: - Combine math

    func testCombinedIsNtpPlusManual() {
        let off = ClockOffset(ntpOffsetMs: 200, manualOffsetMs: -50)
        XCTAssertEqual(off.combinedMs, 150)
    }

    func testDefaultsAreZero() {
        let off = ClockOffset()
        XCTAssertEqual(off.combinedMs, 0)
        XCTAssertEqual(off.apply(toUtcMs: 12_345), 12_345)
    }

    func testApplyShiftsWallClock() {
        let off = ClockOffset(ntpOffsetMs: 300, manualOffsetMs: 500)
        XCTAssertEqual(off.apply(toUtcMs: 1_000_000), 1_000_800)
    }

    // MARK: - The whole point: the offset must move BOTH RX slot detection and
    // TX cycle position by the same amount (Android's whole-clock model), while
    // the DtCalibrator rxOffsetMs stays an independent RX-only term.

    func testOffsetShiftsRxSlotAndTxCycleConsistently() {
        // Pick a wall time deep inside a slot so a +0.5 s manual nudge stays in
        // the same slot but visibly advances the cycle position.
        let wallMs: Int64 = 15_000 * 100 + 5_000 // 5.000 s into slot 100
        let manual: Int64 = 500                   // +0.5 s whole-clock correction
        let off = ClockOffset(manualOffsetMs: manual)
        let rxOffset: Int64 = 120                  // independent capture-latency comp

        let baseNow = wallMs
        let shiftedNow = off.apply(toUtcMs: wallMs)

        // RX slot detection sees the shifted clock AND the rxOffset; the cycle
        // position it implies advances by exactly the manual offset.
        let baseRxSlot = SlotClock.rxSlotID(atUtcMs: baseNow, rxOffsetMs: rxOffset)
        let shiftedRxSlot = SlotClock.rxSlotID(atUtcMs: shiftedNow, rxOffsetMs: rxOffset)
        XCTAssertEqual(baseRxSlot, shiftedRxSlot) // same slot for a small nudge

        // TX timing reads msIntoCycle off the shifted clock (no rxOffset): the
        // cycle position moves by exactly the manual offset.
        let baseTxCycle = SlotClock.msIntoCycle(atUtcMs: baseNow)
        let shiftedTxCycle = SlotClock.msIntoCycle(atUtcMs: shiftedNow)
        XCTAssertEqual(shiftedTxCycle - baseTxCycle, manual)
    }

    func testOffsetCanPushAcrossASlotBoundaryForBothReads() {
        // 200 ms before a slot boundary; a +0.5 s correction crosses into the
        // next slot for both the RX and TX reads (they move together).
        let wallMs: Int64 = 15_000 * 200 - 200
        let off = ClockOffset(ntpOffsetMs: 500)
        let shifted = off.apply(toUtcMs: wallMs)

        XCTAssertEqual(SlotClock.slotID(atUtcMs: wallMs), 199)
        XCTAssertEqual(SlotClock.rxSlotID(atUtcMs: shifted, rxOffsetMs: 0), 200)
        XCTAssertEqual(SlotClock.slotID(atUtcMs: shifted), 200)
    }

    // MARK: - Manual clamping

    func testManualClampToRange() {
        XCTAssertEqual(ClockOffset.clampManualMs(9_999), ClockOffset.manualMaxMs)
        XCTAssertEqual(ClockOffset.clampManualMs(-9_999), ClockOffset.manualMinMs)
        XCTAssertEqual(ClockOffset.clampManualMs(250), 250)
    }

    func testInitClampsManualOffset() {
        let hi = ClockOffset(manualOffsetMs: 100_000)
        XCTAssertEqual(hi.manualOffsetMs, ClockOffset.manualMaxMs)
        let lo = ClockOffset(manualOffsetMs: -100_000)
        XCTAssertEqual(lo.manualOffsetMs, ClockOffset.manualMinMs)
    }

    func testStepManualNudgesAndClamps() {
        XCTAssertEqual(ClockOffset.stepManualMs(0, byMs: 100), 100)
        XCTAssertEqual(ClockOffset.stepManualMs(0, byMs: -500), -500)
        // Step past the ceiling clamps to the max.
        XCTAssertEqual(ClockOffset.stepManualMs(4_800, byMs: 500), ClockOffset.manualMaxMs)
        XCTAssertEqual(ClockOffset.stepManualMs(-4_800, byMs: -500), ClockOffset.manualMinMs)
    }

    // MARK: - Formatting

    func testFormatMs() {
        XCTAssertEqual(ClockOffset.formatMs(600), "+0.6 s")
        XCTAssertEqual(ClockOffset.formatMs(-300), "-0.3 s")
        XCTAssertEqual(ClockOffset.formatMs(0), "0.0 s")
        // A value that rounds to 0.0 never renders a sign.
        XCTAssertEqual(ClockOffset.formatMs(40), "0.0 s")
        XCTAssertEqual(ClockOffset.formatMs(-40), "0.0 s")
    }
}
