import XCTest
import FT8DSP
@testable import FT8Audio

final class SlotAccumulatorTests: XCTestCase {

    func testTakeSlotFrontPadsWhenUnderfilled() {
        let acc = SlotAccumulator()
        let n = 1000
        acc.push((0..<n).map { Float($0) }) // 0,1,2,...,999
        let slot = acc.takeSlot()
        XCTAssertEqual(slot.count, FT8.slotSamples)
        // Leading region is silence...
        XCTAssertTrue(slot[0..<(FT8.slotSamples - n)].allSatisfy { $0 == 0 })
        // ...and the captured samples land flush at the end, in order.
        XCTAssertEqual(slot[FT8.slotSamples - n], 0)
        XCTAssertEqual(slot[FT8.slotSamples - 1], Float(n - 1))
    }

    func testTakeSlotReturnsMostRecentFullSlotWhenOverfilled() {
        let acc = SlotAccumulator()
        let extra = 5000
        // Push more than a slot; the oldest `extra` samples should be dropped.
        acc.push((0..<(FT8.slotSamples + extra)).map { Float($0) })
        let slot = acc.takeSlot()
        XCTAssertEqual(slot.count, FT8.slotSamples)
        XCTAssertEqual(slot.first, Float(extra))                    // first kept sample
        XCTAssertEqual(slot.last, Float(FT8.slotSamples + extra - 1)) // newest sample
    }

    func testCapEvictsOldestBeyondAboutSixteenSeconds() {
        let acc = SlotAccumulator()
        let cap = FT8.slotSamples + Int(FT8.sampleRate)
        acc.push([Float](repeating: 1, count: cap + 4096))
        XCTAssertEqual(acc.count, cap, "buffer is capped at ~16 s")
    }

    func testPeekRecentDuringWarmupAndSteadyState() {
        let acc = SlotAccumulator()
        XCTAssertEqual(acc.peekRecent(2048).count, 0, "empty buffer yields nothing")
        acc.push((0..<500).map { Float($0) })
        XCTAssertEqual(acc.peekRecent(2048).count, 500, "warm-up: clamped to what's available")
        let recent = acc.peekRecent(100)
        XCTAssertEqual(recent.count, 100)
        XCTAssertEqual(recent.first, 400) // most recent 100 of 0..499
        XCTAssertEqual(recent.last, 499)
    }

    func testClear() {
        let acc = SlotAccumulator()
        acc.push([Float](repeating: 1, count: 100))
        XCTAssertFalse(acc.isEmpty)
        acc.clear()
        XCTAssertTrue(acc.isEmpty)
        XCTAssertEqual(acc.count, 0)
    }
}
