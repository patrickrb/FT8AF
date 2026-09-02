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

    // MARK: - Auto-DT is a whole-clock term (the primary change)

    func testCombinedIncludesAutoComponent() {
        let off = ClockOffset(ntpOffsetMs: 100, manualOffsetMs: 50, autoOffsetMs: -300)
        XCTAssertEqual(off.combinedMs, -150)
        XCTAssertEqual(off.apply(toUtcMs: 1_000_000), 999_850)
    }

    func testAutoDefaultsToZeroSoExistingCallSitesAreUnchanged() {
        let off = ClockOffset(ntpOffsetMs: 200, manualOffsetMs: -50)
        XCTAssertEqual(off.autoOffsetMs, 0)
        XCTAssertEqual(off.combinedMs, 150)
    }

    func testAutoOffsetShiftsRxSlotAndTxCycleByTheSameAmount() {
        // A confirmed auto-DT correction feeds autoOffsetMs; RX slot detection and
        // TX cycle position must move together (whole-clock), unlike the retired
        // RX-only path that moved only the RX slice.
        let wallMs: Int64 = 15_000 * 100 + 5_000 // 5.000 s into slot 100
        let auto: Int64 = -300                    // a DtCalibrator correction
        let off = ClockOffset(autoOffsetMs: auto)
        let shifted = off.apply(toUtcMs: wallMs)

        // RX: pass rxOffsetMs 0 — the only correction now rides the shifted clock.
        let baseRx = SlotClock.msIntoCycle(atUtcMs: wallMs)
        let shiftedRx = SlotClock.rxSlotID(atUtcMs: shifted, rxOffsetMs: 0)
        XCTAssertEqual(SlotClock.rxSlotID(atUtcMs: wallMs, rxOffsetMs: 0), shiftedRx) // same slot
        // TX: msIntoCycle off the shifted clock moves by exactly the auto offset.
        let shiftedTx = SlotClock.msIntoCycle(atUtcMs: shifted)
        XCTAssertEqual(shiftedTx - baseRx, auto)
    }

    func testAutoCorrectionAppliedToRxExactlyOnce() {
        // Regression guard against double-correcting RX: with the auto-DT folded
        // into the whole clock and rxOffsetMs pinned to 0, the RX cycle position
        // shifts by exactly `auto` — not 2×auto (which a lingering rxOffsetMs
        // path would produce).
        let wallMs: Int64 = 15_000 * 40 + 7_000
        let auto: Int64 = 450
        let shifted = ClockOffset(autoOffsetMs: auto).apply(toUtcMs: wallMs)

        // RX read uses the shifted clock with NO separate rxOffset (0).
        let rxShift = SlotClock.msIntoCycle(atUtcMs: shifted - 0)
            - SlotClock.msIntoCycle(atUtcMs: wallMs)
        XCTAssertEqual(rxShift, auto)
        // A hypothetical double-apply (auto folded in *and* an equal rxOffset)
        // would shift by 2×auto — assert we are NOT doing that.
        let doubled = SlotClock.msIntoCycle(atUtcMs: shifted - auto)
            - SlotClock.msIntoCycle(atUtcMs: wallMs)
        XCTAssertEqual(doubled, 0)
        XCTAssertNotEqual(rxShift, 2 * auto)
    }

    func testCalibratorResultDrivesTheWholeClockConsistently() {
        // End-to-end: a consistent +0.5 s band bias over CONFIRM_SLOTS produces a
        // DtCalibrator correction, and feeding it into autoOffsetMs shifts an RX
        // slot read and a TX msIntoCycle read by the identical amount.
        var cal = DtCalibrator()
        let dts = [Float](repeating: 0.5, count: 4)
        XCTAssertNil(cal.calibrate(autoOffsetMs: 0, decodedDtSec: dts)) // building
        guard let correction = cal.calibrate(autoOffsetMs: 0, decodedDtSec: dts) else {
            return XCTFail("expected a correction after the confirm slot")
        }
        XCTAssertEqual(correction, -300)

        let wallMs: Int64 = 15_000 * 10 + 6_000
        let shifted = ClockOffset(autoOffsetMs: correction).apply(toUtcMs: wallMs)
        let rxShift = SlotClock.msIntoCycle(atUtcMs: shifted, cycleMs: SlotClock.cycleMs)
            - SlotClock.msIntoCycle(atUtcMs: wallMs)
        let txShift = SlotClock.msIntoCycle(atUtcMs: shifted)
            - SlotClock.msIntoCycle(atUtcMs: wallMs)
        XCTAssertEqual(rxShift, correction)
        XCTAssertEqual(txShift, correction)
    }

    func testPlausibleAutoCorrectionKeepsOnTimeTxOutOfClipTerritory() {
        // TX safety: the auto offset drives the clock toward truth, so an on-time
        // key-up stays inside the 2.36 s slack (no leading-Costas clip). Even the
        // *maximum* auto correction, applied to a key-up that fires ~0.6 s into
        // the corrected cycle, leaves the cycle position well under the slack.
        let auto = ClockOffset(autoOffsetMs: DtCalibrator.maxOffsetMs)
        let correctedNow: Int64 = 15_000 * 3 + 600 // ~0.6 s into the corrected cycle
        let wallMs = correctedNow - auto.combinedMs
        let msInto = ClockOffset(autoOffsetMs: DtCalibrator.maxOffsetMs)
            .apply(toUtcMs: wallMs)
        let clip = max(0, SlotClock.msIntoCycle(atUtcMs: msInto) - 2_360)
        XCTAssertEqual(clip, 0)
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
