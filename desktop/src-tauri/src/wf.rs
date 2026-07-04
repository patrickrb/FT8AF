//! Live waterfall spectrum pipeline: windowed Welch-averaged FFT → dB →
//! noise-floor-referenced u8 rows (issue #428 developer knobs).
//!
//! Extracted from `engine.rs::emit_waterfall_row` so the row math is pure and
//! unit-testable, and so the window function, FFT size, and averaging count can
//! be changed at runtime. Defaults reproduce the previous hard-coded pipeline
//! byte-for-byte (Hann, 2048, 6 segments at 50% overlap). Display-only: the
//! decoder runs its own FFT inside the C core and is unaffected.

use std::sync::Arc;

use rustfft::num_complex::Complex;
use rustfft::{Fft, FftPlanner};
use serde::{Deserialize, Serialize};

/// Displayed top of the audio band (matches decoder f_max).
pub const WF_MAX_HZ: f32 = 3_500.0;
/// dB above the black point that maps to full brightness.
pub const WF_SPAN_DB: f32 = 26.0;
/// Black point sits this far above the noise floor so noise stays dark.
pub const WF_BLACK_OFFSET_DB: f32 = 4.0;

/// FFT sizes the developer knob may select. Anything else sanitizes to 2048.
pub const ALLOWED_FFT_SIZES: [usize; 5] = [512, 1024, 2048, 4096, 8192];

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WfWindow {
    Rect,
    Hann,
    Hamming,
    Blackman,
    BlackmanHarris,
}

impl WfWindow {
    /// Window coefficients in the periodic form (denominator N, not N-1),
    /// matching the Hann table the engine has always used:
    /// `0.5 - 0.5*cos(2πi/N)`.
    pub fn coeffs(self, n: usize) -> Vec<f32> {
        let n_f = n as f32;
        (0..n)
            .map(|i| {
                let x = 2.0 * std::f32::consts::PI * i as f32 / n_f;
                match self {
                    WfWindow::Rect => 1.0,
                    WfWindow::Hann => 0.5 - 0.5 * x.cos(),
                    WfWindow::Hamming => 0.54 - 0.46 * x.cos(),
                    WfWindow::Blackman => 0.42 - 0.5 * x.cos() + 0.08 * (2.0 * x).cos(),
                    WfWindow::BlackmanHarris => {
                        0.35875 - 0.48829 * x.cos() + 0.14128 * (2.0 * x).cos()
                            - 0.01168 * (3.0 * x).cos()
                    }
                }
            })
            .collect()
    }

    /// Stable string form used as the `wf_window` config value.
    pub fn as_str(self) -> &'static str {
        match self {
            WfWindow::Rect => "rect",
            WfWindow::Hann => "hann",
            WfWindow::Hamming => "hamming",
            WfWindow::Blackman => "blackman",
            WfWindow::BlackmanHarris => "blackman_harris",
        }
    }

    /// Parse a config value; unknown strings fall back to the default (Hann).
    pub fn parse(s: &str) -> WfWindow {
        match s {
            "rect" => WfWindow::Rect,
            "hann" => WfWindow::Hann,
            "hamming" => WfWindow::Hamming,
            "blackman" => WfWindow::Blackman,
            "blackman_harris" => WfWindow::BlackmanHarris,
            _ => WfWindow::Hann,
        }
    }
}

/// Developer-tunable waterfall FFT parameters (issue #428). Persisted in the
/// config table as `wf_window` / `wf_fft_size` / `wf_avg`.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct WfConfig {
    pub window: WfWindow,
    pub fft_size: usize,
    /// Welch segments averaged per emitted row (50% overlap).
    pub avg: usize,
}

impl Default for WfConfig {
    fn default() -> Self {
        WfConfig { window: WfWindow::Hann, fft_size: 2048, avg: 6 }
    }
}

impl WfConfig {
    /// Clamp out-of-range values to safe ones. Applied to anything arriving
    /// from the config table or the UI before it reaches the processor.
    pub fn sanitize(mut self) -> Self {
        if !ALLOWED_FFT_SIZES.contains(&self.fft_size) {
            self.fft_size = WfConfig::default().fft_size;
        }
        self.avg = self.avg.clamp(1, 16);
        self
    }

    /// Hop between averaged segments (50% overlap).
    pub fn step(&self) -> usize {
        self.fft_size / 2
    }

    /// Samples of recent audio one row consumes.
    pub fn samples_needed(&self) -> usize {
        self.fft_size + (self.avg - 1) * self.step()
    }
}

/// Owns the FFT plan, window table, and smoothed noise-floor state for the
/// live waterfall. Rebuilt whenever the config changes.
pub struct WfProcessor {
    cfg: WfConfig,
    fft: Arc<dyn Fft<f32>>,
    window: Vec<f32>,
    /// Smoothed noise-floor estimate for auto color scaling. Restarts at
    /// -60 dB on rebuild and reconverges within a couple of seconds.
    floor_db: f32,
}

