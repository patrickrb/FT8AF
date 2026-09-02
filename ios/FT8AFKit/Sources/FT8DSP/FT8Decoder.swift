import CFT8
import Foundation

/// A single decoded FT8 message with geometry + signal metrics
/// (mirror desktop dsp/decoder.rs DecodedMessage).
public struct DecodedMessage: Equatable, Hashable {
    public var callTo = ""
    public var callFrom = ""
    public var extra = ""
    public var grid = ""
    public var rawText = ""
    public var snr = 0
    public var freqHz: Float = 0
    public var timeSec: Float = 0
    public var score = 0
    public var i3: UInt8 = 0
    public var n3: UInt8 = 0
    /// 12-byte a91 (77-bit payload + 14-bit CRC), for deep-decode subtraction.
    public var a91 = [UInt8](repeating: 0, count: 12)

    public init() {}
}

/// Safe FT8 decoder wrapper replicating the decoder lifecycle from
/// ft8af_glue/ft8_decoder.cpp (mirrored in desktop dsp/decoder.rs):
///   init -> feedSlot -> findSync -> decodeAll -> (deinit frees the monitor).
///
/// Owns the C `monitor_t` (freed in deinit), a candidate array (cap 140), and a
/// per-decoder callsign hash table for resolving hashed compound calls.
public final class FT8Decoder {
    private static let maxCandidates = 140
    private static let minScore: Int32 = 10
    /// LDPC belief-propagation iteration caps (mirror ft8af_glue decode_params.h
    /// `FT8AF_LDPC_ITERS_FAST` / `_DEEP`). Deep decode's only effect in this
    /// Swift wrapper is raising the iteration cap; see `setDeep`.
    public static let ldpcItersNormal: Int32 = 20
    public static let ldpcItersDeep: Int32 = 30

    private var mon = monitor_t()
    private var candidates = [candidate_t](repeating: candidate_t(), count: FT8Decoder.maxCandidates)
    private var numCandidates = 0

    /// The codec sample rate (needed by the time-domain subtraction, which works
    /// in the raw sample domain rather than the waterfall).
    let sampleRate: Int32
    /// Whether this decoder is running the FT8 protocol (vs. FT4). The subtract-
    /// and-redecode deep loop re-synthesizes FT8 tones, so it is only valid for
    /// FT8; FT4 uses the single decode pass. Exposed read-only for that gate/tests.
    public let isFT8: Bool
    /// Retained copy of the samples last fed through the monitor. The subtract-
    /// and-redecode loop edits this in place (removing each decoded signal) and
    /// re-runs the STFT over it — mirroring ft8_decoder_state.samples/num_fed in
    /// the Android JNI glue, which keeps the slot's audio for exactly this. Only
    /// the block-aligned prefix [0, numFed) was actually processed by the monitor.
    private(set) var fedSamples: [Float] = []
    private(set) var numFed = 0
    /// Current LDPC iteration cap fed to `ft8_decode`. Exposed read-only so
    /// callers/tests can confirm `setDeep` took effect.
    public private(set) var ldpcIterations = FT8Decoder.ldpcItersNormal
    private let hashtable: HashTable

    /// Whether deep decode is currently active (iteration cap raised).
    public var isDeep: Bool { ldpcIterations == FT8Decoder.ldpcItersDeep }

    /// Create a decoder for the given sample rate. `isFT8 = false` selects FT4.
    ///
    /// `hashTable` is the callsign-hash store used to resolve hashed compound
    /// calls (`<...>`). Pass a shared, long-lived table (as LiveEngine does) so
    /// hashes resolve across slots even though a fresh decoder is built each
    /// slot; the default is a private per-decoder table (same-slot resolution
    /// only), which keeps standalone/one-shot decodes isolated.
    public init(sampleRate: Int32 = FT8.sampleRate, isFT8: Bool = true, hashTable: HashTable = HashTable()) {
        self.hashtable = hashTable
        self.sampleRate = sampleRate
        self.isFT8 = isFT8
        var cfg = monitor_config_t()
        cfg.f_min = 100
        cfg.f_max = 3500
        cfg.sample_rate = sampleRate
        cfg.time_osr = 2
        cfg.freq_osr = 2
        cfg.`protocol` = isFT8 ? FTX_PROTOCOL_FT8 : FTX_PROTOCOL_FT4
        monitor_init(&mon, &cfg)
    }

    deinit { monitor_free(&mon) }

    /// Set deep-decode mode (more LDPC iterations). Mirrors `setDecodeMode`.
    public func setDeep(_ deep: Bool) {
        ldpcIterations = deep ? FT8Decoder.ldpcItersDeep : FT8Decoder.ldpcItersNormal
    }

