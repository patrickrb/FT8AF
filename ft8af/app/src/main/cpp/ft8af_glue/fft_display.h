// Waterfall display intensity mapping — extracted from ft8_fft_jni.cpp so it can
// be unit-tested on the host (it has no JNI / FFT dependency; it is pure data in,
// data out). See fft_display.c for the rationale.

#ifndef FT8AF_FFT_DISPLAY_H
#define FT8AF_FFT_DISPLAY_H

#ifdef __cplusplus
extern "C" {
#endif

// Map linear FFT bin magnitudes to 0..255 display intensities on a LOGARITHMIC
// (dB) scale referenced to the frame's noise floor (median dB).
//
//   mag      [in]  n_bins linear magnitudes (sqrt(re^2+im^2)), >= 0
//   n_bins   [in]  number of bins (no-op if <= 0)
//   output   [out] n_bins intensities clamped to 0..255
//   denoise  [in]  0 = raw view (noise floor kept faintly visible);
//                  non-0 = also subtract a local band-tilt estimate and push the
//                          noise floor to black.
void ft8af_magnitudes_to_display(const float* mag, int n_bins, int* output, int denoise);

// --- FFT display window functions + frame averaging (issue #428) -----------
//
// Developer-selectable pre-FFT windows for the display path. Values are wire
// format: they are stored in the config DB and passed through JNI as ints, so
// they must stay stable.
typedef enum {
    FT8AF_WIN_RECT = 0,            // no window (the pre-#428 behavior)
    FT8AF_WIN_HANN = 1,            // default; matches the desktop/iOS pipeline
    FT8AF_WIN_HAMMING = 2,
    FT8AF_WIN_BLACKMAN = 3,
    FT8AF_WIN_BLACKMAN_HARRIS = 4, // 4-term minimum-sidelobe
} ft8af_window_type;

// Fill w[0..n-1] with window coefficients (symmetric form, denominator n-1).
// Unknown types fall back to rectangular. No-op if n <= 0; n == 1 yields 1.0.
//
// Windowing scales every bin by the window's coherent gain (~0.5 for Hann),
// but ft8af_magnitudes_to_display() maps dB RELATIVE to the frame's median
// noise floor, so the constant gain cancels — no renormalization is needed.
void ft8af_window_fill(int type, float* w, int n);

// out[i] = in[i] * w[i]. out may alias in. No-op if n <= 0.
void ft8af_window_apply(const float* w, const float* in, float* out, int n);

// Exponential moving average across display frames, on linear magnitudes
// (the closest cross-frame analog to Welch power averaging):
//   acc[i] = alpha*mag[i] + (1-alpha)*acc[i]
// alpha in (0,1]; alpha == 1 is a passthrough copy. No-op if n <= 0.
void ft8af_mag_ema(float* acc, const float* mag, int n, float alpha);

#ifdef __cplusplus
}
#endif

#endif // FT8AF_FFT_DISPLAY_H
