//! FT8 band plan: standard dial frequencies and band-name lookup, plus
//! user-defined "custom" dial frequencies (issue #470) for chasing DXpeditions
//! on off-plan frequencies. Custom bands are persisted as JSON in the config
//! store and merged into the picker alongside the built-in [`FT8_BANDS`].

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy)]
pub struct Band {
    pub name: &'static str,
    /// Standard FT8 dial (USB suppressed-carrier) frequency in Hz.
    pub dial_hz: u64,
    /// Inclusive band edges (Hz) for naming an arbitrary frequency.
    pub lo_hz: u64,
    pub hi_hz: u64,
}

/// A user-defined dial frequency (issue #470). Persisted as a JSON array under
/// the `custom_bands` config key and merged into the band picker so operators
/// can tune to published DXpedition/off-plan dials that aren't in the band plan.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CustomBand {
    /// Optional operator label (e.g. "3B9 DX"). Falls back to the band name.
    pub name: String,
    pub dial_hz: u64,
}

/// A band entry as sent to the UI picker: built-in dials and custom dials share
/// one flat list, `custom` flagging the user-defined ones so the UI can mark
/// them (and offer delete).
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct BandEntry {
    pub name: String,
    pub dial_hz: u64,
    pub custom: bool,
}

/// Config key under which the custom-band JSON list is stored (see `db.rs`).
pub const CUSTOM_BANDS_KEY: &str = "custom_bands";

/// Accepted dial-frequency range for validation, in Hz. The lower bound keeps
/// out obvious typos; the upper bound comfortably covers the microwave dials in
/// the band plan (QO-100 3cm ~10.489 GHz).
pub const MIN_DIAL_HZ: u64 = 1_000;
pub const MAX_DIAL_HZ: u64 = 30_000_000_000;

pub const FT8_BANDS: &[Band] = &[
    Band { name: "160m", dial_hz: 1_840_000, lo_hz: 1_800_000, hi_hz: 2_000_000 },
    Band { name: "80m", dial_hz: 3_573_000, lo_hz: 3_500_000, hi_hz: 4_000_000 },
    Band { name: "60m", dial_hz: 5_357_000, lo_hz: 5_300_000, hi_hz: 5_410_000 },
    Band { name: "40m", dial_hz: 7_074_000, lo_hz: 7_000_000, hi_hz: 7_300_000 },
    Band { name: "30m", dial_hz: 10_136_000, lo_hz: 10_100_000, hi_hz: 10_150_000 },
    Band { name: "20m", dial_hz: 14_074_000, lo_hz: 14_000_000, hi_hz: 14_350_000 },
    Band { name: "17m", dial_hz: 18_100_000, lo_hz: 18_068_000, hi_hz: 18_168_000 },
    Band { name: "15m", dial_hz: 21_074_000, lo_hz: 21_000_000, hi_hz: 21_450_000 },
    Band { name: "12m", dial_hz: 24_915_000, lo_hz: 24_890_000, hi_hz: 24_990_000 },
    Band { name: "10m", dial_hz: 28_074_000, lo_hz: 28_000_000, hi_hz: 29_700_000 },
    Band { name: "6m", dial_hz: 50_313_000, lo_hz: 50_000_000, hi_hz: 54_000_000 },
    Band { name: "2m", dial_hz: 144_174_000, lo_hz: 144_000_000, hi_hz: 148_000_000 },
];

/// ADIF band name for a dial frequency in Hz (e.g. 14_074_000 -> "20m").
pub fn band_for_freq_hz(hz: u64) -> &'static str {
    for b in FT8_BANDS {
        if hz >= b.lo_hz && hz <= b.hi_hz {
            return b.name;
        }
    }
    "unknown"
}

/// Frequency in MHz as an ADIF-style string, e.g. 14_074_000 -> "14.074000".
pub fn freq_mhz_string(hz: u64) -> String {
    format!("{:.6}", hz as f64 / 1_000_000.0)
}

pub fn default_band() -> &'static Band {
    // 20m — the most active FT8 band.
    &FT8_BANDS[5]
}

// --- custom (user-defined) dial frequencies (issue #470) --------------------

/// Parse an operator-entered dial frequency (whole Hz) and validate its range,
/// returning a human-readable message on bad input so the UI can surface it.
pub fn parse_dial_hz(input: &str) -> Result<u64, String> {
    let t = input.trim();
    if t.is_empty() {
        return Err("Enter a dial frequency in Hz.".to_string());
    }
    let hz: u64 = t
        .parse()
        .map_err(|_| format!("\"{t}\" is not a whole number of Hz."))?;
    if hz < MIN_DIAL_HZ || hz > MAX_DIAL_HZ {
        return Err(format!(
            "{hz} Hz is out of range ({MIN_DIAL_HZ}–{MAX_DIAL_HZ} Hz)."
        ));
    }
    Ok(hz)
}

/// Deserialize the persisted custom-band list. An empty/malformed string (older
/// build, hand-edited DB) yields an empty list rather than an error, so a bad
/// value can never wipe out the built-in bands.
pub fn parse_custom_bands(json: &str) -> Vec<CustomBand> {
    if json.trim().is_empty() {
        return Vec::new();
    }
    serde_json::from_str(json).unwrap_or_default()
}

/// Serialize the custom-band list for persistence.
pub fn serialize_custom_bands(bands: &[CustomBand]) -> String {
    serde_json::to_string(bands).unwrap_or_else(|_| "[]".to_string())
}

