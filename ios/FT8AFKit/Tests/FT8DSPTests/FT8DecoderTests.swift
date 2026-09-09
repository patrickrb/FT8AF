import XCTest
import CFT8
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

    /// A long-lived decoder must stay correct across repeated feedSlot calls
    /// (no stale monitor/candidate state corrupting later slots).
    func testDecoderReusableAcrossSlots() {
        guard let s = slot(for: "CQ K1ABC FN42", baseFreqHz: 1200) else { return XCTFail("encode") }
        let dec = FT8Decoder()
        dec.feedSlot(s); dec.findSync()
        let first = Set(dec.decodeAll().map { $0.rawText })
        dec.feedSlot(s); dec.findSync()
        let second = Set(dec.decodeAll().map { $0.rawText })
        XCTAssertEqual(first, second)
        XCTAssertTrue(first.contains("CQ K1ABC FN42"))
    }

    /// A hashed compound call (`<...>`) must resolve against a full call heard in
    /// an EARLIER slot when the decoders share one persistent hash table — the
    /// cross-slot resolution LiveEngine relies on (a fresh decoder per slot).
    func testHashedCallResolvesAcrossSlots() {
        // Slot 1: `CQ PJ4/K1ABC` as a genuine Type 4 frame carries PJ4/K1ABC in
        // full (58-bit), so decoding it saves the call's hash into the table.
        guard let s1 = nonstdSlot(callTo: "CQ", callDe: "PJ4/K1ABC", baseFreqHz: 1500) else {
            return XCTFail("encode s1")
        }
        // Slot 2: `PJ4/K1ABC W9XYZ` sends PJ4/K1ABC only as a 12-bit hash, so it
        // renders `<...>` unless that hash is already known.
        guard let s2 = nonstdSlot(callTo: "PJ4/K1ABC", callDe: "W9XYZ", baseFreqHz: 1500) else {
            return XCTFail("encode s2")
        }

        let shared = HashTable()

        // Fresh decoder per slot (as LiveEngine builds), sharing one table.
        let d1 = FT8Decoder(hashTable: shared)
        d1.feedSlot(s1); d1.findSync()
        let m1 = d1.decodeAll().map { $0.rawText.trimmingCharacters(in: .whitespaces) }
        XCTAssertTrue(m1.contains("CQ PJ4/K1ABC"), "slot 1 should decode the defining call: \(m1)")

        let d2 = FT8Decoder(hashTable: shared)
        d2.feedSlot(s2); d2.findSync()
        let m2 = d2.decodeAll().map { $0.rawText.trimmingCharacters(in: .whitespaces) }
        // The hash saved in slot 1 now resolves in slot 2 -> full call rendered as
        // `<PJ4/K1ABC>` (brackets mark a resolved-from-hash call), not `<...>`.
        XCTAssertTrue(m2.contains { $0.contains("<PJ4/K1ABC>") }, "slot 2 should resolve earlier hash: \(m2)")
        XCTAssertFalse(m2.contains { $0.contains("<...>") }, "no unresolved placeholder expected: \(m2)")
    }

    /// The same hashed-call slot stays UNresolved when the table never saw the
    /// defining call — proving resolution comes from the persistent store, not
    /// something intrinsic to the frame.
    func testHashedCallUnresolvedWhenNeverSeen() {
        guard let s2 = nonstdSlot(callTo: "PJ4/K1ABC", callDe: "W9XYZ", baseFreqHz: 1500) else {
            return XCTFail("encode s2")
        }
        let fresh = HashTable() // never saw the defining call
        let d = FT8Decoder(hashTable: fresh)
        d.feedSlot(s2); d.findSync()
        let m = d.decodeAll().map(\.rawText)
        XCTAssertTrue(m.contains { $0.contains("<...>") }, "unknown hash should render <...>: \(m)")
    }

    /// Lay a genuine Type 4 (nonstandard/hashed-call) FT8 frame at the start of a
    /// 15 s slot. The legacy `pack77` path can't emit Type 4, so we drive
    /// `ftx_message_encode_nonstd` directly (as WSJT-X does over the air).
    private func nonstdSlot(callTo: String, callDe: String, extra: String = "",
                            baseFreqHz: Float, gain: Float = 0.5) -> [Float]? {
        var msg = ftx_message_t()
        var iface = makeHashInterface()
        // encode_nonstd hashes the compound call through the interface; give the
        // save callback a throwaway active table to write into.
        let scratch = HashTable()
        installActiveHashTable(scratch)
        defer { clearActiveHashTable() }
        let rc = callTo.withCString { ct in
            callDe.withCString { cd in
                extra.withCString { ex in
                    ftx_message_encode_nonstd(&msg, &iface, ct, cd, ex)
                }
            }
        }
        guard rc == FTX_MESSAGE_RC_OK else { return nil }
        var payload = [UInt8](repeating: 0, count: 10)
        withUnsafeBytes(of: msg.payload) { raw in for i in 0..<10 { payload[i] = raw[i] } }
        let tones = FT8Encoder.encodeTones(payload)
        var s = [Float](repeating: 0, count: FT8.slotSamples)
        FT8Encoder.synthGFSK(tones: tones, f0: baseFreqHz, sampleRate: FT8.sampleRate, into: &s, offset: 0)
        for i in 0..<s.count { s[i] *= gain }
        return s
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

    /// setDeep is the only knob the deep-decode setting turns: it raises the
    /// LDPC iteration cap (20 -> 30). Assert that observable effect so the
    /// LiveEngine wiring (`decoder.setDeep(settings.deepDecode)`) has a defined,
    /// tested contract.
    func testSetDeepRaisesLdpcIterations() {
        let dec = FT8Decoder()
        XCTAssertEqual(dec.ldpcIterations, FT8Decoder.ldpcItersNormal, "defaults to normal")
        XCTAssertFalse(dec.isDeep)

        dec.setDeep(true)
        XCTAssertEqual(dec.ldpcIterations, FT8Decoder.ldpcItersDeep)
        XCTAssertEqual(dec.ldpcIterations, 30)
        XCTAssertTrue(dec.isDeep)

        dec.setDeep(false)
        XCTAssertEqual(dec.ldpcIterations, FT8Decoder.ldpcItersNormal)
        XCTAssertEqual(dec.ldpcIterations, 20)
        XCTAssertFalse(dec.isDeep)
    }

    /// Deep decode must still decode a clean signal (more iterations is a
    /// superset of the normal effort — it never regresses an easy decode).
    func testDeepDecodeStillDecodesCleanSignal() {
        let text = "CQ K1ABC FN42"
        guard let s = slot(for: text, baseFreqHz: 1500) else { return XCTFail("encode failed") }
        let dec = FT8Decoder()
        dec.setDeep(true)
        dec.feedSlot(s)
        dec.findSync()
        XCTAssertTrue(dec.decodeAll().contains { $0.rawText == text })
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

    /// Saving past capacity must stay bounded and evict oldest-first (FIFO):
    /// the newest entries survive, the oldest are dropped, and lookups never hang.
    func testBoundedGrowthEvictsOldest() {
        let t = HashTable()
        let overflow = 10
        let total = HashTable.capacity + overflow
        for i in 1...total {
            t.save("C\(i)", UInt32(i) & 0x3F_FFFF) // distinct non-zero hashes
        }
        // Newest survives; first `overflow` entries were evicted.
        XCTAssertEqual(t.lookup(shift: 0, hash: UInt32(total)), "C\(total)")
        XCTAssertNil(t.lookup(shift: 0, hash: 1), "oldest should be evicted")
        XCTAssertNil(t.lookup(shift: 0, hash: UInt32(overflow)), "oldest window evicted")
        // First entry that should still fit under the cap.
        XCTAssertEqual(t.lookup(shift: 0, hash: UInt32(overflow + 1)), "C\(overflow + 1)")
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

/// Junk / false-decode rejection (ported from Android isPlausibleCallsign /
/// isJunkDecode).
final class JunkFilterTests: XCTestCase {
    func testPlausibleCallsignsAccepted() {
        // Standard, portable, compound, and hashed forms are all plausible.
        for c in ["W1AW", "K1ABC", "K1ABC/P", "VE3/K1ABC", "G4ABC/P", "PJ4/K1ABC",
                  "<...>", "<PJ4/K1ABC>"] {
            XCTAssertTrue(isPlausibleCallsign(c), "\(c) should be plausible")
        }
    }

    func testJunkCallsignsRejected() {
        // CRC-collision garbage: stray brackets, spaces, dots.
        for c in [".<. >", "<>", "< >", "K1 ABC", "K1.ABC", "", "   ", "<<K1ABC>>"] {
            XCTAssertFalse(isPlausibleCallsign(c), "\(c) should be rejected")
        }
    }

    func testJunkDecodeExemptsFreeTextAndTelemetry() {
        // Free text (i3=0,n3=0) and telemetry (i3=0,n3=5) legitimately carry
        // non-callsign text in the sender field — never junk.
        XCTAssertFalse(isJunkDecode(i3: 0, n3: 0, callFrom: "TNX 73 GL"))
        XCTAssertFalse(isJunkDecode(i3: 0, n3: 5, callFrom: "123456789ABCDE"))
    }

    func testJunkDecodeDropsImplausibleSender() {
        XCTAssertTrue(isJunkDecode(i3: 1, n3: 0, callFrom: ".<. >"))
        XCTAssertFalse(isJunkDecode(i3: 1, n3: 0, callFrom: "K1ABC"))
        XCTAssertFalse(isJunkDecode(i3: 4, n3: 0, callFrom: "<...>"))       // unresolved hash ok
        XCTAssertFalse(isJunkDecode(i3: 4, n3: 0, callFrom: "<PJ4/K1ABC>")) // resolved hash ok
    }
}
