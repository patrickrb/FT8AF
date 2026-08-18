import Foundation

/// Whole-clock correction applied to the device wall clock so the app's UTC
/// matches an authoritative reference. Port of Android's `UtcTimer.delay`
/// (`getSystemTime() = delay + System.currentTimeMillis()`), split into its two
/// independent sources so each can be set and persisted on its own:
///
///  - `ntpOffsetMs`   — from an SNTP "Sync now" query (see ``Sntp``).
///  - `manualOffsetMs`— an operator nudge for operating offline where NTP can't
///    reach a time server (mirrors Android's manual TimeCorrection).
///
/// The combined value is added to **every** wall-clock read the engine uses for
/// slot/TX math and for logged/displayed UTC — RX slot detection, TX key-up
/// timing, and QSO timestamps all shift together. This is deliberately distinct
/// from ``DtCalibrator``'s `rxOffsetMs`, which is an RX-only capture-latency
/// compensation and must *not* move TX or logged times: the clock offset here
/// corrects the device clock itself.
public struct ClockOffset: Equatable, Sendable {
    /// NTP-derived correction (ms). Positive = device clock is behind UTC.
    public var ntpOffsetMs: Int64
    /// Manual operator correction (ms), clamped to ``manualMinMs``…``manualMaxMs``.
    public var manualOffsetMs: Int64

    public init(ntpOffsetMs: Int64 = 0, manualOffsetMs: Int64 = 0) {
        self.ntpOffsetMs = ntpOffsetMs
        self.manualOffsetMs = ClockOffset.clampManualMs(manualOffsetMs)
    }

    /// Total correction added to the wall clock (`ntp + manual`).
    public var combinedMs: Int64 { ntpOffsetMs + manualOffsetMs }

    /// Apply the whole-clock correction to a wall-clock epoch-ms value. This is
    /// the single transform every engine time read routes through so the manual
    /// / NTP offset reaches RX slot detection, TX scheduling, and timestamps
    /// identically.
    public func apply(toUtcMs wallMs: Int64) -> Int64 { wallMs + combinedMs }

    // MARK: - Manual-offset range

    /// Inclusive bounds for the manual correction, in milliseconds. ±5 s (not
    /// the much tighter FT8 decode tolerance) because it has to cover *device
    /// clock error*: a phone offline for a while can drift several seconds, and
    /// the correction is what pulls that back toward 0 (mirrors Android's
    /// `MANUAL_TIME_CORRECTION_MIN_MS` / `MAX_MS`).
    public static let manualMinMs: Int64 = -5_000
    public static let manualMaxMs: Int64 = 5_000

    /// Coerce an arbitrary manual correction into the allowed range.
    public static func clampManualMs(_ ms: Int64) -> Int64 {
        min(max(ms, manualMinMs), manualMaxMs)
    }

    /// Apply a stepper nudge (e.g. +100 or -500 ms) to a manual value and clamp.
    public static func stepManualMs(_ current: Int64, byMs deltaMs: Int64) -> Int64 {
        clampManualMs(current + deltaMs)
    }

    /// Human-readable signed offset, e.g. "+0.6 s", "-0.3 s", "0.0 s" (a value
    /// that rounds to zero renders unsigned, never "-0.0 s"). Mirrors Android's
    /// `formatOffsetMs`.
    public static func formatMs(_ ms: Int64) -> String {
        let mag = String(format: "%.1f", abs(Double(ms)) / 1000.0)
        let sign = mag == "0.0" ? "" : (ms < 0 ? "-" : "+")
        return "\(sign)\(mag) s"
    }
}
