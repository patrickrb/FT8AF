import XCTest
import FT8Audio

final class WaterfallAxisTests: XCTestCase {

    func testDefaultDisplaySpanMatchesDataTop() {
        // The default overlay span must equal the default span the waterfall
        // data is built to.
        XCTAssertEqual(WaterfallAxis.defaultDisplayMaxHz, WaterfallRowBuilder.defaultMaxHz, accuracy: 1e-6)
        XCTAssertEqual(WaterfallAxis.defaultDisplayMaxHz, 3500, accuracy: 1e-6)
    }

    func testFractionMapsAcrossFullDataSpan() {
        XCTAssertEqual(WaterfallAxis.fraction(forHz: 0, displayMaxHz: 3500), 0, accuracy: 1e-6)
        XCTAssertEqual(WaterfallAxis.fraction(forHz: 1750, displayMaxHz: 3500), 0.5, accuracy: 1e-6)
        XCTAssertEqual(WaterfallAxis.fraction(forHz: 3500, displayMaxHz: 3500), 1.0, accuracy: 1e-6)
    }

    /// The mapping follows the operator's spectrum-width setting: the same
    /// signal sits at a different fraction when the displayed band changes.
    func testFractionFollowsConfiguredSpectrumWidth() {
        XCTAssertEqual(WaterfallAxis.fraction(forHz: 1500, displayMaxHz: 3000), 0.5, accuracy: 1e-6)
        XCTAssertEqual(WaterfallAxis.fraction(forHz: 2500, displayMaxHz: 5000), 0.5, accuracy: 1e-6)
        XCTAssertEqual(WaterfallAxis.fraction(forHz: 1250, displayMaxHz: 2500), 0.5, accuracy: 1e-6)
    }

    func testFractionGuardsNonPositiveSpan() {
        XCTAssertEqual(WaterfallAxis.fraction(forHz: 1500, displayMaxHz: 0), 0, accuracy: 1e-6)
        XCTAssertEqual(WaterfallAxis.fraction(forHz: 1500, displayMaxHz: -100), 0, accuracy: 1e-6)
    }

    func testFractionAndHzAreInverse() {
        for width: Float in [2500, 3000, 3500, 4000, 5000] {
            for hz: Float in [0, 100, 733, 1500, 2400, width] {
                let round = WaterfallAxis.hz(
                    forFraction: WaterfallAxis.fraction(forHz: hz, displayMaxHz: width),
                    displayMaxHz: width
                )
                XCTAssertEqual(round, hz, accuracy: 1e-3)
            }
        }
    }

    /// Regression: the overlays used to place a 1500 Hz signal at fraction 0.5
    /// (a 3000 Hz span) while the data spanned 3500 Hz. With the axis driven by
    /// the same width the rows are built to, the marker lands on the trace.
    func testSignalNoLongerMisplacedByMismatchedSpan() {
        let f = WaterfallAxis.fraction(forHz: 1500, displayMaxHz: 3500)
        XCTAssertEqual(f, 1500.0 / 3500.0, accuracy: 1e-6)
        XCTAssertNotEqual(f, 1500.0 / 3000.0, accuracy: 1e-3) // the old, wrong mapping
    }

    /// The overlay axis lines up with the actual columns the builder emits for
    /// the same width: x == width corresponds to `columns * binHz`, which is
    /// within one bin of the display span.
    func testAxisAgreesWithActualColumnSpan() {
        for width: Float in [2500, 3000, 3500, 4000, 5000] {
            for sr in [12_000, 24_000, 48_000] {
                let bin = WaterfallRowBuilder.binHz(sampleRate: sr)
                let dataTop = Float(WaterfallRowBuilder.columns(sampleRate: sr, maxHz: width)) * bin
                XCTAssertEqual(dataTop, width, accuracy: bin)
            }
        }
    }

    /// `columns(sampleRate:)` without a width keeps the historical default span.
    func testColumnsDefaultsToDefaultMaxHz() {
        XCTAssertEqual(
            WaterfallRowBuilder.columns(sampleRate: 12_000),
            WaterfallRowBuilder.columns(sampleRate: 12_000, maxHz: WaterfallRowBuilder.defaultMaxHz)
        )
    }

    // MARK: - TX tuning clamp (Android: 100 ... min(3000, width - 100))

    func testTapToTuneClampsToTxRangeForWidth() {
        // Middle of the strip tunes to the frequency under the finger.
        XCTAssertEqual(WaterfallAxis.tunedTxHz(forFraction: 0.5, displayMaxHz: 3500), 1750, accuracy: 1e-4)
        // Far left clamps up to the minimum TX offset (Android's 100 Hz floor).
        XCTAssertEqual(WaterfallAxis.tunedTxHz(forFraction: 0.0, displayMaxHz: 3500), WaterfallAxis.minTxHz, accuracy: 1e-4)
        XCTAssertEqual(WaterfallAxis.minTxHz, 100, accuracy: 1e-6)
        // Far right (3500 Hz) clamps down to the SSB passband ceiling, not 3500.
        XCTAssertEqual(WaterfallAxis.tunedTxHz(forFraction: 1.0, displayMaxHz: 3500), 3000, accuracy: 1e-4)
    }