    /// Feed a full slot of mono Float samples (12 kHz) through the monitor:
    /// reset then process block by block (mirror feed_slot).
    public func feedSlot(_ samples: [Float]) {
        let block = Int(mon.block_size)
        if block == 0 { return }
        monitor_reset(&mon)
        // The callsign hash table is intentionally NOT cleared here: it persists
        // across slots so a `<...>` hash resolves against a full compound call
        // decoded in an earlier slot (matching Android's static hashList). The
        // table bounds its own growth (see HashTable.capacity).
        // Retain the samples so the deep-decode subtract loop can edit them in
        // place and re-run the STFT (see runSubtractLoop / rerunMonitor).
        fedSamples = samples
        numFed = (samples.count / block) * block
        samples.withUnsafeBufferPointer { buf in
            guard let base = buf.baseAddress else { return }
            var pos = 0
            while pos + block <= buf.count {
                monitor_process(&mon, base + pos)
                pos += block
            }
        }
    }

    /// Recompute the waterfall from the (possibly subtraction-edited) retained
    /// samples, so the next `findSync` sees the residual. Mirrors the
    /// wf_dirty branch of Android's DecoderFt8FindSync.
    internal func rerunMonitor() {
        let block = Int(mon.block_size)
        if block == 0 || numFed <= 0 { return }
        monitor_reset(&mon)
        fedSamples.withUnsafeBufferPointer { buf in
            guard let base = buf.baseAddress else { return }
            var pos = 0
            while pos + block <= numFed {
                monitor_process(&mon, base + pos)
                pos += block
            }
        }
    }

    /// Coherently subtract one decoded signal's GFSK waveform from the retained
    /// samples, in place (the native WSJT-X-style time-domain subtraction). The
    /// caller must `rerunMonitor` before decoding again. Returns false (samples
    /// unchanged) on degenerate input, matching `ft8_subtract_signal_time`.
    @discardableResult
    internal func subtractSignalTime(a91: [UInt8], freqHz: Float, timeSec: Float) -> Bool {
        guard numFed > 0, a91.count >= 10 else { return false }
        return fedSamples.withUnsafeMutableBufferPointer { sp -> Bool in
            guard let sbase = sp.baseAddress else { return false }
            return a91.withUnsafeBufferPointer { ap in
                ft8_subtract_signal_time(&mon, sbase, Int32(numFed), sampleRate,
                                         ap.baseAddress, freqHz, timeSec)
            }
        }
    }

    /// Locate sync candidates. Mirrors `DecoderFt8FindSync`.
    @discardableResult
    public func findSync() -> Int {
        let n = candidates.withUnsafeMutableBufferPointer { cbuf in
            ft8_find_sync(&mon.wf, Int32(FT8Decoder.maxCandidates), cbuf.baseAddress, FT8Decoder.minScore)
        }
        numCandidates = max(0, min(Int(n), FT8Decoder.maxCandidates))
        return numCandidates
    }

    /// Decode every candidate found by the last `findSync`, returning the
    /// successfully decoded messages (deduped by text). Mirrors the per-candidate
    /// `DecoderFt8Analysis` loop.
    public func decodeAll() -> [DecodedMessage] {
        var out: [DecodedMessage] = []
        // The sync search yields several candidates for one signal; collapse
        // those that decode to identical text so each message appears once.
        var seen = Set<String>()
        // Activate this decoder's hash table for the callbacks invoked by
        // ftx_message_decode*; clear on the way out (RAII via defer).
        installActiveHashTable(hashtable)
        defer { clearActiveHashTable() }
        var iface = makeHashInterface()

        for idx in 0..<numCandidates {
            var cand = candidates[idx]
            var message = ftx_message_t()
            var status = decode_status_t()
            let ok = ft8_decode(&mon.wf, &cand, ldpcIterations, &message, &status)
            if !ok { continue }
            if let msg = buildMessage(cand: &cand, message: &message, iface: &iface) {
                // Keep the first (highest-score) decode of each unique message.
                if msg.rawText.isEmpty || seen.insert(msg.rawText).inserted {
                    out.append(msg)
                }
            }
        }
        return out
    }

