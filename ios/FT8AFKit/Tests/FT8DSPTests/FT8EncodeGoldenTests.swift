import XCTest
import CFT8
@testable import FT8DSP

/// Layer (A): golden snapshots. pack77(message) and ft8_encode(payload) must
/// reproduce the frozen vectors bit-for-bit, and the Costas sync arrays must sit
/// at 0-6 / 36-42 / 72-78. Mirrors test_golden_encode.c layer A.
final class FT8EncodeGoldenTests: XCTestCase {

    func testPack77MatchesGoldenPayload() {
        for (k, msg) in Golden.msgs.enumerated() {
            guard let got = FT8Encoder.pack77(msg) else {
                XCTFail("[\(msg)] pack77 returned nil (rejected)")
                continue
            }
            XCTAssertTrue(Golden.payload77Eq(got, Golden.payload[k]),
                          "[\(msg)] payload mismatch vs golden: \(got) != \(Golden.payload[k])")
        }
    }

    func testEncodeTonesMatchGoldenAndCostas() {
        for (k, msg) in Golden.msgs.enumerated() {
            let tones = FT8Encoder.encodeTones(Golden.payload[k])
            XCTAssertEqual(tones, Golden.tones[k], "[\(msg)] tone sequence mismatch vs golden")

            // Costas sync arrays must equal kFT8_Costas_pattern at all three groups.
            withUnsafeBytes(of: kFT8_Costas_pattern) { costas in
                for s in 0..<7 {
                    let expect = costas[s]
                    XCTAssertEqual(tones[s], expect, "[\(msg)] Costas group 0 symbol \(s)")
                    XCTAssertEqual(tones[36 + s], expect, "[\(msg)] Costas group 1 symbol \(s)")
                    XCTAssertEqual(tones[72 + s], expect, "[\(msg)] Costas group 2 symbol \(s)")
                }
            }
        }
    }

    /// The full text -> tones chain (pack then encode) also lands on golden.
    func testTextToTonesChain() {
        for (k, msg) in Golden.msgs.enumerated() {
            guard let payload = FT8Encoder.pack77(msg) else {
                XCTFail("[\(msg)] pack77 nil"); continue
            }
            XCTAssertEqual(FT8Encoder.encodeTones(payload), Golden.tones[k], "[\(msg)] chain")
        }
    }

    /// generateFT8 yields the right number of bounded, non-silent samples.
    func testGenerateFT8ProducesAudio() {
        guard let sig = FT8Encoder.generateFT8("CQ K1ABC FN42", baseFreqHz: 1000) else {
            return XCTFail("generateFT8 returned nil")
        }
        let expected = Int(0.5 + Float(FT8.nn) * FT8.symbolPeriod * Float(FT8.sampleRate))
        XCTAssertEqual(sig.count, expected, "sample count (12.64 s @ 12 kHz)")
        XCTAssertTrue(sig.contains { $0 != 0 }, "waveform must not be silent")
        XCTAssertTrue(sig.allSatisfy { abs($0) <= 1.0001 }, "amplitude within [-1, 1]")
    }

    /// Regression: the TX buffer must be sized by the SAME per-symbol rounding
    /// synthGFSK writes, at EVERY sample rate — not the total-rounded
    /// `round(nn * symbolPeriod * rate)`, which underruns the write count at a
    /// non-integral rate (e.g. 12004 Hz) and tripped synthGFSK's length
    /// precondition. iOS twin of the Android GenerateFT8 fix (#521) and desktop
    /// `encode.rs::waveform_len` (#551).
    func testGenerateFT8BufferMatchesSynthWriteCountAcrossRates() {
        // Multiples of 25 (rate*0.16 integral) stay byte-identical; the rest
        // diverge — 12004/12005 are the cases where the OLD total-rounded size was
        // SMALLER than the write count (the trap), 12001 where it was larger.
        for rate: Int32 in [12_000, 48_000, 44_100, 12_001, 12_004, 12_005, 16_003] {
            let nSpsym = Int(0.5 + Double(rate) * Double(FT8.symbolPeriod))
            let nWave = FT8.nn * nSpsym
            XCTAssertEqual(FT8Encoder.waveformSampleCount(nTones: FT8.nn, sampleRate: rate), nWave,
                           "rate \(rate): helper must equal per-symbol write count")
            guard let sig = FT8Encoder.generateFT8("CQ K1ABC FN42", baseFreqHz: 1000, sampleRate: rate) else {
                return XCTFail("generateFT8 nil at rate \(rate)")
            }
            // Buffer is exactly the write count → synthGFSK's precondition can't trap.
            XCTAssertEqual(sig.count, nWave,
                           "rate \(rate): buffer must equal synthGFSK write count")
        }
        // Default (12 kHz) path is unchanged from the historical total-rounded size.
        let legacy12k = Int(0.5 + Float(FT8.nn) * FT8.symbolPeriod * Float(FT8.sampleRate))
        XCTAssertEqual(FT8Encoder.waveformSampleCount(nTones: FT8.nn, sampleRate: FT8.sampleRate),
                       legacy12k, "12 kHz size must match the legacy formula (byte-identical)")
        // Document the defect the fix removes: at 12004 Hz the OLD total-rounded
        // size was strictly smaller than what synthGFSK writes.
        let oldTotalRounded12004 = Int(0.5 + Float(FT8.nn) * FT8.symbolPeriod * Float(12_004))
        XCTAssertLessThan(oldTotalRounded12004,
                          FT8Encoder.waveformSampleCount(nTones: FT8.nn, sampleRate: 12_004),
                          "old total-rounded size underran the write count at 12004 Hz")
    }

    /// synthGFSK honors a non-zero offset: it writes the waveform into
    /// signal[offset...] and leaves the leading region untouched (the offset
    /// code path the engine uses to place a frame later in a buffer).
    func testSynthGFSKWritesAtOffsetLeavingPrefixUntouched() {
        let tones = FT8Encoder.encodeTones(Golden.payload[0])
        let nSpsym = Int(0.5 + Double(FT8.sampleRate) * Double(FT8.symbolPeriod))
        let nWave = tones.count * nSpsym
        let offset = 1000
        var signal = [Float](repeating: 0, count: offset + nWave)
        FT8Encoder.synthGFSK(tones: tones, f0: 1000, sampleRate: FT8.sampleRate,
                             into: &signal, offset: offset)
        XCTAssertTrue(signal[0..<offset].allSatisfy { $0 == 0 },
                      "samples before offset must stay silent")
        XCTAssertTrue(signal[offset..<(offset + nWave)].contains { $0 != 0 },
                      "waveform region must be non-silent")
        XCTAssertTrue(signal.allSatisfy { abs($0) <= 1.0001 }, "amplitude within [-1, 1]")
    }

    // Note: this ft8_lib's pack77 always returns 0 — it falls back to free-text
    // packing (packtext77) for anything not a structured message — so
    // FT8Encoder.pack77 never returns nil here. The Optional is kept for safety
    // and parity with desktop encode.rs, but there is no reachable nil path to test.
}
