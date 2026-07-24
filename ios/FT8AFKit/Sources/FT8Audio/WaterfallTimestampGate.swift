import Foundation

/// Gates the waterfall's UTC-timestamp gridline (the horizontal boundary line
/// plus the time label) to one emission per transmit/decode slot — the pure,
/// unit-testable core behind the labels the waterfall draws at each 15 s FT8
/// period boundary. Port of the Android `WaterfallTimestampGate`, including
/// its mode-aware slot length (FT8 15 s today; FT4/FT2 would pass a shorter
/// `slotMs`).
///
/// The line marks where one cycle ends and the next begins on the scrolling
/// waterfall so an operator can line signals up against the slot grid. The
/// waterfall loop ticks ~4x per second, so without the gate the label would be
/// stamped on every row of a slot instead of exactly once at its boundary.
public struct WaterfallTimestampGate {
    /// Fallback slot length (FT8, ms) used if the caller has no valid mode slot.
    public static let defaultSlotMs: Int64 = 15_000

    private var lastPeriod: Int64 = -1
    /// Slot length `lastPeriod` was computed under; -1 = none yet.
    private var lastSlotMs: Int64 = -1

    public init() {}

    /// The slot index `utcMs` falls in for a mode of slot length `slotMs`. A
    /// non-positive `slotMs` falls back to the 15 s FT8 grid so a mid-init
    /// caller can never divide by zero. Euclidean division keeps the index
    /// correct even if a clock correction pushes the time negative.
    public static func slotPeriod(utcMs: Int64, slotMs: Int64 = defaultSlotMs) -> Int64 {
        let slot = slotMs > 0 ? slotMs : defaultSlotMs
        return SlotClock.floorDiv(utcMs, slot)
    }

    /// Epoch ms of the start of the slot containing `utcMs` — the instant the
    /// boundary label should display (Android draws the period-start time, not
    /// the wall clock of whichever frame crossed the boundary).
    public static func slotStartMs(utcMs: Int64, slotMs: Int64 = defaultSlotMs) -> Int64 {
        let slot = slotMs > 0 ? slotMs : defaultSlotMs
        return slotPeriod(utcMs: utcMs, slotMs: slot) * slot
    }

    /// Whether the boundary line + label should be emitted now: true the first
    /// call that `utcMs` crosses into a new slot, false while it stays within
    /// the same slot (so the label is stamped exactly once per slot).
    ///
    /// Slot indices are only comparable within one mode, so when `slotMs`
    /// changes mid-stream the gate re-baselines to the new grid silently,
    /// emitting only if `utcMs` happens to land exactly on a new-mode boundary
    /// (mirrors the Android gate's mode-change rule).
    public mutating func shouldDraw(utcMs: Int64, slotMs: Int64 = defaultSlotMs) -> Bool {
        let slot = slotMs > 0 ? slotMs : Self.defaultSlotMs
        let period = SlotClock.floorDiv(utcMs, slot)
        if lastSlotMs > 0 && slot != lastSlotMs {
            // Mode change: adopt the new grid without comparing across
            // incompatible indices.
            lastSlotMs = slot
            lastPeriod = period
            return SlotClock.floorMod(utcMs, slot) == 0
        }
        lastSlotMs = slot
        if period == lastPeriod {
            return false
        }
        lastPeriod = period
        return true
    }

    /// Forget the last emitted slot and mode (e.g. when the waterfall history
    /// is cleared), so the next call stamps a fresh label.
    public mutating func reset() {
        lastPeriod = -1
        lastSlotMs = -1
    }

    /// "HH:mm:ss" UTC label for an epoch-ms instant — the text drawn at the
    /// boundary row (matches Android's period-start `HH:mm:ss` stamp).
    public static func utcLabel(forUtcMs ms: Int64) -> String {
        let secondsOfDay = SlotClock.floorMod(ms, 86_400_000) / 1000
        let h = secondsOfDay / 3600
        let m = (secondsOfDay % 3600) / 60
        let s = secondsOfDay % 60
        return String(format: "%02d:%02d:%02d", h, m, s)
    }
}
