//! FT8 band plan: standard dial frequencies and band-name lookup.

#[derive(Debug, Clone, Copy)]
pub struct Band {
    pub name: &'static str,
    /// Standard FT8 dial (USB suppressed-carrier) frequency in Hz.
    pub dial_hz: u64,
    /// Inclusive band edges (Hz) for naming an arbitrary frequency.
    pub lo_hz: u64,
    pub hi_hz: u64,
}

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
