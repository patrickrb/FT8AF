import Foundation

/// Pure display formatters for the decode list (age, distance). Kept in the
/// kit so they are host-testable; the SwiftUI rows are thin wrappers.

/// Relative age of a decode, Android-style buckets:
/// <5 s → "now", <60 s → "32s", <1 h → "5m", <24 h → "2h", else "3d".
/// Negative deltas (clock skew) clamp to "now".
public func relativeAge(secondsAgo: Int) -> String {
    let s = max(0, secondsAgo)
    switch s {
    case ..<5: return "now"
    case ..<60: return "\(s)s"
    case ..<3600: return "\(s / 60)m"
    case ..<86400: return "\(s / 3600)h"
    default: return "\(s / 86400)d"
    }
}

/// Great-circle distance in kilometers between two Maidenhead locators, or
/// nil when either grid is malformed (uses the shared `gridToLatLon`, which
/// also rejects the "RR73"/"RR" sign-off tokens).
public func gridDistanceKm(from myGrid: String, to theirGrid: String) -> Double? {
    guard let a = gridToLatLon(myGrid), let b = gridToLatLon(theirGrid) else { return nil }
    let earthRadiusKm = 6371.0
    let dLat = (b.0 - a.0) * .pi / 180
    let dLon = (b.1 - a.1) * .pi / 180
    let lat1 = a.0 * .pi / 180
    let lat2 = b.0 * .pi / 180
    let sinDLat = sin(dLat / 2)
    let sinDLon = sin(dLon / 2)
    let h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
    return 2 * earthRadiusKm * asin(min(1, sqrt(h)))
}

/// Kilometers → miles.
public func kilometersToMiles(_ km: Double) -> Double { km * 0.621371 }

/// Format a QSO-path distance for the decode meta row: whole units with the
/// operator's preferred unit ("1832 km" / "1139 mi"). Zero/negative → "".
public func formatQsoDistance(km: Double, inMiles: Bool) -> String {
    guard km > 0 else { return "" }
    let value = inMiles ? kilometersToMiles(km) : km
    let rounded = Int(value.rounded())
    guard rounded > 0 else { return "" }
    return "\(rounded) \(inMiles ? "mi" : "km")"
}

/// Convenience: formatted grid-to-grid distance, or "" when unavailable.
public func gridDistanceText(from myGrid: String, to theirGrid: String, inMiles: Bool) -> String {
    guard let km = gridDistanceKm(from: myGrid, to: theirGrid) else { return "" }
    return formatQsoDistance(km: km, inMiles: inMiles)
}
