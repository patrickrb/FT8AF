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

    /// Render `tones` as a GFSK waveform into `signal[offset...]`.
    /// Traps (precondition) if `signal` is too short — mirrors the encode.rs assert.
    public static func synthGFSK(tones: [UInt8], f0: Float, sampleRate: Int32,
                                 into signal: inout [Float], offset: Int) {
        let nSpsym = Int(0.5 + Double(sampleRate) * Double(FT8.symbolPeriod))
        let nWave = tones.count * nSpsym
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
        let numSamples = Int(0.5 + Float(FT8.nn) * FT8.symbolPeriod * Float(sampleRate))
        var signal = [Float](repeating: 0, count: numSamples)
        synthGFSK(tones: tones, f0: baseFreqHz, sampleRate: sampleRate, into: &signal, offset: 0)
        return signal
    }
}
