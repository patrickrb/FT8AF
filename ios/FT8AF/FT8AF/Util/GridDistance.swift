import Foundation

/// Haversine distance between two (lat, lon) points in kilometers.
func haversineDistance(from a: (Double, Double), to b: (Double, Double)) -> Double {
    let R = 6371.0 // Earth radius in km
    let dLat = (b.0 - a.0) * .pi / 180
    let dLon = (b.1 - a.1) * .pi / 180
    let lat1 = a.0 * .pi / 180
    let lat2 = b.0 * .pi / 180

    let sinDLat = sin(dLat / 2)
    let sinDLon = sin(dLon / 2)
    let h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
    return 2 * R * asin(min(1, sqrt(h)))
}

/// Initial bearing from point A to point B in degrees (0-360).
func bearing(from a: (Double, Double), to b: (Double, Double)) -> Double {
    let lat1 = a.0 * .pi / 180
    let lat2 = b.0 * .pi / 180
    let dLon = (b.1 - a.1) * .pi / 180

    let y = sin(dLon) * cos(lat2)
    let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    let theta = atan2(y, x)
    return (theta * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
}

/// Format distance for display: km if >=1, else meters.
func formatDistance(_ km: Double) -> String {
    if km >= 1000 {
        return String(format: "%.0f km", km)
    } else if km >= 1 {
        return String(format: "%.1f km", km)
    } else {
        return String(format: "%.0f m", km * 1000)
    }
}

/// Format bearing for display with cardinal direction.
func formatBearing(_ degrees: Double) -> String {
    let cardinals = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]
    let idx = Int((degrees + 22.5) / 45) % 8
    return String(format: "%.0f\u{00B0} %@", degrees, cardinals[idx])
}

/// Convert distance in km to miles.
func kmToMiles(_ km: Double) -> Double { km * 0.621371 }