impl WfProcessor {
    pub fn new(cfg: WfConfig) -> Self {
        let cfg = cfg.sanitize();
        let fft = FftPlanner::<f32>::new().plan_fft_forward(cfg.fft_size);
        let window = cfg.window.coeffs(cfg.fft_size);
        WfProcessor { cfg, fft, window, floor_db: -60.0 }
    }

    pub fn config(&self) -> WfConfig {
        self.cfg
    }

    pub fn samples_needed(&self) -> usize {
        self.cfg.samples_needed()
    }

    /// Turn the most recent `samples_needed()` samples into one waterfall row:
    /// (u8 brightness per column, Hz per column). Returns None while the ring
    /// buffer is still warming up. Color is auto-scaled relative to a smoothed
    /// noise-floor estimate so the floor stays dark regardless of input gain.
    pub fn process_row(&mut self, samples: &[f32], sample_rate: f32) -> Option<(Vec<u8>, f32)> {
        let need = self.samples_needed();
        if samples.len() < need {
            return None;
        }
        let n = self.cfg.fft_size;
        let avg = self.cfg.avg;
        let step = self.cfg.step();

        let bin_hz = sample_rate / n as f32;
        let cols = ((WF_MAX_HZ / bin_hz) as usize).min(n / 2).max(1);

        // Average `avg` overlapping FFTs (Welch) to cut the per-bin variance of
        // a single FFT, so the noise floor reads smooth instead of speckled.
        let mut power = vec![0f32; cols];
        let mut buf: Vec<Complex<f32>> = vec![Complex { re: 0.0, im: 0.0 }; n];
        for seg in 0..avg {
            let off = seg * step;
            for i in 0..n {
                buf[i] = Complex { re: samples[off + i] * self.window[i], im: 0.0 };
            }
            self.fft.process(&mut buf);
            for (i, p) in power.iter_mut().enumerate() {
                *p += buf[i].re * buf[i].re + buf[i].im * buf[i].im;
            }
        }

        // Averaged power -> dB magnitude per bin. Normalization is kept
        // identical to the legacy pipeline (2/N, no window-gain correction):
        // the floor-relative mapping below absorbs the window's constant gain.
        let mut dbs = vec![0f32; cols];
        for (i, &p) in power.iter().enumerate() {
            let a = p / avg as f32;
            let mag = (a.sqrt() * 2.0) / n as f32;
            dbs[i] = 20.0 * (mag + 1e-12).log10();
        }

        // Estimate the noise floor from the 30th-percentile dB (robust even on
        // a busy band where signals fill much of the spectrum), smoothed over time.
        let mut sorted = dbs.clone();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        let pct = sorted[((cols as f32 * 0.30) as usize).min(cols - 1)];
        self.floor_db = self.floor_db * 0.9 + pct * 0.1;

        let black_point = self.floor_db + WF_BLACK_OFFSET_DB;
        Some((map_to_u8(&dbs, black_point, WF_SPAN_DB), bin_hz))
    }
}

