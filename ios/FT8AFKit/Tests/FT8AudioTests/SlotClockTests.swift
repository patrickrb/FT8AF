import XCTest
@testable import FT8Audio

final class SlotClockTests: XCTestCase {

    func testSlotIDAndMsIntoCycle() {
        // 3 full cycles + 4.2 s into the 4th.
        let now: Int64 = 3 * 15_000 + 4_200
        XCTAssertEqual(SlotClock.slotID(atUtcMs: now), 3)
        XCTAssertEqual(SlotClock.msIntoCycle(atUtcMs: now), 4_200)
    }

    func testBoundaryIsExact() {
        XCTAssertEqual(SlotClock.slotID(atUtcMs: 45_000), 3)
        XCTAssertEqual(SlotClock.msIntoCycle(atUtcMs: 45_000), 0)
    }

    func testRxSlotIDShiftsByOffset() {
        // 200 ms into a new slot, but a 500 ms capture-latency offset means the
        // RX window is still finishing the previous slot.
        let now: Int64 = 60_000 + 200
        XCTAssertEqual(SlotClock.slotID(atUtcMs: now), 4)
        XCTAssertEqual(SlotClock.rxSlotID(atUtcMs: now, rxOffsetMs: 500), 3)
        // Zero offset collapses to the plain slot id.
        XCTAssertEqual(SlotClock.rxSlotID(atUtcMs: now, rxOffsetMs: 0), 4)
    }

    func testParityAlternates() {
        XCTAssertEqual(SlotClock.parity(slotID: 0), 0)
        XCTAssertEqual(SlotClock.parity(slotID: 1), 1)
        XCTAssertEqual(SlotClock.parity(slotID: 2), 0)
        XCTAssertEqual(SlotClock.parity(slotID: 3), 1)
    }

    func testEuclideanWithNegativeTimes() {
        // A clock correction can push the effective time slightly negative; the
        // remainder must stay non-negative (Euclidean), not go to -something.
        XCTAssertEqual(SlotClock.slotID(atUtcMs: -1), -1)
        XCTAssertEqual(SlotClock.msIntoCycle(atUtcMs: -1), 14_999)
        XCTAssertEqual(SlotClock.parity(slotID: -1), 1)
        XCTAssertEqual(SlotClock.parity(slotID: -2), 0)
    }
}
