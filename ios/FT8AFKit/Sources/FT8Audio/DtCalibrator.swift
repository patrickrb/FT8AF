import Foundation

/// Automatic capture-latency / clock-residual (DT) calibration — the iOS port of
/// Android's `ClockSelfSync`, keeping the desktop `calibrate_dt` math
/// (median + slow EMA + deadband) and layering Android's per-slot robustness
/// (MAD outlier rejection + a two-slot same-sign confirmation) on top.
///
/// The FT8 network is NTP-synced, so the *median* DT of a slot's decodes is a
/// robust estimate of our own systematic timing error (device-clock residual +
/// audio buffering latency). Driving it toward zero with a damped correction
/// tracks the truth without chasing noise.
///
/// **Whole-clock, not RX-only.** The correction produced here feeds the unified
/// whole-clock offset (``ClockOffset/autoOffsetMs``), which shifts RX slot
/// detection, TX key-up, and logged UTC together — matching Android, where the
/// self-sync writes `UtcTimer.delay`. This replaces the earlier RX-only
/// `rxOffsetMs` path (which moved only the RX slice); the correction now reaches
/// RX exactly once, through the shifted clock.
///
/// **Sign.** The whole-clock offset is *added* to the wall clock
/// (`nowMs = wall + combinedMs`), the opposite convention from the retired
/// `rxOffsetMs` (which was *subtracted* inside `rxSlotID`). A positive median DT
/// means decodes land late in our window — our clock is fast — so the correction
/// *subtracts* here to pull the clock back, exactly as Android does
/// (`delay - round(median * gain)`).
///
/// The type is a value struct holding only the confirmation streak, so the
/// decision logic stays pure and unit-testable off the audio/engine thread; the
/// engine owns an instance per decode session and mutates it once per decoded
/// slot.
public struct DtCalibrator {
    /// Minimum surviving (post-outlier-rejection) decodes for a slot to count.
    public static let minDecodes = 4
    /// Correction fraction applied per confirmed slot (EMA / proportional gain).
    public static let alpha = 0.6
    /// Ignore |median DT| at/below this (ms) to avoid jitter around a good clock.
    public static let deadbandMs: Int64 = 60
    /// Clamp the accumulated whole-clock auto offset to a sane range.
    public static let maxOffsetMs: Int64 = 4_000

    /// Absolute floor (seconds) for the MAD-based rejection threshold — keeps a
    /// tight cluster (MAD near 0) from rejecting everything but the exact median.
    public static let madFloorSec: Float = 0.2
    /// Rejection threshold is `max(madFloorSec, madMultiplier * MAD)`.
    public static let madMultiplier: Float = 3
    /// Consecutive qualifying same-sign slots required before a correction lands.
    public static let confirmSlots = 2

    // Confirmation streak: how many consecutive qualifying slots have agreed and
    // the sign (+1/-1) they agreed on. 0 sign = no streak in progress.
    private var streak = 0
    private var streakSign = 0

    public init() {}

    // MARK: - Pure helpers (static, so they unit-test without an instance)

    /// Median of `values` using the upper-middle element (`sorted[count/2]`),
    /// matching the desktop `calibrate_dt` convention. Empty input yields 0 —
    /// callers gate on sample count anyway.
    public static func median(_ values: [Float]) -> Float {
        if values.isEmpty { return 0 }
        let sorted = values.sorted()
        return sorted[sorted.count / 2]
    }

    /// MAD-based outlier rejection: samples farther than
    /// `max(madFloorSec, madMultiplier * MAD)` from the median are dropped, so a
    /// single wild DT can't drag the slot's median (and thus the clock).
    public static func rejectOutliers(_ dtSec: [Float]) -> [Float] {
        if dtSec.isEmpty { return [] }
        let med = median(dtSec)
        let mad = median(dtSec.map { abs($0 - med) })
        let threshold = max(madFloorSec, madMultiplier * mad)
        return dtSec.filter { abs($0 - med) <= threshold }
    }

    // MARK: - Per-slot decision

    /// Feed one slot's decode DTs (seconds) through outlier rejection, the
    /// sample-count gate, the deadband, and the two-slot same-sign confirmation.
    /// Returns the NEW clamped whole-clock auto offset (ms) to apply, or `nil`
    /// for no change this slot.
    ///
    /// - Parameters:
    ///   - autoOffsetMs: the live ``ClockOffset/autoOffsetMs`` at decision time.
    ///   - decodedDtSec: this slot's per-decode DTs (seconds); own-TX echoes
    ///     already filtered out upstream.
    public mutating func calibrate(autoOffsetMs: Int64, decodedDtSec: [Float]) -> Int64? {
        let survivors = Self.rejectOutliers(decodedDtSec)
        if survivors.count < Self.minDecodes {
            // Too little evidence — leave the streak untouched so a sparse slot
            // doesn't break up a genuine bias building over adjacent slots.
            return nil
        }

        let medianMs = Int64(Self.median(survivors) * 1000.0) // truncates toward 0
        if abs(medianMs) <= Self.deadbandMs {
            // Clock is healthy; any streak that was building was noise.
            streak = 0
            streakSign = 0
            return nil
        }

        let sign = medianMs > 0 ? 1 : -1
        if sign != streakSign {
            // First qualifying slot in this direction (or a direction flip):
            // start the streak, and only act once a later slot confirms.
            streakSign = sign
            streak = 1
        } else {
            streak += 1
        }
        if streak < Self.confirmSlots { return nil }

        // Confirmed bias: apply the damped correction. Subtract (whole-clock is
        // added to the wall clock, so a positive DT / fast clock pulls it back).
        let corrected = autoOffsetMs - Int64((Double(medianMs) * Self.alpha).rounded())
        let clamped = min(max(corrected, -Self.maxOffsetMs), Self.maxOffsetMs)
        streak = 0
        streakSign = 0
        return clamped
    }
}