/// Map per-bin dB to display brightness: 0 at/below the black point, 255 at
/// black point + span, linear in between.
fn map_to_u8(dbs: &[f32], black_point: f32, span_db: f32) -> Vec<u8> {
    dbs.iter()
        .map(|&db| {
            let t = ((db - black_point) / span_db).clamp(0.0, 1.0);
            (t * 255.0) as u8
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SR: f32 = 12_000.0;

    fn tone(freq: f32, len: usize) -> Vec<f32> {
        (0..len)
            .map(|i| (2.0 * std::f32::consts::PI * freq * i as f32 / SR).sin())
            .collect()
    }

    #[test]
    fn window_coefficients_match_definitions() {
        let n = 16;
        for w in [
            WfWindow::Rect,
            WfWindow::Hann,
            WfWindow::Hamming,
            WfWindow::Blackman,
            WfWindow::BlackmanHarris,
        ] {
            let c = w.coeffs(n);
            assert_eq!(c.len(), n);
            // Periodic windows: w[i] == w[n-i] for i in 1..n.
            for i in 1..n {
                assert!(
                    (c[i] - c[n - i]).abs() < 1e-5,
                    "{w:?} not symmetric at {i}: {} vs {}",
                    c[i],
                    c[n - i]
                );
            }
        }
        assert!(WfWindow::Rect.coeffs(n).iter().all(|&v| v == 1.0));
        let hann = WfWindow::Hann.coeffs(n);
        assert!(hann[0].abs() < 1e-6);
        assert!((hann[n / 2] - 1.0).abs() < 1e-6);
        let hamming = WfWindow::Hamming.coeffs(n);
        assert!((hamming[0] - 0.08).abs() < 1e-6);
        assert!((hamming[n / 2] - 1.0).abs() < 1e-6);
        let blackman = WfWindow::Blackman.coeffs(n);
        assert!((blackman[n / 2] - 1.0).abs() < 1e-5);
        let bh = WfWindow::BlackmanHarris.coeffs(n);
        assert!(bh[0].abs() < 1e-4); // 4-term BH endpoint ≈ 6e-5
        assert!((bh[n / 2] - 1.0).abs() < 1e-4);
    }

    #[test]
    fn window_string_round_trip() {
        for w in [
            WfWindow::Rect,
            WfWindow::Hann,
            WfWindow::Hamming,
            WfWindow::Blackman,
            WfWindow::BlackmanHarris,
        ] {
            assert_eq!(WfWindow::parse(w.as_str()), w);
        }
        assert_eq!(WfWindow::parse("bogus"), WfWindow::Hann);
    }

    #[test]
    fn tone_lands_in_correct_bin_across_sizes() {
        for fft_size in [1024usize, 2048, 4096] {
            let cfg = WfConfig { window: WfWindow::Hann, fft_size, avg: 1 }.sanitize();
            let mut p = WfProcessor::new(cfg);
            let samples = tone(1000.0, p.samples_needed());
            let (bins, hz_per_col) = p.process_row(&samples, SR).unwrap();
            assert!((hz_per_col - SR / fft_size as f32).abs() < 1e-3);
            // Strong tones clamp several adjacent bins to 255, so instead of
            // comparing argmax indices, require the expected bin to be at the
            // maximum and bins a few away from it to be strictly darker.
            let expected = (1000.0 / hz_per_col).round() as usize;
            let max = *bins.iter().max().unwrap();
            assert_eq!(
                bins[expected], max,
                "fft {fft_size}: expected bin {expected} not at max"
            );
            assert!(
                bins[expected - 10] < max && bins[expected + 10] < max,
                "fft {fft_size}: tone energy not localized around bin {expected}"
            );
        }
    }

    #[test]
    fn u8_mapping_is_monotonic_and_clamped() {
        let dbs: Vec<f32> = (0..100).map(|i| -80.0 + i as f32).collect();
        let out = map_to_u8(&dbs, -50.0, WF_SPAN_DB);
        for w in out.windows(2) {
            assert!(w[1] >= w[0]);
        }
        assert_eq!(out[0], 0); // well below black point
        assert_eq!(*out.last().unwrap(), 255); // above black + span
        assert_eq!(map_to_u8(&[-50.0 + WF_SPAN_DB], -50.0, WF_SPAN_DB)[0], 255);
    }

    #[test]
    fn averaging_dilutes_a_step_transient() {
        // A tone present only in the newest FFT-length of audio: with 6-segment
        // Welch averaging its power is diluted vs a single-segment row. This is
        // the temporal-smearing mechanism issue #428 asks to make observable.
        // Amplitude is kept small so brightness stays inside the linear span
        // instead of clamping to 255 in both cases.
        let brightness_at_1k = |avg: usize| -> f32 {
            let cfg = WfConfig { window: WfWindow::Rect, fft_size: 2048, avg };
            let mut p = WfProcessor::new(cfg);
            let need = p.samples_needed();
            let mut samples = vec![0f32; need];
            let t: Vec<f32> = tone(1000.0, 2048).iter().map(|v| v * 1e-3).collect();
            samples[need - 2048..].copy_from_slice(&t);
            let (bins, hz) = p.process_row(&samples, SR).unwrap();
            bins[(1000.0 / hz).round() as usize] as f32
        };
        let single = brightness_at_1k(1);
        let averaged = brightness_at_1k(6);
        assert!(single > 0.0);
        assert!(
            averaged < single,
            "expected 6-segment average ({averaged}) below single segment ({single})"
        );
    }

    #[test]
    fn samples_needed_and_sanitize() {
        assert_eq!(WfConfig::default().samples_needed(), 7168); // 2048 + 5*1024
        let s = WfConfig { window: WfWindow::Hann, fft_size: 3000, avg: 0 }.sanitize();
        assert_eq!(s.fft_size, 2048);
        assert_eq!(s.avg, 1);
        let s = WfConfig { window: WfWindow::Rect, fft_size: 512, avg: 99 }.sanitize();
        assert_eq!(s.fft_size, 512);
        assert_eq!(s.avg, 16);
    }

    #[test]
    fn default_config_matches_legacy_constants() {
        let d = WfConfig::default();
        assert_eq!(d.window, WfWindow::Hann);
        assert_eq!(d.fft_size, 2048);
        assert_eq!(d.avg, 6);
        assert_eq!(d.step(), 1024);
        // The Hann table must match the old inline formula from engine.rs.
        let table = WfWindow::Hann.coeffs(2048);
        for &i in &[0usize, 1, 511, 1024, 2047] {
            let legacy =
                0.5 - 0.5 * (2.0 * std::f32::consts::PI * i as f32 / 2048.0).cos();
            assert!((table[i] - legacy).abs() < 1e-7, "hann mismatch at {i}");
        }
    }
}
