import XCTest
@testable import FT8DSP

/// Phase 1 gate: prove the from-source decode path works end to end by encoding
/// a message to audio, feeding it through the monitor, and decoding it back —
/// the in-memory round trip (mirror desktop's encode->decode cross-check).
final class FT8DecoderTests: XCTestCase {

    /// Lay a 12.64 s FT8 waveform at the start of a 15 s slot, scaled by `gain`.
    private func slot(for text: String, baseFreqHz: Float, gain: Float = 0.5) -> [Float]? {
        guard let audio = FT8Encoder.generateFT8(text, baseFreqHz: baseFreqHz) else { return nil }
        var s = [Float](repeating: 0, count: FT8.slotSamples)
        for i in 0..<min(audio.count, s.count) { s[i] += audio[i] * gain }
        return s
    }

    func testEncodeThenDecodeRoundTrip() {
        let text = "CQ K1ABC FN42"
        let baseFreq: Float = 1500
        guard let s = slot(for: text, baseFreqHz: baseFreq) else { return XCTFail("encode failed") }

        let dec = FT8Decoder()
        dec.feedSlot(s)
        dec.findSync()
        let msgs = dec.decodeAll()

        guard let m = msgs.first(where: { $0.rawText == text }) else {
            return XCTFail("expected \"\(text)\", got \(msgs.map { $0.rawText })")
        }
        XCTAssertEqual(m.callTo, "CQ")
        XCTAssertEqual(m.callFrom, "K1ABC")
        XCTAssertEqual(m.grid, "FN42")
        XCTAssertEqual(m.freqHz, baseFreq, accuracy: 6.25, "frequency within one tone bin")
        XCTAssertEqual(m.timeSec, 0, accuracy: 0.2, "signal placed at slot start -> DT ~ 0")
        XCTAssertGreaterThan(m.snr, -10, "clean synthetic signal should report decent SNR")
    }

    /// Two signals at different audio frequencies decode from one slot (exercises
    /// multi-candidate find_sync + dedup). Doubles as the "known slot" decode.
    func testDecodesMultipleSignalsInOneSlot() {
        let specs: [(text: String, freq: Float)] = [
            ("CQ K1ABC FN42", 800),
            ("W9XYZ K1ABC RR73", 1600),
        ]
        var s = [Float](repeating: 0, count: FT8.slotSamples)
        for spec in specs {
            guard let audio = FT8Encoder.generateFT8(spec.text, baseFreqHz: spec.freq) else {
                return XCTFail("encode failed for \(spec.text)")
            }
            for i in 0..<min(audio.count, s.count) { s[i] += audio[i] * 0.4 }
        }

        let dec = FT8Decoder()
        dec.feedSlot(s)
        dec.findSync()
        let texts = Set(dec.decodeAll().map { $0.rawText })
        for spec in specs {
            XCTAssertTrue(texts.contains(spec.text), "missing \"\(spec.text)\" in \(texts)")
        }
    }

    /// An empty slot yields no decodes (no false positives from silence).
    func testSilentSlotDecodesNothing() {
        let dec = FT8Decoder()
        dec.feedSlot([Float](repeating: 0, count: FT8.slotSamples))
        dec.findSync()
        XCTAssertTrue(dec.decodeAll().isEmpty)
    }

    /// feedSlot populates the waterfall so a heatmap can be built (Phase 2 UI).
    func testWaterfallHeatmapPopulatedAfterFeed() {
        guard let s = slot(for: "CQ K1ABC FN42", baseFreqHz: 1500) else { return XCTFail("encode") }
        let dec = FT8Decoder()
        dec.feedSlot(s)
        let wf = dec.waterfallHeatmap(maxRows: 60, maxCols: 120)
        XCTAssertGreaterThan(wf.rows, 0)
        XCTAssertGreaterThan(wf.cols, 0)
        XCTAssertEqual(wf.bins.count, wf.rows * wf.cols)
        XCTAssertGreaterThan(wf.hzPerCol, 0)
        XCTAssertTrue(wf.bins.contains { $0 > 0 }, "signal should light up some bins")
    }
}

/// Pure-logic tests for the hash table + helpers (no monitor needed).
final class HashTableTests: XCTestCase {
    func testSaveAndLookupAcrossWidths() {
        let t = HashTable()
        let n22 = FT8Hash.n22("PJ4/K1ABC") & 0x3F_FFFF
        t.save("PJ4/K1ABC", n22)
        XCTAssertEqual(t.lookup(shift: 0, hash: n22), "PJ4/K1ABC")
        XCTAssertEqual(t.lookup(shift: 10, hash: n22 >> 10), "PJ4/K1ABC")
        XCTAssertEqual(t.lookup(shift: 12, hash: n22 >> 12), "PJ4/K1ABC")
        XCTAssertNil(t.lookup(shift: 0, hash: 0x1_2345))
    }

    func testBracketedCallsignNotStored() {
        let t = HashTable()
        t.save("<W9XYZ>", FT8Hash.n22("W9XYZ"))
        XCTAssertNil(t.lookup(shift: 0, hash: FT8Hash.n22("W9XYZ") & 0x3F_FFFF))
    }

    func testClearEmptiesTable() {
        let t = HashTable()
        let n22 = FT8Hash.n22("K1ABC") & 0x3F_FFFF
        t.save("K1ABC", n22)
        t.clear()
        XCTAssertNil(t.lookup(shift: 0, hash: n22))
    }

    func testLooksLikeGrid() {
        XCTAssertTrue(looksLikeGrid("FN42"))
        XCTAssertTrue(looksLikeGrid("AA00"))
        XCTAssertTrue(looksLikeGrid("RR99"))
        XCTAssertFalse(looksLikeGrid("FN4"))   // too short
        XCTAssertFalse(looksLikeGrid("FN420")) // too long
        XCTAssertFalse(looksLikeGrid("SN42"))  // S > R
        XCTAssertFalse(looksLikeGrid("F142"))  // second char not a letter
        XCTAssertFalse(looksLikeGrid("FNAB"))  // last two not digits
    }
}
