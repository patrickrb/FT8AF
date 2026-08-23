import XCTest
@testable import FT8Audio
import FT8DSP

/// SlotClock + DecodeScheduler math under a non-FT8 `ModeProfile`. Proves the
/// FT4 (7.5 s) profile shifts slot boundaries and window sizes correctly, that
/// FT4's shorter waveform is never clipped, and that omitting the profile (the
/// FT8 default) reproduces the existing FT8 behavior exactly (regression guard).
final class ModeTimingTests: XCTestCase {

    private let sr = 12_000
    private let ft4 = ModeProfile.ft4

    // MARK: - SlotClock under FT4 cadence

    func testSlotBoundariesAtSevenAndAHalfSeconds() {
        // 3 full FT4 slots + 4.2 s into the 4th.
        let now: Int64 = 3 * 7_500 + 4_200
        XCTAssertEqual(SlotClock.slotID(atUtcMs: now, cycleMs: ft4.cycleMs), 3)
        XCTAssertEqual(SlotClock.msIntoCycle(atUtcMs: now, cycleMs: ft4.cycleMs), 4_200)
        // Exact boundary.
        XCTAssertEqual(SlotClock.slotID(atUtcMs: 22_500, cycleMs: ft4.cycleMs), 3)
        XCTAssertEqual(SlotClock.msIntoCycle(atUtcMs: 22_500, cycleMs: ft4.cycleMs), 0)
    }

    /// The same wall-clock instant lands in different slots under FT8 vs FT4,
    /// so cadence is genuinely driven by the profile, not a hidden constant.
    func testFT4AndFT8DisagreeOnSlotAtSameInstant() {
        let now: Int64 = 15_000 // exactly slot 1 boundary for FT8, slot 2 for FT4
        XCTAssertEqual(SlotClock.slotID(atUtcMs: now), 1) // FT8 default
        XCTAssertEqual(SlotClock.slotID(atUtcMs: now, cycleMs: ft4.cycleMs), 2)
    }

    // MARK: - DecodeScheduler off-mode (boundary) under FT4

    func testFT4OffModeFullSlotWindowIs90000() {
        // Just past the boundary into FT4 slot 5 (msInto = 100), last decoded 3.
        let now: Int64 = 5 * 7_500 + 100
        let plan = DecodeScheduler.plan(
            nowMs: now, rxOffsetMs: 0, earlyDecode: false,
            lastDecodedAudioSlotID: 3, sampleRate: sr, profile: ft4)
        XCTAssertEqual(plan?.audioSlotID, 4)
        XCTAssertEqual(plan?.replySlotID, 5)
        // Full FT4 slot = 7.5 s * 12 kHz = 90000 samples = profile.slotSamples.
        XCTAssertEqual(plan?.windowSamples, 90_000)
        XCTAssertEqual(plan?.windowSamples, ft4.slotSamples)
    }

    // MARK: - DecodeScheduler early-mode under FT4

    func testFT4EarlyModeDoesNotFireBeforeSixPointFive() {
        // 6.0 s into FT4 slot 5, before the 6.5 s early mark.
        let now: Int64 = 5 * 7_500 + 6_000
        let plan = DecodeScheduler.plan(
            nowMs: now, rxOffsetMs: 0, earlyDecode: true,
            lastDecodedAudioSlotID: 4, sampleRate: sr, profile: ft4)
        XCTAssertNil(plan, "must wait until msInto >= FT4 earlyDecodeMillis (6500)")
    }

    func testFT4EarlyModeFiresAtSixPointFiveOnCurrentSlot() {
        let msInto: Int64 = 6_600
        let now: Int64 = 5 * 7_500 + msInto
        let plan = DecodeScheduler.plan(
            nowMs: now, rxOffsetMs: 0, earlyDecode: true,
            lastDecodedAudioSlotID: 4, sampleRate: sr, profile: ft4)
        XCTAssertEqual(plan?.audioSlotID, 5)
        XCTAssertEqual(plan?.replySlotID, 6)
        XCTAssertEqual(plan?.windowSamples,
                       DecodeScheduler.windowSampleCount(windowMs: msInto, sampleRate: sr))
    }

    /// The FT4 early window (min 6.5 s = 78000 samples) must exceed the FT4
    /// waveform (5.04 s = 60480 samples), so an on-time FT4 signal is never
    /// clipped by the early trigger — the FT4 twin of the FT8 invariant.
    func testFT4EarlyWindowNeverClipsWaveform() {
        let minEarly = DecodeScheduler.windowSampleCount(windowMs: ft4.earlyDecodeMillis, sampleRate: sr)
        let waveform = DecodeScheduler.windowSampleCount(windowMs: ft4.waveformMs, sampleRate: sr)
        XCTAssertEqual(minEarly, 78_000)
        XCTAssertEqual(waveform, 60_480)
        XCTAssertGreaterThan(minEarly, waveform)
    }

    // MARK: - Reply boundary delay under FT4

    func testFT4ReplyBoundaryDelayWaitsToTheFT4Boundary() {
        // Early decode 6.6 s into a 7.5 s FT4 cycle -> wait remaining 0.9 s.
        XCTAssertEqual(
            DecodeScheduler.replyBoundaryDelayMs(earlyDecode: true, msIntoCycle: 6_600, profile: ft4),
            900)
        // Off mode never waits.
        XCTAssertEqual(
            DecodeScheduler.replyBoundaryDelayMs(earlyDecode: false, msIntoCycle: 6_600, profile: ft4),
            0)
    }

    // MARK: - FT8 default regression: no profile == profile:.ft8 == legacy

    func testOmittingProfileReproducesFT8Exactly() {
        let now: Int64 = 5 * 15_000 + 13_600
        let noProfile = DecodeScheduler.plan(
            nowMs: now, rxOffsetMs: 0, earlyDecode: true,
            lastDecodedAudioSlotID: 4, sampleRate: sr)
        let explicitFT8 = DecodeScheduler.plan(
            nowMs: now, rxOffsetMs: 0, earlyDecode: true,
            lastDecodedAudioSlotID: 4, sampleRate: sr, profile: .ft8)
        XCTAssertEqual(noProfile, explicitFT8)
        XCTAssertEqual(noProfile?.audioSlotID, 5)

        // Off-mode full-slot window is still the FT8 180000, not FT4's 90000.
        let off = DecodeScheduler.plan(
            nowMs: 5 * 15_000 + 100, rxOffsetMs: 0, earlyDecode: false,
            lastDecodedAudioSlotID: 3, sampleRate: sr)
        XCTAssertEqual(off?.windowSamples, 180_000)

        // SlotClock default cycle is unchanged.
        XCTAssertEqual(SlotClock.slotID(atUtcMs: now),
                       SlotClock.slotID(atUtcMs: now, cycleMs: ModeProfile.ft8.cycleMs))
    }
}
