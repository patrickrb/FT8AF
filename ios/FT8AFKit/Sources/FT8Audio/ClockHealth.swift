import Foundation

/// Clock-sync health classification derived from the mean decode DT (the time
/// offset, in seconds, of the stations we're hearing). Port of Android's
/// `ClockSync.kt`.
///
/// FT8 tolerates only ~2 s of clock error before decoding collapses both ways —
/// a mis-set clock stops you decoding others *and* pushes your transmissions to
/// the edge of everyone else's receive window. The mean DT of the stations you
/// decode is a good proxy for your own clock offset: most stations are
/// themselves NTP-synced, so a consistent bias across all of them is almost
/// certainly yours. All decision/format logic is pure so it can be unit-tested;
/// the SwiftUI indicator is a thin wrapper.
public enum ClockHealthLevel: Equatable, Sendable {
    case good, fair, poor, unknown
}

public enum ClockHealth {
    /// |DT| at/below this (seconds) is a healthy, in-the-sweet-spot clock.
    public static let goodSec: Float = 0.3
    /// |DT| at/below this (seconds) still decodes but is worth a resync; above
    /// it is POOR.
    public static let fairSec: Float = 1.0

    /// Classify the mean decode DT into a sync-health level. A `nil` value means
    /// no decode cycle has reported yet this session; a non-finite value is
    /// treated the same way so a bad reading can never mis-classify.
    public static func level(offsetSec: Float?) -> ClockHealthLevel {
        guard let offsetSec, offsetSec.isFinite else { return .unknown }
        let mag = abs(offsetSec)
        switch mag {
        case ...goodSec: return .good
        case ...fairSec: return .fair
        default: return .poor
        }
    }

    /// Signed, one-decimal-second DT label (WSJT-X style), e.g. "+0.1 s",
    /// "-1.3 s", "0.0 s", or an em dash when unknown. A value that rounds to
    /// zero renders unsigned (never "-0.0 s").
    public static func offsetLabel(offsetSec: Float?) -> String {
        guard let offsetSec, offsetSec.isFinite else { return "—" }
        let mag = String(format: "%.1f", abs(offsetSec))
        let sign = mag == "0.0" ? "" : (offsetSec < 0 ? "-" : "+")
        return "\(sign)\(mag) s"
    }

    /// Short status word for accessibility / screen readers.
    public static func statusText(_ level: ClockHealthLevel) -> String {
        switch level {
        case .good: return "In sync"
        case .fair: return "Resync soon"
        case .poor: return "Clock off"
        case .unknown: return "Unknown"
        }
    }
}
