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

#ifdef __cplusplus
}
#endif

#endif // FT8AF_FFT_DISPLAY_H
