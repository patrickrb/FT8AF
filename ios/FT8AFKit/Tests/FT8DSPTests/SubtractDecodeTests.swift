import XCTest
import CFT8
@testable import FT8DSP

/// Subtract-and-redecode (deep-decode weak-signal recovery): the iOS port of
/// Android's FT8SignalListener subtract loop over ft8af_glue/ft8_subtract.c.
final class SubtractDecodeTests: XCTestCase {

    /// Sum of two GFSK signals (each at its own base freq + linear gain) laid at
    /// the start of a 15 s slot. Gains model relative SNR: a much smaller gain is
    /// a much weaker signal.
    private func mixedSlot(_ specs: [(text: String, freq: Float, gain: Float)]) -> [Float]? {
        var s = [Float](repeating: 0, count: FT8.slotSamples)
        for spec in specs {
            guard let audio = FT8Encoder.generateFT8(spec.text, baseFreqHz: spec.freq) else { return nil }
            for i in 0..<min(audio.count, s.count) { s[i] += audio[i] * spec.gain }
        }
        return s
    }

    /// Goertzel power at one frequency over the whole buffer.
    private func goertzelPower(_ x: [Float], freq: Float, sampleRate: Float) -> Double {
        let w = 2.0 * Double.pi * Double(freq) / Double(sampleRate)
        let coeff = 2.0 * cos(w)
        var s1 = 0.0, s2 = 0.0
        for v in x {
            let s0 = Double(v) + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    /// Total power over the eight FT8 tone bins an audio signal based at `freq`
    /// can occupy (freq .. freq + 7*6.25 Hz) — "energy near the signal".
    private func bandEnergy(_ x: [Float], baseFreq: Float) -> Double {
        var e = 0.0
        for i in 0..<8 {
            e += goertzelPower(x, freq: baseFreq + Float(i) * 6.25, sampleRate: Float(FT8.sampleRate))
        }
        return e
    }

    // MARK: Weak-signal recovery

    /// The headline behaviour: a weak signal overlapping a much stronger one
    /// (here two tone-bins / 12.5 Hz apart, so their spectra overlap) is lost by
    /// a single pass — find_sync locks onto the dominant signal and the louder
    /// message wins the demod — but the subtract-and-redecode loop removes the
    /// strong signal and decodes the weak one from the residual.
    func testSubtractRedecodeSurfacesMaskedSignal() {
        let strong = "CQ K1ABC FN42"
        let weak = "W9XYZ K1ABC RR73"
        // ~12 Hz apart (two 6.25 Hz tone bins), weak ~12 dB down: overlapping
        // spectra, weak masked in a single pass but cleanly separable once the
        // strong signal's full waveform is subtracted.
        guard let s = mixedSlot([(strong, 1500, 0.50), (weak, 1512, 0.12)]) else {
            return XCTFail("encode failed")
        }

        // Single deep pass (no subtraction): should get the strong one only.
        let single = FT8Decoder()
        single.setDeep(true)
        single.feedSlot(s)
        single.findSync()
        let singleTexts = Set(single.decodeAll().map { $0.rawText })
        XCTAssertTrue(singleTexts.contains(strong), "single pass should decode the strong signal: \(singleTexts)")
        XCTAssertFalse(singleTexts.contains(weak),
                       "single pass is expected to MISS the masked weak signal: \(singleTexts)")

        // Full deep decode with the subtract-and-redecode loop: both surface.
        let deep = FT8Decoder()
        deep.setDeep(true)
        deep.feedSlot(s)
        let deepTexts = Set(deep.decodeSlotDeep().map { $0.rawText })
        XCTAssertTrue(deepTexts.contains(strong), "deep decode keeps the strong signal: \(deepTexts)")
        XCTAssertTrue(deepTexts.contains(weak),
                      "subtract-and-redecode should recover the masked weak signal: \(deepTexts)")
    }

    /// The subtraction must actually remove energy from the buffer near the
    /// subtracted signal's frequency (a wrong-phase/amplitude subtraction would
    /// ADD energy and corrupt the residual).
    func testSubtractionReducesEnergyNearFrequency() {
        let text = "CQ K1ABC FN42"
        let f: Float = 1500
        guard let s = mixedSlot([(text, f, 0.5)]) else { return XCTFail("encode failed") }

        let dec = FT8Decoder()
        dec.setDeep(true)
        dec.feedSlot(s)
        dec.findSync()
        guard let m = dec.decodeAll().first(where: { $0.rawText == text }) else {
            return XCTFail("expected to decode \(text)")
        }

        let before = bandEnergy(dec.fedSamples, baseFreq: f)
        XCTAssertTrue(dec.subtractSignalTime(a91: m.a91, freqHz: m.freqHz, timeSec: m.timeSec),
                      "subtraction should apply")
        let after = bandEnergy(dec.fedSamples, baseFreq: f)

        XCTAssertLessThan(after, before * 0.25,
                          "subtraction should cut in-band energy by >6 dB (before=\(before), after=\(after))")
    }

    // MARK: Merge / dedup / SNR upgrade (pure logic)

    /// A repeat decode with a higher SNR upgrades the kept copy; a lower one does
    /// not; and only genuinely new messages are returned.
    func testMergeKeepsHigherSnrAndReturnsOnlyNew() {
        func msg(_ text: String, _ snr: Int) -> DecodedMessage {
            var m = DecodedMessage(); m.rawText = text; m.snr = snr; return m
        }
        var all = [msg("CQ K1ABC FN42", -12), msg("W9XYZ K1ABC RR73", -5)]

        // Same texts (one higher SNR, one lower) + one new message.
        let newMsgs = [msg("CQ K1ABC FN42", -3),   // higher -> upgrade
                       msg("W9XYZ K1ABC RR73", -9), // lower -> keep -5
                       msg("K1ABC W9XYZ 73", -8)]   // new -> append
        let added = mergeDecodes(into: &all, newMsgs)

        XCTAssertEqual(added.map { $0.rawText }, ["K1ABC W9XYZ 73"], "only the new message is returned")
        XCTAssertEqual(all.count, 3)
        XCTAssertEqual(all.first { $0.rawText == "CQ K1ABC FN42" }?.snr, -3, "higher SNR upgrades")
        XCTAssertEqual(all.first { $0.rawText == "W9XYZ K1ABC RR73" }?.snr, -5, "lower SNR does not downgrade")
    }

    // MARK: Termination / budget

    /// The loop must terminate on a pathological (pure-noise) buffer: no infinite
    /// loop, and the result stays under the hard message cap. Bounded by the round
    /// cap regardless of what find_sync keeps manufacturing.
    func testBudgetTerminatesOnPathologicalBuffer() {
        var rng = SystemRandomNumberGenerator()
        var noise = [Float](repeating: 0, count: FT8.slotSamples)
        for i in 0..<noise.count { noise[i] = Float.random(in: -0.3...0.3, using: &rng) }

        let dec = FT8Decoder()
        dec.setDeep(true)
        dec.feedSlot(noise)
        // Returns (does not hang); the round cap guarantees termination.
        let result = dec.decodeSlotDeep()
        XCTAssertLessThanOrEqual(result.count, FT8Decoder.subtractMaxMessages,
                                 "message count stays under the hard cap")
    }

    /// With deep mode OFF, decodeSlotDeep is exactly one pass (no subtraction) —
    /// so it never differs from the plain single-pass path.
    func testDeepOffIsSinglePass() {
        guard let s = mixedSlot([("CQ K1ABC FN42", 1500, 0.5)]) else { return XCTFail("encode") }

        let a = FT8Decoder() // deep off
        a.feedSlot(s)
        let deepOff = Set(a.decodeSlotDeep().map { $0.rawText })

        let b = FT8Decoder()
        b.feedSlot(s)
        b.findSync()
        let single = Set(b.decodeAll().map { $0.rawText })

        XCTAssertEqual(deepOff, single, "deep-off decodeSlotDeep equals a single pass")
    }

    /// The subtract loop re-synthesizes FT8 tones, so it must run for FT8 only.
    /// The `isFT8` flag the gate keys on is set from the initializer; guard the
    /// wiring so an FT4 decoder can't silently start FT8-tone subtraction.
    func testIsFt8FlagGatesSubtraction() {
        XCTAssertTrue(FT8Decoder(isFT8: true).isFT8, "default/FT8 decoder reports FT8")
        XCTAssertFalse(FT8Decoder(isFT8: false).isFT8, "FT4 decoder reports non-FT8")

        // An FT4 deep decoder must run the single pass only (subtract loop gated
        // off), so decodeSlotDeep never diverges from findSync + decodeAll.
        guard let s = mixedSlot([("CQ K1ABC FN42", 1500, 0.5)]) else { return XCTFail("encode") }

        let deep = FT8Decoder(isFT8: false)
        deep.setDeep(true)
        deep.feedSlot(s)
        let ft4Deep = Set(deep.decodeSlotDeep().map { $0.rawText })

        let single = FT8Decoder(isFT8: false)
        single.feedSlot(s)
        single.findSync()
        let ft4Single = Set(single.decodeAll().map { $0.rawText })

        XCTAssertEqual(ft4Deep, ft4Single, "FT4 decodeSlotDeep is a single pass (subtraction gated off)")
    }
}
