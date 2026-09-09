import XCTest
import FT8Audio

/// Guards the once-per-slot rule behind the waterfall's UTC boundary labels
/// (port of the Android `WaterfallTimestampGateTest`).
final class WaterfallTimestampGateTests: XCTestCase {

    func testSlotPeriodMatchesFifteenSecondGrid() {
        XCTAssertEqual(WaterfallTimestampGate.slotPeriod(utcMs: 0), 0)
        XCTAssertEqual(WaterfallTimestampGate.slotPeriod(utcMs: 14_999), 0)
        XCTAssertEqual(WaterfallTimestampGate.slotPeriod(utcMs: 15_000), 1)
        XCTAssertEqual(WaterfallTimestampGate.slotPeriod(utcMs: 29_999), 1)
        XCTAssertEqual(WaterfallTimestampGate.slotPeriod(utcMs: 30_000), 2)
    }

    func testSlotPeriodNonPositiveSlotFallsBackToFifteenSeconds() {
        XCTAssertEqual(
            WaterfallTimestampGate.slotPeriod(utcMs: 16_000, slotMs: 0),
            WaterfallTimestampGate.slotPeriod(utcMs: 16_000)
        )
        XCTAssertEqual(
            WaterfallTimestampGate.slotPeriod(utcMs: 16_000, slotMs: -5),
            WaterfallTimestampGate.slotPeriod(utcMs: 16_000)
        )
    }

    func testSlotStartMsIsPeriodStart() {
        XCTAssertEqual(WaterfallTimestampGate.slotStartMs(utcMs: 0), 0)
        XCTAssertEqual(WaterfallTimestampGate.slotStartMs(utcMs: 14_999), 0)
        XCTAssertEqual(WaterfallTimestampGate.slotStartMs(utcMs: 15_001), 15_000)
        XCTAssertEqual(WaterfallTimestampGate.slotStartMs(utcMs: 44_800), 30_000)
    }

    func testFirstCallDraws() {
        var gate = WaterfallTimestampGate()
        XCTAssertTrue(gate.shouldDraw(utcMs: 7_300))
    }

    func testDrawsExactlyOncePerSlotAcrossPolledFrames() {
        // The waterfall loop polls every 250 ms; over two full slots the gate
        // must emit exactly at the first frame of each slot and nowhere else.
        var gate = WaterfallTimestampGate()
        var drawTimes: [Int64] = []
        var t: Int64 = 0
        while t < 45_000 {
            if gate.shouldDraw(utcMs: t) { drawTimes.append(t) }
            t += 250
        }
        XCTAssertEqual(drawTimes, [0, 15_000, 30_000])
    }

    func testStaysQuietWithinOneSlot() {
        var gate = WaterfallTimestampGate()
        XCTAssertTrue(gate.shouldDraw(utcMs: 15_100))
        XCTAssertFalse(gate.shouldDraw(utcMs: 15_400))
        XCTAssertFalse(gate.shouldDraw(utcMs: 29_999))
        XCTAssertTrue(gate.shouldDraw(utcMs: 30_000))
    }

    func testResetRearmsTheGate() {
        var gate = WaterfallTimestampGate()
        XCTAssertTrue(gate.shouldDraw(utcMs: 5_000))
        XCTAssertFalse(gate.shouldDraw(utcMs: 5_500))
        gate.reset()
        // Same slot, but the history was cleared (e.g. waterfall rebuilt), so
        // the label must be stamped again.
        XCTAssertTrue(gate.shouldDraw(utcMs: 6_000))
    }

    func testModeChangeRebaselinesWithoutSpuriousDraw() {
        // FT4 (7.5 s) → FT8 (15 s) mid-slot: period indices are not comparable
        // across modes, so the gate re-baselines silently instead of emitting a
        // line in the middle of a slot.
        var gate = WaterfallTimestampGate()
        XCTAssertTrue(gate.shouldDraw(utcMs: 7_600, slotMs: 7_500))
        XCTAssertFalse(gate.shouldDraw(utcMs: 8_000, slotMs: 15_000)) // mid-slot, no draw
        XCTAssertTrue(gate.shouldDraw(utcMs: 15_000, slotMs: 15_000)) // next true boundary
    }

    func testModeChangeExactlyOnNewBoundaryDraws() {
        var gate = WaterfallTimestampGate()
        XCTAssertTrue(gate.shouldDraw(utcMs: 7_500, slotMs: 7_500))
        XCTAssertTrue(gate.shouldDraw(utcMs: 15_000, slotMs: 15_000)) // lands exactly on FT8 boundary
    }

    func testUtcLabelFormatsHHmmss() {
        XCTAssertEqual(WaterfallTimestampGate.utcLabel(forUtcMs: 0), "00:00:00")
        XCTAssertEqual(WaterfallTimestampGate.utcLabel(forUtcMs: 45_000), "00:00:45")
        // 13:32:15 UTC on any day: 13*3600 + 32*60 + 15 = 48_735 s.
        XCTAssertEqual(WaterfallTimestampGate.utcLabel(forUtcMs: 48_735_000), "13:32:15")
        // Rolls over at midnight, independent of the day.
        XCTAssertEqual(WaterfallTimestampGate.utcLabel(forUtcMs: 86_400_000 + 61_000), "00:01:01")
    }

    func testBoundaryLabelShowsSlotStartNotFrameTime() {
        // A frame that crosses the boundary 300 ms late must still label the
        // period start (Android stamps the period-start time).
        let frameMs: Int64 = 30_300
        let label = WaterfallTimestampGate.utcLabel(
            forUtcMs: WaterfallTimestampGate.slotStartMs(utcMs: frameMs)
        )
        XCTAssertEqual(label, "00:00:30")
    }
}
