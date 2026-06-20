import Foundation

/// Capture-latency (DT) calibration — port of `calibrate_dt` in desktop
/// engine.rs, as a pure function so it is testable off the audio/engine thread.
///
/// The FT8 network is NTP-synced, so the *median* DT of a slot's decodes is a
/// robust estimate of our own systematic timing error (clock residual + audio
/// buffering latency). We nudge the RX-window offset toward zeroing it with a
/// slow EMA so it tracks without chasing noise. The engine owns `rxOffsetMs`,
/// persists it, and re-anchors the boundary; this type only computes the value.
public enum DtCalibrator {
    /// Need a few independent decodes for the median to be meaningful.
    public static let minDecodes = 4
    /// Correction fraction applied per slot.
    public static let alpha = 0.6
    /// Ignore tiny residuals to avoid jitter.
    public static let deadbandMs: Int64 = 60
    /// Clamp the accumulated offset to a sane range.
    public static let maxOffsetMs: Int64 = 4_000

    /// Given the current `rxOffsetMs` and this slot's decoded DTs (seconds),
    /// return the updated `rxOffsetMs`. Returns the input unchanged when there
    /// aren't enough decodes, the median residual is within the deadband, or the
    /// nudge rounds to no change.
    public static func calibrate(rxOffsetMs: Int64, decodedDtSec: [Float]) -> Int64 {
        if decodedDtSec.count < minDecodes { return rxOffsetMs }

        let sorted = decodedDtSec.sorted()
        let medianDtMs = Int64(sorted[sorted.count / 2] * 1000.0) // truncates toward 0, as Rust `as i64`
        if abs(medianDtMs) <= deadbandMs { return rxOffsetMs }

        let adjusted = rxOffsetMs + Int64((Double(medianDtMs) * alpha).rounded())
        return min(max(adjusted, -maxOffsetMs), maxOffsetMs)
    }
}
