# FFT display pipeline & developer knobs (issue #428)

Issue #428 reports ringing/smearing/ghosting on the spectrum and waterfall when
the received signal jumps from near-silence to strong. This document records
how each platform turns audio into the on-screen spectrum/waterfall, what the
defaults are and why, and which parameters are configurable so the artifact can
be evaluated without code changes.

**Everything here is display-only.** On every platform the FT8/FT4 decoder runs
its own independent FFT (Hann-windowed `monitor.c` inside the C core); none of
these knobs affect decoding.

## The likely mechanism

Before #428, Android applied **no window function** (rectangular) to the
display FFT. A strong tone that isn't exactly on a bin boundary then leaks
sinc-shaped sidelobes across the entire row — measured in the host test
(`test_fft_window.c`): skirts only ~28 dB below the peak with a rectangular
window vs ~70 dB with Hann, at the display path's exact geometry (1920 samples
@ 12 kHz). On a dB-scaled waterfall with a 50 dB visible range, a signal
~30 dB above the floor paints the whole row — which reads as "false energy /
smearing when a strong signal starts". Desktop and iOS always used Hann.

Android's default is now **Hann**, matching the other platforms; rectangular
remains selectable to reproduce the old behavior for A/B comparison.

## Defaults per platform

| Parameter | Android | Desktop | iOS (unchanged, for reference) |
|---|---|---|---|
| Window | **Hann** (was: none) — configurable | Hann — configurable | Hann — fixed |
| FFT size | 1920 (= 160 ms @ 12 kHz, 6.25 Hz/bin) — fixed, see below | 2048 (≈5.86 Hz/bin) — configurable 512–8192 | 2048 — fixed |
| Averaging | Off — configurable (cross-frame EMA 0.5 / 0.25) | 6-segment Welch, 50 % overlap — configurable 1–16 | 6-segment Welch — fixed |
| Scaling | dB, median-bin noise floor, 50 dB range (`fft_display.c`) | dB, 30th-pct floor + EMA 0.9/0.1, 26 dB span, 4 dB black offset | same as desktop |
| Bins→pixels | Spectrum strip: pair combine (Max default) — configurable Max/Avg/RMS. Waterfall: LinearGradient interpolation (see below) | 1 bin = 1 canvas column; browser scales. No aggregation step | 1 bin = 1 column |

## Android

Pipeline: `SpectrumListener` (1920 float samples per 160 ms, ~6 fps) →
`WaterfallScreen.kt` observer → `FFTBridge` → JNI `getFFTData*`
(`ft8af_glue/ft8_fft_jni.cpp`) → window (`ft8af_window_fill/apply`) → kissfft
real FFT → optional cross-frame EMA (`ft8af_mag_ema`) →
`ft8af_magnitudes_to_display` (dB, median noise floor, 0–255) →
`ColumnarView` (spectrum strip) + `WaterfallView` (scrolling bitmap).

Settings → Advanced → **FFT / Waterfall (developer)**:

- **FFT window function** — Rectangular / Hann (default) / Hamming / Blackman /
  Blackman-Harris. Applied in native code before the FFT; the window's constant
  coherent gain cancels in the noise-floor-relative dB mapping, so no
  renormalization is needed.
- **Frame averaging** — Off (default) / Light (EMA α=0.5) / Heavy (EMA α=0.25),
  applied to linear magnitudes across successive display frames. Off is the
  default deliberately: temporal smoothing is precisely the effect under
  investigation, so it must be opt-in. (Welch averaging *within* a frame, as
  desktop does, isn't possible on Android: there is exactly one 1920-sample
  buffer per display frame.)
- **Spectrum bin combining** — Maximum (default, the legacy behavior) / Average
  / RMS: how `ColumnarView` merges each FFT bin with its right neighbor into
  one bar (`BinAggregation.java`). Inputs are already dB-scaled 0–255 display
  intensities, so Avg/RMS are display heuristics for comparison, not physics.

Persisted as config keys `fftWindowType`, `fftAveragingMode`,
`spectrumBinAggregation`; hydrated in `DatabaseOpr.getAllConfigParameter` with
clamping setters in `GeneralVariables`, and included in settings
backup/restore automatically.

**Why FFT size is not a knob on Android.** The FFT length equals the capture
frame (1920 samples), giving 6.25 Hz bins — exactly the FT8 tone spacing, which
is a meaningful, defensible resolution. Zero-padding to 2048/4096 adds
interpolation but no resolution, and breaks the `input.size/2` output-array
contract at three call sites; capturing longer frames would gain resolution but
halve the update rate and *increase* temporal smear — the opposite of what the
issue asks for. If a future need arises, zero-padding is the practical option.

**Waterfall interpolation note.** `WaterfallView` renders each row as a
`LinearGradient` with one color stop per bin; the GPU interpolates between
stops. That is itself a smoothing step (bins never map 1:1 to pixels there),
worth remembering when judging "smearing" by eye — the spectrum strip, which
has the configurable combine, is the more faithful per-bin view.

Tests: `ft8af_glue/test_fft_window.c` (window shapes, unknown-type fallback,
rect-vs-Hann leakage, EMA math; run via `run_host_tests.ps1`/`.sh`),
`BinAggregationTest.java`, `GeneralVariablesFftSettingsTest.kt`,
`FftDisplaySettingsTest.kt`.

## Desktop

See the "Waterfall display pipeline (developer settings)" section of
`desktop/README.md`. Summary: the row math lives in the pure, unit-tested
`desktop/src-tauri/src/wf.rs`; Settings → *Developer — waterfall FFT* exposes
window / FFT size / averaging count; changes apply live via
`EngineCommand::SetWaterfallConfig` and persist as `wf_window`, `wf_fft_size`,
`wf_avg`. Defaults (Hann / 2048 / 6) reproduce the previous hard-coded pipeline
byte-for-byte. Bins-per-pixel aggregation doesn't exist on desktop (1 bin =
1 canvas column, scaled by the browser) — documented rather than knobbed.

## iOS

Out of scope for the #428 implementation round (no Mac in the loop); its
pipeline (`FFTProcessor.swift` + `WaterfallRowBuilder.swift`) already matches
the desktop defaults (Hann, 2048, Welch×6, same dB/floor mapping), so the
cross-platform default behavior is consistent today. Making its parameters
configurable can follow the desktop pattern (thread a config through
`LiveEngine.runWaterfallLoop`) in a later PR.

## Evaluating configurations

Reproduce the reported artifact with a controlled signal (near-silence, then a
strong FT8 transmission or tone burst mid-band, slightly off a 6.25 Hz
multiple):

- **Rect vs Hann (Android):** with Rectangular, the onset of the strong signal
  brightens the entire row; with Hann the energy stays within a few bins.
- **Averaging:** desktop `avg 6 → 1` restores per-row speckle and sharpens
  signal onsets; Android `Off → Heavy` visibly delays onset/decay (ghosting) —
  useful to bracket how much smear averaging alone can cause.
- **FFT size (desktop):** larger sizes narrow each signal and slow the row
  cadence; watch for the artifact scaling (leakage) vs staying constant
  (rendering).
