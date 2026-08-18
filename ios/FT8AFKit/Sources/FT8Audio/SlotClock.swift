import Foundation

/// UTC 15 s slot timing for the FT8 cycle (the math behind the `FT8Engine` run
/// loop; port of the slot-id / boundary arithmetic in desktop engine.rs).
///
/// All times are Unix-epoch milliseconds, already including any NTP clock offset.
/// Uses Euclidean division so results stay correct even if a clock correction
/// pushes the effective time slightly negative (matches Rust `div_euclid` /
/// `rem_euclid`, which the desktop relies on).
public enum SlotClock {
    /// Default cycle length (FT8, ms). Mode-parameterized callers pass the
    /// active `ModeProfile.cycleMs`; the default keeps FT8 callers unchanged.
    public static let cycleMs: Int64 = 15_000

    /// The slot index containing `nowMs` (`cycleMs`-length slots since the epoch).
    public static func slotID(atUtcMs nowMs: Int64, cycleMs: Int64 = SlotClock.cycleMs) -> Int64 {
        floorDiv(nowMs, cycleMs)
    }

    /// Milliseconds elapsed into the current slot (0..<cycleMs).
    public static func msIntoCycle(atUtcMs nowMs: Int64, cycleMs: Int64 = SlotClock.cycleMs) -> Int64 {
        floorMod(nowMs, cycleMs)
    }

    /// The RX-window slot index. The RX boundary runs on a clock shifted later by
    /// the capture-latency compensation (`rxOffsetMs`) so the sliced slot aligns
    /// with the audio that has actually arrived in the buffer; UTC display and TX
    /// still use the unshifted clock.
    public static func rxSlotID(atUtcMs nowMs: Int64, rxOffsetMs: Int64, cycleMs: Int64 = SlotClock.cycleMs) -> Int64 {
        floorDiv(nowMs - rxOffsetMs, cycleMs)
    }

    /// Even/odd parity of a slot (the FT8 sequential / TX-turn bit).
    public static func parity(slotID: Int64) -> Int64 {
        floorMod(slotID, 2)
    }

    /// Whether we should key up in `slotID` for the operator's selected TX slot.
    ///
    /// TX audio is scheduled to start immediately, so it physically occupies
    /// `slotID` itself — eligibility is therefore the parity of that very slot,
    /// not the next one. This mirrors the desktop engine's `maybe_transmit`,
    /// which gates on `slot_id.rem_euclid(2)` for the slot it keys up in.
    public static func shouldTransmit(slotID: Int64, desiredParity: Int) -> Bool {
        Int(parity(slotID: slotID)) == desiredParity
    }

    // Euclidean division/modulo: quotient floored toward -inf so the remainder is
    // always in [0, |b|). For non-negative inputs these equal `/` and `%`.
    static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b, r = a % b
        return (r != 0 && (r < 0) != (b < 0)) ? q - 1 : q
    }
    static func floorMod(_ a: Int64, _ b: Int64) -> Int64 {
        let r = a % b
        return (r != 0 && (r < 0) != (b < 0)) ? r + b : r
    }
}
