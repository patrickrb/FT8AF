import XCTest
@testable import FT8Engine
import FT8DSP

final class TxTimingTests: XCTestCase {

    func testDefaultSlackIs2360() {
        XCTAssertEqual(TxTiming.defaultSlackMs, 2360)
    }

    // The load-bearing rule (CLAUDE.md TX pipeline gotcha #2): a normal
    // on-time TX firing a few hundred ms into the cycle must clip NOTHING —
    // that is where the leading Costas sync lives.
    func testOnTimeStartClipsNothing() {
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 0), 0)
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 500), 0)
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 800), 0)
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 2360), 0)
    }

    func testLateStartClipsOnlyTheOverrun() {
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 2361), 1)
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 3000), 640)
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 5000), 2640)
    }

    // Guard against the historical `msIntoCycle % 15000` bug: the correct
    // rule is max(0, ms - slack), never the raw cycle position.
    func testNotModuloSemantics() {
        // 700 ms into the cycle: modulo logic would clip 700 ms (destroying
        // the Costas array); the correct rule clips 0.
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 700), 0)
        // Very late in the cycle the clip is huge (correct — the TX truly is
        // that late), not wrapped around.
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 14_900), 12_540)
    }

    func testCustomSlack() {
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 1500, slackMs: 1000), 500)
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 900, slackMs: 1000), 0)
        // A nonsensical negative slack is treated as zero, not added.
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 100, slackMs: -500), 100)
    }

    // MARK: shouldSkip — the tolerance is a SKIP threshold, never the clip slack

    func testShouldSkipWhenClipExceedsTolerance() {
        // clip(3000) = 640; tolerance 500 → too much leading audio lost, skip.
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 3000), 640)
        XCTAssertTrue(TxTiming.shouldSkip(clipMs: 640, toleranceMs: 500))
        // Same clip within tolerance → transmit (clipped).
        XCTAssertFalse(TxTiming.shouldSkip(clipMs: 640, toleranceMs: 640))
        XCTAssertFalse(TxTiming.shouldSkip(clipMs: 640, toleranceMs: 2360))
    }

    func testShouldSkipOnTimeStartNeverSkips() {
        // clip(800) = 0 always → never skipped, whatever the tolerance.
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 800), 0)
        XCTAssertFalse(TxTiming.shouldSkip(clipMs: 0, toleranceMs: 0))
        XCTAssertFalse(TxTiming.shouldSkip(clipMs: 0, toleranceMs: -100))
        XCTAssertFalse(TxTiming.shouldSkip(clipMs: 0, toleranceMs: 2360))
    }

    func testShouldSkipZeroToleranceSkipsAnyClippedStart() {
        XCTAssertTrue(TxTiming.shouldSkip(clipMs: 1, toleranceMs: 0))
        XCTAssertTrue(TxTiming.shouldSkip(clipMs: 640, toleranceMs: 0))
    }

    func testShouldSkipClampsNegativeTolerance() {
        // A nonsensical negative tolerance behaves like 0, not like a
        // magically-permissive threshold.
        XCTAssertTrue(TxTiming.shouldSkip(clipMs: 1, toleranceMs: -500))
        XCTAssertTrue(TxTiming.shouldSkip(clipMs: 640, toleranceMs: -1))
    }

    func testClipSampleCount() {
        XCTAssertEqual(TxTiming.clipSampleCount(clipMs: 0, sampleRate: 12_000), 0)
        XCTAssertEqual(TxTiming.clipSampleCount(clipMs: 640, sampleRate: 12_000), 7680)
        XCTAssertEqual(TxTiming.clipSampleCount(clipMs: 1000, sampleRate: 12_000), 12_000)
        XCTAssertEqual(TxTiming.clipSampleCount(clipMs: -5, sampleRate: 12_000), 0)
    }

    // MARK: FT4 slack — the clip math must use the FT4 profile's 2460 ms slack

    /// FT4's waveform is 5.04 s in a 7.5 s cycle, so its physical slack is 2460
    /// ms (not FT8's 2360). A normal on-time FT4 TX firing within that slack
    /// clips nothing (the leading Costas array is preserved); only the overrun
    /// past 2460 ms is clipped. This mirrors the FT8 on-time invariant with the
    /// mode-specific slack the LiveEngine now passes.
    func testFT4OnTimeStartClipsNothingWithinFT4Slack() {
        let slack = ModeProfile.ft4.slackMs
        XCTAssertEqual(slack, 2_460)
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 0, slackMs: slack), 0)
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 800, slackMs: slack), 0)
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 2_460, slackMs: slack), 0)
        // Just past the FT4 slack: only the overrun is clipped.
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 2_461, slackMs: slack), 1)
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 3_000, slackMs: slack), 540)
    }

    /// At an msInto within FT8's slack but past nothing for FT4 either — the
    /// wider FT4 slack means a start that would clip under a hypothetical
    /// smaller slack still clips nothing here. Guards that slack is per-mode.
    func testFT4SlackIsWiderThanFT8() {
        XCTAssertGreaterThan(ModeProfile.ft4.slackMs, ModeProfile.ft8.slackMs)
        // 2400 ms in: FT4's wider 2460 ms slack clips nothing, but FT8's 2360 ms
        // slack is already exceeded, so FT8 clips the 40 ms overage.
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 2_400, slackMs: ModeProfile.ft4.slackMs), 0)
        XCTAssertEqual(TxTiming.lateStartClipMs(msIntoCycle: 2_400, slackMs: ModeProfile.ft8.slackMs), 40)
    }
}