    /// Downsample the monitor's waterfall magnitudes (populated by the last
    /// `feedSlot`) into a `rows x cols` heatmap of 0...255 values for display.
    /// Returns (bins, rows, cols, hzPerCol). Mirrors waterfall_heatmap.
    public func waterfallHeatmap(maxRows: Int, maxCols: Int) -> (bins: [UInt8], rows: Int, cols: Int, hzPerCol: Float) {
        let wf = mon.wf
        guard let mag = wf.mag, wf.num_blocks > 0, wf.num_bins > 0 else { return ([], 0, 0, 0) }
        let numBlocks = Int(wf.num_blocks)
        let numBins = Int(wf.num_bins)
        let stride = Int(wf.block_stride)
        let rows = max(1, min(numBlocks, maxRows))
        let cols = max(1, min(numBins, maxCols))
        var bins = [UInt8](repeating: 0, count: rows * cols)
        for r in 0..<rows {
            let block = r * numBlocks / rows
            let base = block * stride // time_sub=0, freq_sub=0 plane
            for c in 0..<cols {
                let bin0 = c * numBins / cols
                let bin1 = min(max((c + 1) * numBins / cols, bin0 + 1), numBins)
                var mx: UInt8 = 0
                for bin in bin0..<bin1 {
                    let v = mag[base + bin]
                    if v > mx { mx = v }
                }
                bins[r * cols + c] = mx
            }
        }
        // Bin spacing in Hz = (1/symbol_period)/freq_osr; columns aggregate bins.
        let binHz = (1.0 / mon.symbol_period) / Float(wf.freq_osr)
        let hzPerCol = binHz * (Float(numBins) / Float(cols))
        return (bins, rows, cols, hzPerCol)
    }

    private func buildMessage(cand: inout candidate_t, message: inout ftx_message_t,
                              iface: inout ftx_callsign_hash_interface_t) -> DecodedMessage? {
        let freqHz = (Float(mon.min_bin) + Float(cand.freq_offset)
                      + Float(cand.freq_sub) / Float(mon.wf.freq_osr)) / mon.symbol_period
        let timeSec = (Float(cand.time_offset)
                       + Float(cand.time_sub) / Float(mon.wf.time_osr)) * mon.symbol_period
        let snr = ft8_snr(&mon.wf, &cand)

        var a91 = [UInt8](repeating: 0, count: 12)
        withUnsafeBytes(of: message.payload) { payloadRaw in
            let payloadPtr = payloadRaw.bindMemory(to: UInt8.self).baseAddress
            a91.withUnsafeMutableBufferPointer { a in ftx_add_crc(payloadPtr, a.baseAddress) }
        }

        let i3 = ftx_message_get_i3(&message)
        let n3 = ftx_message_get_n3(&message)
        let msgType = ftx_message_get_type(&message)

        // Canonical full text (always available) — mirrors getMessageText.
        var rawText = ""
        var textBuf = [CChar](repeating: 0, count: 64)
        let rcText = textBuf.withUnsafeMutableBufferPointer { ftx_message_decode(&message, &iface, $0.baseAddress) }
        if rcText == FTX_MESSAGE_RC_OK { rawText = cString(textBuf) }

        var m = DecodedMessage()
        m.snr = Int(snr)
        m.freqHz = freqHz
        m.timeSec = timeSec
        m.score = Int(cand.score)
        m.i3 = i3
        m.n3 = n3
        m.a91 = a91
        m.rawText = rawText

        if msgType == FTX_MESSAGE_TYPE_STANDARD || msgType == FTX_MESSAGE_TYPE_NONSTD_CALL {
            var callTo = [CChar](repeating: 0, count: 24)
            var callDe = [CChar](repeating: 0, count: 24)
            var extra = [CChar](repeating: 0, count: 24)
            let rc = callTo.withUnsafeMutableBufferPointer { ct in
                callDe.withUnsafeMutableBufferPointer { cd in
                    extra.withUnsafeMutableBufferPointer { ex in
                        msgType == FTX_MESSAGE_TYPE_STANDARD
                            ? ftx_message_decode_std(&message, &iface, ct.baseAddress, cd.baseAddress, ex.baseAddress)
                            : ftx_message_decode_nonstd(&message, &iface, ct.baseAddress, cd.baseAddress, ex.baseAddress)
                    }
                }
            }
            if rc != FTX_MESSAGE_RC_OK { return nil }
            m.callTo = cString(callTo)
            m.callFrom = cString(callDe)
            m.extra = cString(extra)
            if looksLikeGrid(m.extra) { m.grid = m.extra }
        } else {
            // Free text + contest sub-types: full text already in rawText.
            m.extra = m.rawText
        }
        // Drop CRC-collision garbage before it reaches the UI/log (mirrors
        // Android Ft8Message.isJunkDecode). Free text / telemetry are exempt.
        if isJunkDecode(i3: m.i3, n3: m.n3, callFrom: m.callFrom) { return nil }
        return m
    }
}

private func cString(_ buf: [CChar]) -> String {
    buf.withUnsafeBufferPointer { String(cString: $0.baseAddress!) }
}

/// A 4-char Maidenhead grid: two A-R letters then two digits.
func looksLikeGrid(_ s: String) -> Bool {
    let b = Array(s.utf8)
    return b.count == 4
        && (UInt8(ascii: "A")...UInt8(ascii: "R")).contains(b[0])
        && (UInt8(ascii: "A")...UInt8(ascii: "R")).contains(b[1])
        && (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(b[2])
        && (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(b[3])
}
