import XCTest
import FT8Audio

/// Guards the RX input-level classifier behind the waterfall's level meter
/// (port of the Android `AudioInputLevel` thresholds — the two platforms must
/// judge the same audio identically).
final class AudioInputLevelTests: XCTestCase {

    /// Sine buffer of a given peak amplitude; RMS = amplitude / sqrt(2).
    private func sine(amplitude: Float, count: Int = 3000) -> [Float] {
        (0..<count).map { amplitude * sin(2 * .pi * 20 * Float($0) / Float(count)) }
    }

    func testEmptyBufferIsSilent() {
        let r = AudioInputLevel.measure([])
        XCTAssertEqual(r.status, .silent)
        XCTAssertEqual(r.peak, 0)
        XCTAssertEqual(r.rms, 0)
        XCTAssertEqual(r.peakDbfs, AudioInputLevel.minDbfs)
        XCTAssertEqual(r.rmsDbfs, AudioInputLevel.minDbfs)
    }

    func testDigitalSilenceIsSilent() {
        let r = AudioInputLevel.measure([Float](repeating: 0, count: 3000))
        XCTAssertEqual(r.status, .silent)
        XCTAssertEqual(r.peakDbfs, AudioInputLevel.minDbfs)
    }

    func testFaintNoiseBelowSilentThresholdIsSilent() {
        // Amplitude 1e-5 → RMS ≈ -103 dBFS, well below the -75 dBFS floor.
        let r = AudioInputLevel.measure(sine(amplitude: 1e-5))
        XCTAssertEqual(r.status, .silent)
    }

    func testQuietSignalIsLow() {
        // Amplitude 0.005 → RMS ≈ -49 dBFS: audible but under the -45 dBFS
        // healthy-window floor.
        let r = AudioInputLevel.measure(sine(amplitude: 0.005))
        XCTAssertEqual(r.status, .low)
    }

    func testHealthySignalIsGood() {
        // Amplitude 0.1 → RMS ≈ -23 dBFS, peak -20 dBFS: inside the window.
        let r = AudioInputLevel.measure(sine(amplitude: 0.1))
        XCTAssertEqual(r.status, .good)
    }

    func testHotPeaksAreHigh() {
        // Amplitude 0.9 → peak ≈ -0.9 dBFS (≥ -3 dBFS) but below the 0.985
        // linear clip threshold.
        let r = AudioInputLevel.measure(sine(amplitude: 0.9))
        XCTAssertEqual(r.status, .high)
    }

    func testFullScalePeaksAreClipping() {
        let r = AudioInputLevel.measure(sine(amplitude: 1.0))
        XCTAssertEqual(r.status, .clipping)
        XCTAssertEqual(r.peak, 1.0, accuracy: 1e-3)
    }

    func testMeasureReportsPeakAndRms() {
        let r = AudioInputLevel.measure(sine(amplitude: 0.5, count: 12_000))
        XCTAssertEqual(r.peak, 0.5, accuracy: 1e-3)
        XCTAssertEqual(r.rms, 0.5 / sqrt(2), accuracy: 1e-2)
        XCTAssertEqual(r.peakDbfs, 20 * log10(0.5), accuracy: 0.1)
    }

    func testFromPeakRmsThresholdEdges() {
        // Exactly at the clip threshold → clipping.
        XCTAssertEqual(AudioInputLevel.fromPeakRms(peak: 0.985, rms: 0.3).status, .clipping)
        // Just under clip but at/above -3 dBFS peak → high.
        XCTAssertEqual(AudioInputLevel.fromPeakRms(peak: 0.984, rms: 0.3).status, .high)
        // Peak below -3 dBFS with healthy RMS → good.
        XCTAssertEqual(AudioInputLevel.fromPeakRms(peak: 0.5, rms: 0.1).status, .good)
    }

    func testNonFiniteInputsSanitizedToSilence() {
        for r in [
            AudioInputLevel.fromPeakRms(peak: .nan, rms: .nan),
            AudioInputLevel.fromPeakRms(peak: .infinity, rms: 0.1),
        ] {
            XCTAssertTrue(r.peak.isFinite)
            XCTAssertTrue(r.rms.isFinite)
            XCTAssertTrue(r.peakDbfs.isFinite)
        }
        XCTAssertEqual(AudioInputLevel.fromPeakRms(peak: .nan, rms: .nan).status, .silent)
    }

    func testNegativePeakSanitized() {
        let r = AudioInputLevel.fromPeakRms(peak: -0.5, rms: -0.5)
        XCTAssertEqual(r.peak, 0)
        XCTAssertEqual(r.status, .silent)
    }

    func testMeterFractionClampsToUnitInterval() {
        XCTAssertEqual(AudioInputLevel.meterFraction(-0.5), 0)
        XCTAssertEqual(AudioInputLevel.meterFraction(0), 0)
        XCTAssertEqual(AudioInputLevel.meterFraction(0.37), 0.37, accuracy: 1e-6)
        XCTAssertEqual(AudioInputLevel.meterFraction(1), 1)
        XCTAssertEqual(AudioInputLevel.meterFraction(2.5), 1)
        XCTAssertEqual(AudioInputLevel.meterFraction(.nan), 0)
    }

    func testSilenceConstantClassifiesSilent() {
        XCTAssertEqual(AudioInputLevel.silence.status, .silent)
        XCTAssertEqual(AudioInputLevel.silence, AudioInputLevel.measure([]))
    }
}
