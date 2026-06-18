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
