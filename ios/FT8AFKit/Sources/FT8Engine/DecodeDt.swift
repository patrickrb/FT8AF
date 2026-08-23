import Foundation

/// DT — how far into our receive window a decoded signal actually started, in
/// seconds. WSJT-X shows this on every decode line; it is the single most useful
/// number for diagnosing a clock problem. A *consistent* bias across every
/// decode is almost certainly our own clock, while one outlier is just a station
/// with a bad clock (or a long-path signal). Port of Android `DecodeDt.kt`.

/// Threshold (seconds) past which a decode's DT is worth the operator's eye.
/// Shared with the slot-bar sync indicator on Android (`CLOCK_SYNC_FAIR_SEC`);
/// FT8 tolerates roughly ±2 s before decoding collapses, so ±1 s is actionable.
public let clockSyncFairSec: Float = 1.0

/// A decode's DT, WSJT-X style: signed, one decimal, no unit — "-1.3", "+0.2",
/// "0.0". A value that rounds to zero renders unsigned ("-0.0" is noise). NaN or
/// infinite input renders "--".
public func formatDecodeDt(_ seconds: Float) -> String {
    if seconds.isNaN || seconds.isInfinite { return "--" }
    let rounded = (seconds * 10).rounded() / 10
    if abs(rounded) < 0.05 { return "0.0" }
    return String(format: "%+.1f", rounded)
}

/// True when a decode's DT is far enough out to be worth flagging (used only to
/// colour the label). Shares `clockSyncFairSec` so a row can never call a
/// reading alarming while an averaged indicator still calls it fair.
public func isDecodeDtNotable(_ seconds: Float) -> Bool {
    !seconds.isNaN && !seconds.isInfinite && abs(seconds) > clockSyncFairSec
}
