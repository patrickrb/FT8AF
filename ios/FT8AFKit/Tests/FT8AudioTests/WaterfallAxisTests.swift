import XCTest
import Foundation
import FT8Audio

final class WaterfallAxisTests: XCTestCase {

    func testDisplaySpanMatchesDataTop() {
        // The overlay axis must span the same range as the waterfall data, i.e.
        // the top of the displayed band the builder is configured for.
        XCTAssertEqual(WaterfallAxis.displayMaxHz, WaterfallRowBuilder.maxHz, accuracy: 1e-6)
        XCTAssertEqual(WaterfallAxis.displayMaxHz, 3500, accuracy: 1e-6)
    }

    func testFractionMapsAcrossFullDataSpan() {
        XCTAssertEqual(WaterfallAxis.fraction(forHz: 0), 0, accuracy: 1e-6)
        XCTAssertEqual(WaterfallAxis.fraction(forHz: 1750), 0.5, accuracy: 1e-6)
        XCTAssertEqual(WaterfallAxis.fraction(forHz: 3500), 1.0, accuracy: 1e-6)
    }

    func testFractionAndHzAreInverse() {
        for hz: Float in [0, 100, 733, 1500, 2400, 3200, 3500] {
            let round = WaterfallAxis.hz(forFraction: WaterfallAxis.fraction(forHz: hz))
            XCTAssertEqual(round, hz, accuracy: 1e-3)
        }
    }

    /// Regression: the overlays used to place a 1500 Hz signal at fraction 0.5
    /// (a 3000 Hz span) while the data draws it at 1500/3500 ≈ 0.4286. The marker
    /// now lands on the trace, not ~17% to its right.
    func testSignalNoLongerMisplacedByOldThreeThousandConstant() {
        let f = WaterfallAxis.fraction(forHz: 1500)
        XCTAssertEqual(f, 1500.0 / 3500.0, accuracy: 1e-6)
        XCTAssertNotEqual(f, 1500.0 / 3000.0, accuracy: 1e-3) // the old, wrong mapping
    }

    /// The overlay axis lines up with the actual columns the builder emits:
    /// x == width corresponds to `columns * binHz`, which is within one bin of
    /// `displayMaxHz`.
    func testAxisAgreesWithActualColumnSpan() {
        for sr in [12_000, 24_000, 48_000] {
            let bin = WaterfallRowBuilder.binHz(sampleRate: sr)
            let dataTop = Float(WaterfallRowBuilder.columns(sampleRate: sr)) * bin
            XCTAssertEqual(dataTop, WaterfallAxis.displayMaxHz, accuracy: bin)
        }
    }

    func testTapToTuneClampsToTxPassband() {
        // Middle of the strip tunes to the frequency under the finger.
        XCTAssertEqual(WaterfallAxis.tunedTxHz(forFraction: 0.5), 1750, accuracy: 1e-4)
        // Far left clamps up to the minimum TX offset.
        XCTAssertEqual(WaterfallAxis.tunedTxHz(forFraction: 0.0), WaterfallAxis.minTxHz, accuracy: 1e-4)
        // Far right (3500 Hz) clamps down to the SSB passband ceiling, not 3500.
        XCTAssertEqual(WaterfallAxis.tunedTxHz(forFraction: 1.0), WaterfallAxis.maxTxHz, accuracy: 1e-4)
        XCTAssertEqual(WaterfallAxis.maxTxHz, 3000, accuracy: 1e-6)
    }

    /// Tapping a visible signal keys up on that signal, not ~17% low as before.
    func testTapOnVisibleSignalTunesToIt() {
        // A 2000 Hz signal is drawn at fraction 2000/3500.
        let fraction = WaterfallAxis.fraction(forHz: 2000)
        XCTAssertEqual(WaterfallAxis.tunedTxHz(forFraction: fraction), 2000, accuracy: 1e-3)
    }
}
