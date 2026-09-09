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

/// Convert a latitude/longitude (degrees) to a Maidenhead locator.
///
/// `precision` is the number of characters and must be even and >= 2 (2 = field,
/// 4 = square, 6 = subsquare, 8 = extended square); other values are clamped into
/// the 2..=8 range and rounded down to even. Inputs outside the valid coordinate
/// range are clamped (lat to ±90, lon to ±180) so a bad OS reading still yields a
/// well-formed grid rather than an out-of-alphabet character.
///
/// Mirrors the 6-char precision the app stores elsewhere (see [`grid4`]); the OS
/// location path defaults to 6 (subsquare, ~2.5 × 5 km) so it never leaks a
/// pinpoint home location.
pub fn latlon_to_maidenhead(lat: f64, lon: f64, precision: usize) -> String {
    // Clamp precision to an even value in 2..=8.
    let precision = precision.clamp(2, 8) & !1;

    // Clamp to valid ranges. 180.0/90.0 exactly would overflow the final field
    // (index 18/9) so nudge the top edge just inside the last cell.
    let lon = lon.clamp(-180.0, 179.999_999);
    let lat = lat.clamp(-90.0, 89.999_999);

    // Shift into positive space: longitude 0..360, latitude 0..180.
    let mut adj_lon = lon + 180.0;
    let mut adj_lat = lat + 90.0;

    let mut out = String::with_capacity(precision);

    // Field: A..R (20° lon / 10° lat per cell).
    out.push((b'A' + (adj_lon / 20.0) as u8) as char);
    out.push((b'A' + (adj_lat / 10.0) as u8) as char);
    adj_lon %= 20.0;
    adj_lat %= 10.0;
    if precision == 2 {
        return out;
    }

    // Square: 0..9 (2° lon / 1° lat per cell).
    out.push((b'0' + (adj_lon / 2.0) as u8) as char);
    out.push((b'0' + adj_lat as u8) as char);
    adj_lon %= 2.0;
    adj_lat %= 1.0;
    if precision == 4 {
        return out;
    }

    // Subsquare: a..x (5' lon / 2.5' lat per cell).
    out.push((b'a' + (adj_lon * 12.0) as u8) as char);
    out.push((b'a' + (adj_lat * 24.0) as u8) as char);
    if precision == 6 {
        return out;
    }

    // Extended square: 0..9 (subdividing the subsquare 10×10).
    adj_lon = (adj_lon * 12.0) % 1.0;
    adj_lat = (adj_lat * 24.0) % 1.0;
    out.push((b'0' + (adj_lon * 10.0) as u8) as char);
    out.push((b'0' + (adj_lat * 10.0) as u8) as char);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maidenhead_reference_points() {
        // Munich (JN58td) is the canonical Wikipedia worked example.
        assert_eq!(latlon_to_maidenhead(48.14666, 11.60833, 6), "JN58td");
        // 4-char truncations of the same point.
        assert_eq!(latlon_to_maidenhead(48.14666, 11.60833, 4), "JN58");
        assert_eq!(latlon_to_maidenhead(48.14666, 11.60833, 2), "JN");
        // A few other known squares.
        assert_eq!(latlon_to_maidenhead(42.3, -71.1, 4), "FN42"); // Boston
        assert_eq!(latlon_to_maidenhead(45.0, -93.5, 4), "EN35"); // Minneapolis area
    }

    #[test]
    fn maidenhead_origin_and_extremes() {
        // The Maidenhead origin sits at lon -180, lat -90 -> "AA00aa".
        assert_eq!(latlon_to_maidenhead(-90.0, -180.0, 6), "AA00aa");
        // Null Island.
        assert_eq!(latlon_to_maidenhead(0.0, 0.0, 4), "JJ00");
        // Out-of-range input is clamped, not allowed to run off the alphabet.
        let g = latlon_to_maidenhead(1000.0, 1000.0, 6);
        assert_eq!(g.len(), 6);
        assert!(g.chars().next().unwrap().is_ascii_uppercase());
    }

    #[test]
    fn maidenhead_precision_is_clamped_even() {
        // Odd / zero / oversized precisions clamp to the nearest even in 2..=8.
        assert_eq!(latlon_to_maidenhead(48.14666, 11.60833, 0).len(), 2);
        assert_eq!(latlon_to_maidenhead(48.14666, 11.60833, 5).len(), 4);
        assert_eq!(latlon_to_maidenhead(48.14666, 11.60833, 99).len(), 8);
    }

    #[test]
    fn maidenhead_is_grid4_compatible() {
        // The 6-char result truncates to the same 4-char locator grid4() expects.
        let g6 = latlon_to_maidenhead(48.14666, 11.60833, 6);
        assert_eq!(grid4(&g6), "JN58");
    }
}
