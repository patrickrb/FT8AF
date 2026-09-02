import XCTest
@testable import FT8Engine

final class TuneToneTests: XCTestCase {

    func testSampleCountMatchesDuration() {
        let s = TuneTone.samples(freqHz: 1500, sampleRate: 12_000, durationSec: 3)
        XCTAssertEqual(s.count, 36_000)
    }

    func testDegenerateInputsReturnEmpty() {
        XCTAssertTrue(TuneTone.samples(freqHz: 1500, sampleRate: 12_000, durationSec: 0).isEmpty)
        XCTAssertTrue(TuneTone.samples(freqHz: 1500, sampleRate: 0, durationSec: 5).isEmpty)
        XCTAssertTrue(TuneTone.samples(freqHz: 0, sampleRate: 12_000, durationSec: 5).isEmpty)
        XCTAssertTrue(TuneTone.samples(freqHz: -100, sampleRate: 12_000, durationSec: 5).isEmpty)
    }

    func testAmplitudeBoundedAndReachedInSteadyState() {
        let amp: Float = 0.9
        let s = TuneTone.samples(freqHz: 1000, sampleRate: 12_000, durationSec: 1, amplitude: amp)
        let peak = s.map(abs).max() ?? 0
        XCTAssertLessThanOrEqual(peak, amp + 1e-4)
        XCTAssertGreaterThan(peak, amp * 0.99) // full amplitude reached mid-buffer
    }

    func testRampsAvoidKeyClicks() {
        let s = TuneTone.samples(freqHz: 1500, sampleRate: 12_000, durationSec: 1)
        // Starts and ends at (near) zero.
        XCTAssertEqual(s.first ?? 1, 0, accuracy: 1e-6)
        XCTAssertEqual(abs(s.last ?? 1), 0, accuracy: 0.01)
        // Every sample inside the head ramp is attenuated below full scale.
        let rampSamples = 12_000 * TuneTone.rampMs / 1000
        let headPeak = s[0..<rampSamples].map(abs).max() ?? 1
        XCTAssertLessThan(headPeak, 0.9)
    }

    func testFrequencyViaZeroCrossings() {
        let sr = 12_000
        let s = TuneTone.samples(freqHz: 1500, sampleRate: sr, durationSec: 1)
        var crossings = 0
        for i in 1..<s.count where (s[i - 1] < 0) != (s[i] < 0) {
            crossings += 1
        }
        // A 1500 Hz tone over 1 s has ~3000 zero crossings (2 per period).
        XCTAssertEqual(crossings, 3000, accuracy: 10)
    }

    func testChipLabel() {
        XCTAssertEqual(TuneTone.chipLabel(isTuning: false, remainingSec: 30), "TUNE")
        XCTAssertEqual(TuneTone.chipLabel(isTuning: true, remainingSec: 7), "TUNE 7s")
        XCTAssertEqual(TuneTone.chipLabel(isTuning: true, remainingSec: -2), "TUNE 0s")
    }
}

private func XCTAssertEqual(_ value: Int, _ expected: Int, accuracy: Int,
                            file: StaticString = #filePath, line: UInt = #line) {
    XCTAssertLessThanOrEqual(abs(value - expected), accuracy,
                             "\(value) not within \(accuracy) of \(expected)",
                             file: file, line: line)
}
