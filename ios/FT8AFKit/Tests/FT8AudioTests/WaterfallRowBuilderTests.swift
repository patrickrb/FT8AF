import XCTest
import Foundation
import FT8Audio

final class WaterfallRowBuilderTests: XCTestCase {

    /// Inverse of the builder's power->dB step: the summed power that yields a
    /// given per-bin dB magnitude, so tests can specify spectra in dB.
    private func summedPower(forDb db: Float) -> Float {
        let mag = Float(pow(10.0, Double(db) / 20.0))
        let s = mag * Float(WaterfallRowBuilder.fftSize) / 2.0 // == sqrt(avg power)
        return Float(WaterfallRowBuilder.segments) * s * s
    }

    func testGeometry() {
        XCTAssertEqual(WaterfallRowBuilder.binHz(sampleRate: 12_000), 12_000.0 / 2_048.0, accuracy: 1e-4)
        XCTAssertEqual(WaterfallRowBuilder.columns(sampleRate: 12_000), 597) // 3500 / 5.859 Hz, < Nyquist half
        XCTAssertEqual(WaterfallRowBuilder.samplesNeeded(), 2_048 + 5 * 1_024)
    }

    func testEmptySpectrumYieldsEmptyRow() {
        var b = WaterfallRowBuilder()
        let row = b.buildRow(summedPower: [], sampleRate: 12_000)
        XCTAssertTrue(row.bins.isEmpty)
        XCTAssertEqual(row.hzPerCol, 12_000.0 / 2_048.0, accuracy: 1e-4)
    }

    func testFloorEmaStepsTowardPercentile() {
        var b = WaterfallRowBuilder(floorDb: 0)
        let spec = [Float](repeating: summedPower(forDb: -50), count: 50)
        _ = b.buildRow(summedPower: spec, sampleRate: 12_000)
        // One EMA step from 0 toward the -50 dB 30th-percentile floor: 0.9*0 + 0.1*(-50).
        XCTAssertEqual(b.floorDb, -5, accuracy: 0.3)
    }

    func testBrightnessMappingOnceFloorSettles() {
        var b = WaterfallRowBuilder()
        let cols = 100
        var spec = [Float](repeating: summedPower(forDb: -60), count: cols) // noise floor
        spec[10] = summedPower(forDb: -25) // strong signal (above top of span)
        spec[20] = summedPower(forDb: -43) // mid signal (~half brightness)

        // Let the noise-floor EMA converge onto -60 dB.
        var row = WaterfallRow(bins: [], hzPerCol: 0)
        for _ in 0..<200 { row = b.buildRow(summedPower: spec, sampleRate: 12_000) }

        XCTAssertEqual(b.floorDb, -60, accuracy: 0.5)
        // black point = floor + 4 = -56 dB; span 26 dB -> full bright at -30 dB.
        XCTAssertEqual(row.bins[0], 0, "noise floor renders dark")
        XCTAssertEqual(row.bins[10], 255, "strong signal saturates")
        XCTAssertEqual(Int(row.bins[20]), 127, accuracy: 4, "-43 dB ~ mid brightness")
        XCTAssertEqual(row.hzPerCol, 12_000.0 / 2_048.0, accuracy: 1e-4)
    }

    func testColumnsClampToNyquistHalfAtHighRates() {
        // A very high rate would put maxHz within the first few bins; columns must
        // still be >= 1 and never exceed fftSize/2.
        XCTAssertGreaterThanOrEqual(WaterfallRowBuilder.columns(sampleRate: 48_000), 1)
        XCTAssertLessThanOrEqual(WaterfallRowBuilder.columns(sampleRate: 48_000), WaterfallRowBuilder.fftSize / 2)
    }
}
