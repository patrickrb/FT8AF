import Foundation

/// UTC date/time helpers for logging (port of the desktop util:: time functions).
enum FT8Time {
    private static var utcCalendar: Calendar = {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal
    }()

    static func nowUnixMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    static func yyyymmdd(fromMs ms: Int64) -> String {
        let d = Date(timeIntervalSince1970: Double(ms) / 1000.0)
        let c = utcCalendar.dateComponents([.year, .month, .day], from: d)
        return String(format: "%04d%02d%02d", c.year!, c.month!, c.day!)
    }

    static func hhmmss(fromMs ms: Int64) -> String {
        let d = Date(timeIntervalSince1970: Double(ms) / 1000.0)
        let c = utcCalendar.dateComponents([.hour, .minute, .second], from: d)
        return String(format: "%02d%02d%02d", c.hour!, c.minute!, c.second!)
    }

    static func utcYyyymmdd() -> String { yyyymmdd(fromMs: nowUnixMs()) }
    static func utcHhmmss() -> String { hhmmss(fromMs: nowUnixMs()) }
}

/// Truncate a Maidenhead locator to its 4-char field/square (port of util::grid4).
func grid4(_ s: String) -> String { String(s.prefix(4)) }
