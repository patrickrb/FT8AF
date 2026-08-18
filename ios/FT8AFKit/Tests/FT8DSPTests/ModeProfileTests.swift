import XCTest
@testable import FT8DSP

/// Locks the per-mode timing/codec constants to Android's `ModeProfile`
/// (FT8 15 s / FT4 7.5 s) and guards that the FT8 profile still reproduces the
/// pre-existing `FT8.*` constants byte-for-byte (regression guard).
final class ModeProfileTests: XCTestCase {

    // MARK: - FT8 profile == the historical FT8 constants (regression guard)

    func testFT8ProfileMatchesAndroidAndLegacyConstants() {
        let p = ModeProfile.ft8
        XCTAssertEqual(p.mode, .ft8)
        XCTAssertEqual(p.displayName, "FT8")
        XCTAssertEqual(p.cycleMs, 15_000)
        XCTAssertEqual(p.earlyDecodeMillis, 13_500)
        XCTAssertEqual(p.symbolPeriod, 0.160)
        XCTAssertEqual(p.symbolBT, 2.0)
        XCTAssertEqual(p.numTones, 79)
        XCTAssertTrue(p.isFT8)
        XCTAssertEqual(p.waveformMs, 12_640)
        XCTAssertEqual(p.slackMs, 2_360)
        XCTAssertEqual(p.slotSamples, 180_000)
        XCTAssertEqual(p.trPeriodSeconds, 15)
        XCTAssertEqual(p.deepDecodeBudgetMillis, 11_250)
    }

    /// The FT8 profile must not drift from the standalone `FT8.*` constants the
    /// encoder/decoder/accumulator still use directly.
    func testFT8ProfileAgreesWithFT8Constants() {
        XCTAssertEqual(ModeProfile.ft8.numTones, FT8.nn)
        XCTAssertEqual(ModeProfile.ft8.symbolPeriod, FT8.symbolPeriod)
        XCTAssertEqual(ModeProfile.ft8.symbolBT, FT8.symbolBT)
        XCTAssertEqual(ModeProfile.ft8.sampleRate, FT8.sampleRate)
        XCTAssertEqual(ModeProfile.ft8.slotSamples, FT8.slotSamples)
    }

    // MARK: - FT4 profile == Android's FT4 constants exactly

    func testFT4ProfileMatchesAndroid() {
        let p = ModeProfile.ft4
        XCTAssertEqual(p.mode, .ft4)
        XCTAssertEqual(p.displayName, "FT4")
        XCTAssertEqual(p.cycleMs, 7_500)
        XCTAssertEqual(p.earlyDecodeMillis, 6_500)
        XCTAssertEqual(p.symbolPeriod, 0.048)
        XCTAssertEqual(p.symbolBT, 1.0)
        XCTAssertEqual(p.numTones, 105)
        XCTAssertFalse(p.isFT8)
        // audioMillis = round(105 * 0.048 * 1000) = 5040
        XCTAssertEqual(p.waveformMs, 5_040)
        // audioSlackMillis = 7500 - 5040 = 2460
        XCTAssertEqual(p.slackMs, 2_460)
        // sampleRate * cycleMs / 1000 = 12000 * 7.5 = 90000
        XCTAssertEqual(p.slotSamples, 90_000)
        XCTAssertEqual(p.trPeriodSeconds, 7)
        // max(2500, round(7500 * 0.75)) = 5625
        XCTAssertEqual(p.deepDecodeBudgetMillis, 5_625)
    }

    /// FT4's early-decode window must exceed its own waveform, else an on-time
    /// FT4 signal would be clipped by the early trigger (the same invariant FT8
    /// relies on).
    func testFT4EarlyWindowExceedsWaveform() {
        XCTAssertGreaterThan(ModeProfile.ft4.earlyDecodeMillis, ModeProfile.ft4.waveformMs)
        XCTAssertGreaterThan(ModeProfile.ft8.earlyDecodeMillis, ModeProfile.ft8.waveformMs)
    }

    // MARK: - Mode <-> profile mapping

    func testModeProfileMapping() {
        XCTAssertEqual(Mode.ft8.profile, .ft8)
        XCTAssertEqual(Mode.ft4.profile, .ft4)
        XCTAssertEqual(Mode.allCases, [.ft8, .ft4])
        XCTAssertEqual(Mode.ft8.rawValue, "FT8")
        XCTAssertEqual(Mode.ft4.rawValue, "FT4")
        XCTAssertEqual(Mode(rawValue: "FT4"), .ft4)
        XCTAssertNil(Mode(rawValue: "FT2")) // out of scope, must not resolve
    }
}
