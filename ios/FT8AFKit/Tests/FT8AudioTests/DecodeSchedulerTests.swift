import XCTest
@testable import FT8Audio

final class DecodeSchedulerTests: XCTestCase {

    private let sr = 12_000

    // MARK: - Window sample math

    func testWindowSampleCount() {
        // Full 15 s slot and the 13.5 s early window at 12 kHz.
        XCTAssertEqual(DecodeScheduler.windowSampleCount(windowMs: 15_000, sampleRate: sr), 180_000)
        XCTAssertEqual(DecodeScheduler.windowSampleCount(windowMs: 13_500, sampleRate: sr), 162_000)
        XCTAssertEqual(DecodeScheduler.windowSampleCount(windowMs: -5, sampleRate: sr), 0)
    }

    /// The 12.64 s FT8 waveform must never be clipped by the early window: the
    /// early trigger only fires at msInto >= 13.5 s, so the smallest possible
    /// window (13.5 s) still exceeds the waveform.
    func testEarlyWindowNeverClipsWaveform() {
        let minEarlyWindow = DecodeScheduler.windowSampleCount(
            windowMs: DecodeScheduler.earlyDecodeMillis, sampleRate: sr)
        let waveform = DecodeScheduler.windowSampleCount(
            windowMs: DecodeScheduler.ft8WaveformMillis, sampleRate: sr)
        XCTAssertGreaterThan(minEarlyWindow, waveform,
            "13.5 s early window must exceed the 12.64 s waveform")
    }

    // MARK: - Off (boundary) mode

    func testOffModeFiresAtBoundaryOverFullSlot() {
        // Just past the boundary into slot 5 (msInto = 100 ms), last decoded 3.
        let now: Int64 = 5 * 15_000 + 100
        let plan = DecodeScheduler.plan(
            nowMs: now, rxOffsetMs: 0, earlyDecode: false,
            lastDecodedAudioSlotID: 3, sampleRate: sr)
        // Decodes the slot that just ended (4); reply targets slot 5.
        XCTAssertEqual(plan?.audioSlotID, 4)
        XCTAssertEqual(plan?.replySlotID, 5)
        XCTAssertEqual(plan?.windowSamples, 180_000, "full-slot window")
    }

    func testOffModeDoesNotRefireSameSlot() {
        // Mid slot 5, audio slot 4 already decoded — no new decode.
        let now: Int64 = 5 * 15_000 + 8_000
        let plan = DecodeScheduler.plan(
            nowMs: now, rxOffsetMs: 0, earlyDecode: false,
            lastDecodedAudioSlotID: 4, sampleRate: sr)
        XCTAssertNil(plan)
    }

    // MARK: - Early mode

    func testEarlyModeDoesNotFireBeforeEarlyMark() {
        // 13.0 s into slot 5, before the 13.5 s early mark.
        let now: Int64 = 5 * 15_000 + 13_000
        let plan = DecodeScheduler.plan(
            nowMs: now, rxOffsetMs: 0, earlyDecode: true,
            lastDecodedAudioSlotID: 4, sampleRate: sr)
        XCTAssertNil(plan, "must wait until msInto >= earlyDecodeMillis")
    }

    func testEarlyModeFiresAtEarlyMarkOnCurrentSlot() {
        // 13.6 s into slot 5 (crossed the 13.5 s mark), last decoded 4.
        let msInto: Int64 = 13_600
        let now: Int64 = 5 * 15_000 + msInto
        let plan = DecodeScheduler.plan(
            nowMs: now, rxOffsetMs: 0, earlyDecode: true,
            lastDecodedAudioSlotID: 4, sampleRate: sr)
        // Decodes the IN-PROGRESS slot (5) early; reply targets slot 6.
        XCTAssertEqual(plan?.audioSlotID, 5)
        XCTAssertEqual(plan?.replySlotID, 6)
        // Window = audio captured since slot start (msInto), never a fixed 13.5 s,
        // so the slot's leading edge stays in the buffer.
        XCTAssertEqual(plan?.windowSamples,
                       DecodeScheduler.windowSampleCount(windowMs: msInto, sampleRate: sr))
    }

    /// The core toggle contract: at the same instant, on vs. off pick a
    /// different audio slot and a different window — the toggle is not a no-op.
    func testEarlyVsOffDifferAtSameInstant() {
        let now: Int64 = 5 * 15_000 + 13_600
        let off = DecodeScheduler.plan(
            nowMs: now, rxOffsetMs: 0, earlyDecode: false,
            lastDecodedAudioSlotID: 3, sampleRate: sr)
        let early = DecodeScheduler.plan(
            nowMs: now, rxOffsetMs: 0, earlyDecode: true,
            lastDecodedAudioSlotID: 4, sampleRate: sr)
        // Off decodes the completed slot 4; early decodes the in-progress slot 5.
        XCTAssertEqual(off?.audioSlotID, 4)
        XCTAssertEqual(early?.audioSlotID, 5)
        XCTAssertNotEqual(off?.windowSamples, early?.windowSamples)
    }

    func testEarlyModeOneDecodePerSlot() {
        // Already early-decoded slot 5; another tick within slot 5 must not refire.
        let now: Int64 = 5 * 15_000 + 14_000
        let plan = DecodeScheduler.plan(
            nowMs: now, rxOffsetMs: 0, earlyDecode: true,
            lastDecodedAudioSlotID: 5, sampleRate: sr)
        XCTAssertNil(plan)
    }

    // MARK: - Reply boundary delay

    func testReplyBoundaryDelayOffIsZero() {
        XCTAssertEqual(DecodeScheduler.replyBoundaryDelayMs(earlyDecode: false, msIntoCycle: 13_600), 0)
        XCTAssertEqual(DecodeScheduler.replyBoundaryDelayMs(earlyDecode: false, msIntoCycle: 100), 0)
    }

    func testReplyBoundaryDelayEarlyWaitsToBoundary() {
        // Early decode 13.6 s in -> wait the remaining 1.4 s so TX keys at the boundary.
        XCTAssertEqual(DecodeScheduler.replyBoundaryDelayMs(earlyDecode: true, msIntoCycle: 13_600), 1_400)
        // Right at the boundary -> no wait; never negative past it.
        XCTAssertEqual(DecodeScheduler.replyBoundaryDelayMs(earlyDecode: true, msIntoCycle: 15_000), 0)
    }

    // MARK: - rxOffset shift

    func testRxOffsetShiftsBoundaryDetection() {
        // 100 ms past the wall-clock boundary into slot 5, but a 500 ms capture
        // offset means the RX window is still finishing slot 4 -> no boundary yet.
        let now: Int64 = 5 * 15_000 + 100
        let plan = DecodeScheduler.plan(
            nowMs: now, rxOffsetMs: 500, earlyDecode: false,
            lastDecodedAudioSlotID: 3, sampleRate: sr)
        // rxSlotID is 4 here, so the completed audio slot is 3 (already decoded).
        XCTAssertNil(plan)
    }
}
