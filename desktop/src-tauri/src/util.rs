//! Small shared utilities: UTC time formatting and Maidenhead helpers.

use chrono::{TimeZone, Utc};

/// Current UTC date as ADIF `YYYYMMDD`.
pub fn utc_yyyymmdd() -> String {
    Utc::now().format("%Y%m%d").to_string()
}

/// Current UTC time as ADIF `HHMMSS`.
pub fn utc_hhmmss() -> String {
    Utc::now().format("%H%M%S").to_string()
}

/// Format a Unix-millis timestamp as ADIF `YYYYMMDD` (UTC).
pub fn yyyymmdd_from_ms(ms: i64) -> String {
    Utc.timestamp_millis_opt(ms)
        .single()
        .map(|t| t.format("%Y%m%d").to_string())
        .unwrap_or_default()
}

/// Format a Unix-millis timestamp as ADIF `HHMMSS` (UTC).
pub fn hhmmss_from_ms(ms: i64) -> String {
    Utc.timestamp_millis_opt(ms)
        .single()
        .map(|t| t.format("%H%M%S").to_string())
        .unwrap_or_default()
}

/// Current Unix time in milliseconds (UTC).
pub fn now_unix_ms() -> i64 {
    Utc::now().timestamp_millis()
}

/// Truncate a 6+ char Maidenhead grid to its 4-char locator (e.g. "FN42aa" -> "FN42").
pub fn grid4(grid: &str) -> String {
    grid.chars().take(4).collect()
}