/// Add a custom band, or rename the existing one at the same dial. The label is
/// trimmed; a blank label falls back to the ADIF band name for that frequency
/// (e.g. "20m") so every entry shows something meaningful.
pub fn upsert_custom_band(bands: &mut Vec<CustomBand>, name: &str, dial_hz: u64) {
    let trimmed = name.trim();
    let label = if trimmed.is_empty() {
        band_for_freq_hz(dial_hz).to_string()
    } else {
        trimmed.to_string()
    };
    if let Some(existing) = bands.iter_mut().find(|b| b.dial_hz == dial_hz) {
        existing.name = label;
    } else {
        bands.push(CustomBand { name: label, dial_hz });
    }
}

/// Remove the custom band at `dial_hz`. Returns true if one was removed.
pub fn remove_custom_band(bands: &mut Vec<CustomBand>, dial_hz: u64) -> bool {
    let before = bands.len();
    bands.retain(|b| b.dial_hz != dial_hz);
    bands.len() != before
}

/// The flat picker list: every built-in dial followed by the custom dials, with
/// custom entries de-duplicated against built-ins by dial frequency so a custom
/// entry that duplicates a stock dial doesn't appear twice.
pub fn merged_bands(custom: &[CustomBand]) -> Vec<BandEntry> {
    let mut out: Vec<BandEntry> = FT8_BANDS
        .iter()
        .map(|b| BandEntry {
            name: b.name.to_string(),
            dial_hz: b.dial_hz,
            custom: false,
        })
        .collect();
    for c in custom {
        if out.iter().any(|b| b.dial_hz == c.dial_hz) {
            continue;
        }
        out.push(BandEntry {
            name: c.name.clone(),
            dial_hz: c.dial_hz,
            custom: true,
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_dial_hz_accepts_in_range() {
        assert_eq!(parse_dial_hz("14074000"), Ok(14_074_000));
        assert_eq!(parse_dial_hz("  7074000 "), Ok(7_074_000));
        // Microwave dial from the band plan is still in range.
        assert_eq!(parse_dial_hz("10489540000"), Ok(10_489_540_000));
    }

    #[test]
    fn parse_dial_hz_rejects_bad_input() {
        assert!(parse_dial_hz("").is_err());
        assert!(parse_dial_hz("abc").is_err());
        assert!(parse_dial_hz("14.074").is_err()); // not whole Hz
        assert!(parse_dial_hz("-5").is_err());
        assert!(parse_dial_hz("100").is_err()); // below MIN_DIAL_HZ
        assert!(parse_dial_hz("40000000000").is_err()); // above MAX_DIAL_HZ
    }

    #[test]
    fn custom_bands_round_trip() {
        let bands = vec![
            CustomBand { name: "3B9 DX".into(), dial_hz: 14_090_000 },
            CustomBand { name: "40m DX".into(), dial_hz: 7_090_000 },
        ];
        let json = serialize_custom_bands(&bands);
        assert_eq!(parse_custom_bands(&json), bands);
    }

    #[test]
    fn parse_custom_bands_tolerates_junk() {
        assert!(parse_custom_bands("").is_empty());
        assert!(parse_custom_bands("   ").is_empty());
        assert!(parse_custom_bands("not json").is_empty());
    }

    #[test]
    fn upsert_adds_then_renames_same_dial() {
        let mut bands = Vec::new();
        upsert_custom_band(&mut bands, "DX", 14_090_000);
        assert_eq!(bands.len(), 1);
        // Same dial -> rename in place, not a duplicate.
        upsert_custom_band(&mut bands, "3B9 DX", 14_090_000);
        assert_eq!(bands.len(), 1);
        assert_eq!(bands[0].name, "3B9 DX");
    }

    #[test]
    fn upsert_blank_label_falls_back_to_band_name() {
        let mut bands = Vec::new();
        upsert_custom_band(&mut bands, "   ", 14_090_000);
        assert_eq!(bands[0].name, "20m");
    }

    #[test]
    fn remove_custom_band_reports_hit_and_miss() {
        let mut bands = vec![CustomBand { name: "DX".into(), dial_hz: 14_090_000 }];
        assert!(!remove_custom_band(&mut bands, 7_000_000));
        assert!(remove_custom_band(&mut bands, 14_090_000));
        assert!(bands.is_empty());
    }

    #[test]
    fn merged_bands_appends_custom_and_flags_them() {
        let custom = vec![CustomBand { name: "3B9 DX".into(), dial_hz: 14_090_000 }];
        let merged = merged_bands(&custom);
        assert_eq!(merged.len(), FT8_BANDS.len() + 1);
        let last = merged.last().unwrap();
        assert_eq!(last.dial_hz, 14_090_000);
        assert_eq!(last.name, "3B9 DX");
        assert!(last.custom);
        // Built-ins keep their order and are not flagged custom.
        assert_eq!(merged[0].name, "160m");
        assert!(!merged[0].custom);
    }

    #[test]
    fn merged_bands_dedupes_custom_matching_builtin_dial() {
        // A custom entry on a stock dial (20m = 14.074) must not double the row.
        let custom = vec![CustomBand { name: "dup".into(), dial_hz: 14_074_000 }];
        let merged = merged_bands(&custom);
        assert_eq!(merged.len(), FT8_BANDS.len());
        assert_eq!(merged.iter().filter(|b| b.dial_hz == 14_074_000).count(), 1);
    }
}
