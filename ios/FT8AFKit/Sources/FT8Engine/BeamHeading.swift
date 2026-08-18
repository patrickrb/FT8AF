import Foundation

/// Great-circle beam heading from the operator's grid to a remote station —
/// the initial bearing to point a directional antenna the short way round.
/// Port of Android `BeamHeading.kt`; the trig is pure so it host-tests without
/// any Maidenhead/Play-Services types.

/// Initial great-circle bearing (degrees, `[0, 360)`) from point 1 to point 2
/// using the standard forward-azimuth formula. The final `% 360` means 360 is
/// never returned (a due-north bearing is 0).
public func greatCircleBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double) -> Double {
    let phi1 = lat1 * .pi / 180
    let phi2 = lat2 * .pi / 180
    let dLon = (lon2 - lon1) * .pi / 180
    let y = sin(dLon) * cos(phi2)
    let x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
    return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
}

/// Long-path heading is the reciprocal of the short-path initial bearing.
public func longPathBearing(_ shortPathDeg: Double) -> Double {
    (shortPathDeg + 180).truncatingRemainder(dividingBy: 360)
}

/// Round a bearing to whole degrees in `0..359` (360 wraps back to 0).
public func normalizeHeadingDeg(_ deg: Double) -> Int {
    (Int(deg.rounded()) % 360 + 360) % 360
}

/// Short-path beam heading in whole degrees from `myGrid` to `theirGrid`, or nil
/// when either grid is missing/unparseable or the two points coincide (a bearing
/// to yourself is undefined). Uses the shared `gridToLatLon`, which also rejects
/// the "RR73"/"RR" sign-off tokens.
public func beamHeadingShortPathDeg(fromGrid myGrid: String, toGrid theirGrid: String) -> Int? {
    guard !myGrid.isEmpty, !theirGrid.isEmpty,
          let me = gridToLatLon(myGrid), let them = gridToLatLon(theirGrid) else { return nil }
    if me.0 == them.0 && me.1 == them.1 { return nil }
    return normalizeHeadingDeg(greatCircleBearing(lat1: me.0, lon1: me.1, lat2: them.0, lon2: them.1))
}

/// Format a whole-degree heading as e.g. "47°".
public func formatHeading(_ deg: Int) -> String { "\(deg)°" }

/// Short-path beam-heading text ("47°") from `myGrid` to `theirGrid`, or "" when
/// unavailable. Convenience wrapper for the decode row / QSO sheet.
public func beamHeadingText(fromGrid myGrid: String, toGrid theirGrid: String) -> String {
    guard let deg = beamHeadingShortPathDeg(fromGrid: myGrid, toGrid: theirGrid) else { return "" }
    return formatHeading(deg)
}
