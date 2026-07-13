import Foundation

/// Pure generator for the TUNE carrier: a steady sine at the current TX audio
/// frequency, used for antenna/amplifier tuning (port of Android's tune
/// carrier, issue #408). Short raised-cosine ramps at both ends avoid key
/// clicks; the buffer length is capped by the tune safety timeout, so playback
/// simply ending enforces the hard max-on time even if the countdown UI dies.
public enum TuneTone {
    /// Ramp length applied at the head and tail of the carrier.
    public static let rampMs = 10

    /// Label for the TUNE pill: the plain label when idle, "TUNE 7s" while the
    /// carrier is up so the operator sees the safety timeout running (port of
    /// Android `tuneChipLabel`).
    public static func chipLabel(
        _ label: String = "TUNE",
        isTuning: Bool,
        remainingSec: Int
    ) -> String {
        isTuning ? "\(label) \(max(0, remainingSec))s" : label
    }

    /// Generate `durationSec` seconds of steady carrier at `freqHz`.
    /// Returns an empty buffer for degenerate inputs (non-positive duration,
    /// frequency, or sample rate).
    public static func samples(
        freqHz: Float,
        sampleRate: Int,
        durationSec: Int,
        amplitude: Float = 0.9
    ) -> [Float] {
        guard durationSec > 0, sampleRate > 0, freqHz > 0 else { return [] }
        let count = sampleRate * durationSec
        let rampSamples = min(count / 2, sampleRate * rampMs / 1000)
        let omega = 2.0 * Double.pi * Double(freqHz) / Double(sampleRate)

        var out = [Float](repeating: 0, count: count)
        for i in 0..<count {
            var a = Double(amplitude) * sin(omega * Double(i))
            if rampSamples > 0 {
                // Raised-cosine fade-in / fade-out.
                if i < rampSamples {
                    let t = Double(i) / Double(rampSamples)
                    a *= 0.5 * (1 - cos(.pi * t))
                } else if i >= count - rampSamples {
                    let t = Double(count - 1 - i) / Double(rampSamples)
                    a *= 0.5 * (1 - cos(.pi * t))
                }
            }
            out[i] = Float(a)
        }
        return out
    }
}
