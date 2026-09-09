import XCTest
@testable import FT8Engine

final class CallerQueueTests: XCTestCase {

    private func caller(_ call: String, snr: Int = -10, at ms: Int64 = 0) -> QueuedCaller {
        QueuedCaller(callsign: call, snr: snr, freqHz: 1500, grid: "FN42", queuedAtMs: ms)
    }

    // MARK: enqueue / dedup

    func testEnqueueAppendsInOrder() {
        var q = CallerQueue()
        q.enqueue(caller("W1AAA"))
        q.enqueue(caller("W2BBB"))
        XCTAssertEqual(q.callers.map(\.callsign), ["W1AAA", "W2BBB"])
    }

    func testDedupByCallsignRefreshesButKeepsPosition() {
        var q = CallerQueue()
        q.enqueue(caller("W1AAA", snr: -20, at: 0))
        q.enqueue(caller("W2BBB", at: 0))
        // Re-heard louder and later — updates in place, stays at the head.
        XCTAssertTrue(q.enqueue(caller("w1aaa", snr: -3, at: 30_000)))
        XCTAssertEqual(q.count, 2)
        XCTAssertEqual(q.callers[0].callsign, "W1AAA")
        XCTAssertEqual(q.callers[0].snr, -3)
        XCTAssertEqual(q.callers[0].queuedAtMs, 30_000)
    }

    func testCapacityCap() {
        var q = CallerQueue()
        for i in 0..<CallerQueue.maxSize {
            XCTAssertTrue(q.enqueue(caller("W\(i)AA")))
        }
        XCTAssertFalse(q.enqueue(caller("K9OVERFLOW")))
        XCTAssertEqual(q.count, CallerQueue.maxSize)
        // But an existing caller still refreshes at capacity.
        XCTAssertTrue(q.enqueue(caller("W0AA", snr: 5)))
    }

    // MARK: staleness

    func testRemoveStaleDropsOldCallersOnly() {
        var q = CallerQueue()
        q.enqueue(caller("W1OLD", at: 0))
        q.enqueue(caller("W2NEW", at: 50_000))
        q.removeStale(nowMs: CallerQueue.staleAfterMs + 1)
        XCTAssertEqual(q.callers.map(\.callsign), ["W2NEW"])
        // Exactly at the limit is kept.
        var q2 = CallerQueue()
        q2.enqueue(caller("W1EDGE", at: 0))
        q2.removeStale(nowMs: CallerQueue.staleAfterMs)
        XCTAssertEqual(q2.count, 1)
    }

    // MARK: dequeue

    func testDequeueNextIsFifo() {
        var q = CallerQueue()
        q.enqueue(caller("W1AAA"))
        q.enqueue(caller("W2BBB"))
        XCTAssertEqual(q.dequeueNext()?.callsign, "W1AAA")
        XCTAssertEqual(q.dequeueNext()?.callsign, "W2BBB")
        XCTAssertNil(q.dequeueNext())
    }

    func testDequeueSpecificCaseInsensitive() {
        var q = CallerQueue()
        q.enqueue(caller("W1AAA"))
        q.enqueue(caller("W2BBB"))
        XCTAssertEqual(q.dequeue(callsign: "w2bbb")?.callsign, "W2BBB")
        XCTAssertEqual(q.callers.map(\.callsign), ["W1AAA"])
        XCTAssertNil(q.dequeue(callsign: "K9NONE"))
    }

    func testRemoveAndRemoveAll() {
        var q = CallerQueue()
        q.enqueue(caller("W1AAA"))
        q.enqueue(caller("W2BBB"))
        q.remove(callsign: "W1AAA")
        XCTAssertEqual(q.callers.map(\.callsign), ["W2BBB"])
        q.removeAll()
        XCTAssertTrue(q.isEmpty)
    }

    // MARK: enqueue eligibility policy

    func testShouldEnqueuePolicy() {
        // Normal caller → yes.
        XCTAssertTrue(CallerQueue.shouldEnqueue(
            callsign: "W1AAA", myCall: "K1ABC", currentTarget: "W9XYZ", blocked: []))
        // Never myself.
        XCTAssertFalse(CallerQueue.shouldEnqueue(
            callsign: "k1abc", myCall: "K1ABC", currentTarget: nil, blocked: []))
        // Never the station I'm already working.
        XCTAssertFalse(CallerQueue.shouldEnqueue(
            callsign: "W9XYZ", myCall: "K1ABC", currentTarget: "w9xyz", blocked: []))
        // Never a blocked callsign (case-insensitive).
        XCTAssertFalse(CallerQueue.shouldEnqueue(
            callsign: "W1BAD", myCall: "K1ABC", currentTarget: nil, blocked: ["w1bad"]))
        // Never an empty callsign.
        XCTAssertFalse(CallerQueue.shouldEnqueue(
            callsign: "", myCall: "K1ABC", currentTarget: nil, blocked: []))
    }
}
