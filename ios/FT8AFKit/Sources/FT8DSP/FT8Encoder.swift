import CFT8
import Foundation

/// FT8 encode path — safe Swift wrappers over pack77 / ft8_encode /
/// synth_gfsk_offset, mirroring desktop dsp/encode.rs (and GenerateFT8 on
/// Android). `generateFT8` is the one call the TX engine uses.
public enum FT8Encoder {

    /// Pack a structured FT8 message into a 10-byte (77-bit) payload.
    /// Returns nil if pack77 rejects the message (nonzero status).
    public static func pack77(_ message: String) -> [UInt8]? {
        var payload = [UInt8](repeating: 0, count: 10)
        let rc = message.withCString { cstr in
            payload.withUnsafeMutableBufferPointer { CFT8.pack77(cstr, $0.baseAddress) }
        }
        return rc == 0 ? payload : nil
    }

    /// Force free-text (i3=0, n3=0) packing regardless of structure.
    public static func packFreeText(_ text: String) -> [UInt8] {
        var payload = [UInt8](repeating: 0, count: 10)
        text.withCString { cstr in
            _ = payload.withUnsafeMutableBufferPointer { CFT8.packtext77(cstr, $0.baseAddress) }
        }
        return payload
    }

    /// Encode a 10-byte payload into 79 FT8 tones (CRC + LDPC added internally).
    public static func encodeTones(_ payload: [UInt8]) -> [UInt8] {
        precondition(payload.count == 10, "FT8 payload must be 10 bytes")
        var tones = [UInt8](repeating: 0, count: FT8.nn)
        payload.withUnsafeBufferPointer { p in
            tones.withUnsafeMutableBufferPointer { t in
                CFT8.ft8_encode(p.baseAddress, t.baseAddress)
            }
        }
        return tones
    }

    /// Add the 14-bit CRC, producing the 12-byte a91 (77-bit payload + CRC).
    public static func addCRC(_ payload: [UInt8]) -> [UInt8] {
        precondition(payload.count == 10, "FT8 payload must be 10 bytes")
        var a91 = [UInt8](repeating: 0, count: 12)
        payload.withUnsafeBufferPointer { p in
            a91.withUnsafeMutableBufferPointer { a in
                CFT8.ftx_add_crc(p.baseAddress, a.baseAddress)
            }
        }
        return a91
    }

    /// Number of audio samples the GFSK synthesis writes for `nTones` tones at
    /// `sampleRate`: the *per-symbol*-rounded count the C `synth_gfsk` actually
    /// produces — `nTones * round(sampleRate * symbolPeriod)` — NOT the
    /// *total*-rounded `round(nTones * symbolPeriod * sampleRate)`. The two agree
    /// only when `sampleRate * symbolPeriod` is integral (true for the 12 kHz
    /// default: `12000 * 0.16 = 1920`) and diverge otherwise, so this is the
    /// single source of truth shared by both the TX buffer allocation in
    /// `generateFT8` and the length guard in `synthGFSK`. Keeping them in lockstep
    /// prevents the allocation from underrunning the write count — which tripped
    /// `synthGFSK`'s `precondition` at non-integral rates (the iOS twin of the
    /// Android GenerateFT8 fix and desktop `encode.rs::waveform_len`).
    public static func waveformSampleCount(nTones: Int, sampleRate: Int32) -> Int {
        let nSpsym = Int(0.5 + Double(sampleRate) * Double(FT8.symbolPeriod))
        return nTones * nSpsym
    }

    /// Render `tones` as a GFSK waveform into `signal[offset...]`.
    /// Traps (precondition) if `signal` is too short — mirrors the encode.rs assert.
    public static func synthGFSK(tones: [UInt8], f0: Float, sampleRate: Int32,
                                 into signal: inout [Float], offset: Int) {
        let nWave = waveformSampleCount(nTones: tones.count, sampleRate: sampleRate)
        // A negative offset would still satisfy the upper-bound check below yet
        // make the C write before signal[0] (OOB); and offset is handed to C as
        // an `int`, so it must fit Int32. The desktop encode.rs sidesteps both by
        // typing offset as usize — Swift needs the guards explicit.
        precondition(offset >= 0, "synthGFSK: offset must be non-negative, got \(offset)")
        precondition(offset <= Int(Int32.max), "synthGFSK: offset \(offset) exceeds C int range")
        precondition(offset + nWave <= signal.count,
                     "synthGFSK: signal buffer too short (need \(nWave) from offset \(offset), have \(signal.count))")
        tones.withUnsafeBufferPointer { tp in
            signal.withUnsafeMutableBufferPointer { sp in
                CFT8.synth_gfsk_offset(tp.baseAddress, Int32(tones.count), f0,
                                       FT8.symbolBT, FT8.symbolPeriod, sampleRate,
                                       sp.baseAddress, Int32(offset))
            }
        }
    }

    /// Full encode: structured message text -> 12.64 s of mono Float audio at
    /// `sampleRate`, centered on `baseFreqHz`. Returns nil if the text can't be
    /// packed as a structured message. Mirrors desktop generate_ft8 /
    /// GenerateFT8.generateFt8.
    public static func generateFT8(_ text: String, baseFreqHz: Float,
                                   sampleRate: Int32 = FT8.sampleRate) -> [Float]? {
        guard let payload = pack77(text) else { return nil }
        let tones = encodeTones(payload)
        // Size the buffer with the SAME per-symbol rounding synthGFSK writes, so
        // it can never underrun the write count (and trip synthGFSK's length
        // precondition) at a non-integral sample rate. Byte-identical to the old
        // total-rounded size at the 12 kHz default.
        let numSamples = waveformSampleCount(nTones: tones.count, sampleRate: sampleRate)
        var signal = [Float](repeating: 0, count: numSamples)
        synthGFSK(tones: tones, f0: baseFreqHz, sampleRate: sampleRate, into: &signal, offset: 0)
        return signal
    }
}
