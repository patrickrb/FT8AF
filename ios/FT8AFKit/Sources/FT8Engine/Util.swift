import Foundation

/// UTC date/time helpers for logging (port of the desktop util:: time functions).
enum FT8Time {
    // `.gmt` is non-optional (iOS 16+/macOS 13+), and `Calendar.component(_:from:)`
    // returns non-optional Ints — so no force-unwraps anywhere here.
    private static let utcCalendar: Calendar = {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .gmt
        return cal
    }()

    static func nowUnixMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    static func yyyymmdd(fromMs ms: Int64) -> String {
        let d = Date(timeIntervalSince1970: Double(ms) / 1000.0)
        let y = utcCalendar.component(.year, from: d)
        let mo = utcCalendar.component(.month, from: d)
        let da = utcCalendar.component(.day, from: d)
        return String(format: "%04d%02d%02d", y, mo, da)
    }

    static func hhmmss(fromMs ms: Int64) -> String {
        let d = Date(timeIntervalSince1970: Double(ms) / 1000.0)
        let h = utcCalendar.component(.hour, from: d)
        let mi = utcCalendar.component(.minute, from: d)
        let s = utcCalendar.component(.second, from: d)
        return String(format: "%02d%02d%02d", h, mi, s)
    }

    static func utcYyyymmdd() -> String { yyyymmdd(fromMs: nowUnixMs()) }
    static func utcHhmmss() -> String { hhmmss(fromMs: nowUnixMs()) }
}

/// Truncate a Maidenhead locator to its 4-char field/square (port of util::grid4).
func grid4(_ s: String) -> String { String(s.prefix(4)) }

/// Decode a Maidenhead locator to a `(latitude, longitude)` centre in degrees, or
/// `nil` when the field/square characters are malformed.
///
/// Handles both 4-character (`FN42`) and 6-character (`IO91wm`) grids. A 4-character
/// grid resolves to the centre of its 2° × 1° square; a 6-character grid adds the
/// subsquare offset — each subsquare letter (`a`–`x`, 24 per axis) spans 2°/24 of
/// longitude and 1°/24 of latitude, and the `+ 0.5` lands on the subsquare centre.
///
/// This mirrors the Android decoder (`MaidenheadGrid.gridToLatLng`, whose
/// `SUBSQUARES = 24` was fixed in the app; a wrong divisor / dropping the subsquare
/// entirely misplaces a 6-character grid by up to ~55 km — e.g. `IO91wm` (London,
/// true −0.125° lon) would otherwise resolve to the `IO91` square centre at −1.0°,
/// the wrong side of the prime meridian). The 4-character result is unchanged from
/// the previous app-local implementation.
public func gridToLatLon(_ grid: String) -> (Double, Double)? {
    let chars = Array(grid.uppercased().utf8)
    guard chars.count >= 4,
          chars[0] >= 65, chars[0] <= 82,   // field:  A–R
          chars[1] >= 65, chars[1] <= 82,
          chars[2] >= 48, chars[2] <= 57,   // square: 0–9
          chars[3] >= 48, chars[3] <= 57 else { return nil }

    var lon = Double(chars[0] - 65) * 20 + Double(chars[2] - 48) * 2 - 180
    var lat = Double(chars[1] - 65) * 10 + Double(chars[3] - 48) - 90

    // Subsquare (chars 4–5, letters a–x → A–X once upper-cased). Only apply it when
    // both subsquare characters are valid; otherwise fall back to the 4-char square
    // centre so a malformed 6th/5th character can never mislocate the grid.
    if chars.count >= 6,
       chars[4] >= 65, chars[4] <= 88,      // subsquare: A–X (24 letters)
       chars[5] >= 65, chars[5] <= 88 {
        lon += (Double(chars[4] - 65) + 0.5) * (2.0 / 24.0)
        lat += (Double(chars[5] - 65) + 0.5) * (1.0 / 24.0)
    } else {
        lon += 1.0   // centre of the 2° longitude square
        lat += 0.5   // centre of the 1° latitude square
    }
    return (lat, lon)
}
