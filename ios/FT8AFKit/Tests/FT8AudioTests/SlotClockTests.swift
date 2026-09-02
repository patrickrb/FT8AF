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

    func testShouldTransmitGatesOnTheSlotWeKeyUpIn() {
        // We transmit in the slot itself (audio starts immediately), so eligibility
        // is the parity of that very slot.
        // Even slot (parity 0):
        XCTAssertTrue(SlotClock.shouldTransmit(slotID: 4, desiredParity: 0))
        XCTAssertFalse(SlotClock.shouldTransmit(slotID: 4, desiredParity: 1))
        // Odd slot (parity 1):
        XCTAssertTrue(SlotClock.shouldTransmit(slotID: 5, desiredParity: 1))
        XCTAssertFalse(SlotClock.shouldTransmit(slotID: 5, desiredParity: 0))
    }

    func testShouldTransmitUsesCurrentSlotNotNextSlotRegression() {
        // Regression for the off-by-one that gated on `parity(slotID + 1)`: that
        // inverted the operator's TX1/TX2 selection, keying up on the opposite
        // slot and colliding with the station being answered. For every slot the
        // decision must match the slot's own parity, never the next slot's.
        for slot in Int64(0)..<8 {
            let ownParity = Int(SlotClock.parity(slotID: slot))
            let nextParity = Int(SlotClock.parity(slotID: slot + 1))
            XCTAssertTrue(SlotClock.shouldTransmit(slotID: slot, desiredParity: ownParity))
            XCTAssertFalse(SlotClock.shouldTransmit(slotID: slot, desiredParity: nextParity))
        }
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