    /// The upper clamp is min(3000, width - 100): narrow widths keep TX 100 Hz
    /// inside the displayed band; wide widths never exceed the 3000 Hz SSB
    /// passband ceiling even though the display goes higher.
    func testMaxTxHzFollowsWidthWithPassbandCeiling() {
        XCTAssertEqual(WaterfallAxis.maxTxHz(displayMaxHz: 2500), 2400, accuracy: 1e-4)
        XCTAssertEqual(WaterfallAxis.maxTxHz(displayMaxHz: 3000), 2900, accuracy: 1e-4)
        XCTAssertEqual(WaterfallAxis.maxTxHz(displayMaxHz: 3500), 3000, accuracy: 1e-4)
        XCTAssertEqual(WaterfallAxis.maxTxHz(displayMaxHz: 4000), 3000, accuracy: 1e-4)
        XCTAssertEqual(WaterfallAxis.maxTxHz(displayMaxHz: 5000), 3000, accuracy: 1e-4)
        // Degenerate width never inverts the clamp range.
        XCTAssertGreaterThanOrEqual(WaterfallAxis.maxTxHz(displayMaxHz: 0), WaterfallAxis.minTxHz)
    }

    func testTapAtFarRightOfNarrowWidthStaysInsideBand() {
        // Width 2500: the far right is 2500 Hz, which must clamp to 2400 (width
        // - 100), not to a fixed 3000 that would be off-display.
        XCTAssertEqual(WaterfallAxis.tunedTxHz(forFraction: 1.0, displayMaxHz: 2500), 2400, accuracy: 1e-4)
    }

    /// A TX marker's x-fraction must stay on-canvas even when `txFreqHz` is set
    /// externally without clamping (e.g. a WSJT-X UDP reply's raw deltaFreq).
    func testClampedFractionStaysWithinUnitInterval() {
        for width: Float in [2500, 3500, 5000] {
            // Below the band clamps to the left edge.
            XCTAssertEqual(WaterfallAxis.clampedFraction(forHz: -500, displayMaxHz: width), 0, accuracy: 1e-6)
            // Above the display span clamps to the right edge.
            XCTAssertEqual(WaterfallAxis.clampedFraction(forHz: 9999, displayMaxHz: width), 1, accuracy: 1e-6)
            // In-band values pass through unchanged.
            XCTAssertEqual(
                WaterfallAxis.clampedFraction(forHz: 1750, displayMaxHz: width),
                WaterfallAxis.fraction(forHz: 1750, displayMaxHz: width),
                accuracy: 1e-6
            )
            // Sweep a range spanning outside the band; result never leaves [0, 1].
            for hz: Float in [-10_000, -1, 0, 1750, 3500, 4000, 100_000] {
                let f = WaterfallAxis.clampedFraction(forHz: hz, displayMaxHz: width)
                XCTAssertGreaterThanOrEqual(f, 0)
                XCTAssertLessThanOrEqual(f, 1)
            }
        }
    }

    /// Tapping a visible signal keys up on that signal, whatever the width.
    func testTapOnVisibleSignalTunesToIt() {
        for width: Float in [2500, 3000, 3500, 5000] {
            let fraction = WaterfallAxis.fraction(forHz: 2000, displayMaxHz: width)
            XCTAssertEqual(WaterfallAxis.tunedTxHz(forFraction: fraction, displayMaxHz: width), 2000, accuracy: 1e-3)
        }
    }

    // MARK: - Ruler ticks (mirrors Android FrequencyRulerTicksTest, PR #575)

    func testRulerTicksMultipleOf500LastTickSitsAtFarEdge() {
        // 3500 is an exact multiple of the 500 Hz step, so the top tick equals
        // the width and lands at fraction 1.0.
        let ticks = WaterfallAxis.rulerTicks(displayMaxHz: 3500)
        XCTAssertEqual(ticks.map(\.hz), [0, 500, 1000, 1500, 2000, 2500, 3000, 3500])
        XCTAssertEqual(ticks.first!.fraction, 0, accuracy: 1e-6)
        XCTAssertEqual(ticks.last!.fraction, 1, accuracy: 1e-6)
    }

    func testRulerTicksMultipleOf500AreEvenlySpaced() {
        let ticks = WaterfallAxis.rulerTicks(displayMaxHz: 3000)
        let fractions = ticks.map(\.fraction)
        for (a, b) in zip(fractions, fractions.dropFirst()) {
            XCTAssertEqual(b - a, 500.0 / 3000.0, accuracy: 1e-6)
        }
    }

    /// 2750 is NOT a multiple of 500: the highest tick is 2500, which lives at
    /// 2500/2750 ≈ 0.909 of the width, not jammed against the right edge (1.0)
    /// where an even layout would wrongly place it (the Android drift bug,
    /// commit 647b12e8). 2750 Hz itself has no label.
    func testRulerTicksNonMultipleOf500TopTickIsBelowFarEdge() {
        let ticks = WaterfallAxis.rulerTicks(displayMaxHz: 2750)
        XCTAssertEqual(ticks.map(\.hz), [0, 500, 1000, 1500, 2000, 2500])
        XCTAssertEqual(ticks.last!.hz, 2500)
        XCTAssertEqual(ticks.last!.fraction, 2500.0 / 2750.0, accuracy: 1e-6)
        XCTAssertLessThan(ticks.last!.fraction, 1)
    }

    func testRulerTickFractionEqualsHzOverWidth() {
        let width: Float = 4200
        for tick in WaterfallAxis.rulerTicks(displayMaxHz: width) {
            XCTAssertEqual(tick.fraction, Float(tick.hz) / width, accuracy: 1e-6)
            // Ruler ticks use the exact same mapping as the signal overlays.
            XCTAssertEqual(
                tick.fraction,
                WaterfallAxis.fraction(forHz: Float(tick.hz), displayMaxHz: width),
                accuracy: 1e-6
            )
        }
    }

    func testRulerTicksNonPositiveWidthYieldsSingleZeroTick() {
        XCTAssertEqual(WaterfallAxis.rulerTicks(displayMaxHz: 0), [RulerTick(hz: 0, fraction: 0)])
        XCTAssertEqual(WaterfallAxis.rulerTicks(displayMaxHz: -100), [RulerTick(hz: 0, fraction: 0)])
    }
}
