// FFT display JNI wrappers — from-source replacement for libft8cn.so.
//
// Replaces the prebuilt's getFFTData/getFFTDataFloat/getFFTDataRaw/getFFTDataRawFloat
// entry points on both SpectrumView and SpectrumFragment. These are instance methods
// (non-static) on their respective Java classes, so the JNI signatures include `jobject`
// as the second parameter.
//
// Each method takes audio samples (int[] or float[]) and writes FFT magnitude data
// into an output int[]. The output array is inputLength/2 entries (one per frequency bin).
// "Raw" variants return unprocessed magnitudes; non-raw variants apply a running-average
// noise floor estimate and subtract it (denoising).
//
// Java side:
//   com.k1af.ft8af.ui.SpectrumView    (instance methods)
//   com.k1af.ft8af.ui.SpectrumFragment (instance methods)
//
// DISPLAY KNOBS (issue #428): before the FFT the input is multiplied by a
// selectable window function (default Hann; rectangular reproduces the
// pre-#428 behavior, whose spectral leakage smears strong-signal onsets across
// the row), and after the FFT the linear magnitudes can be smoothed with a
// cross-frame EMA (default off). Both are process-wide state set via the
// static native SpectrumFragment.setFFTDisplayParams(windowType, avgMode) —
// the existing getFFTData* signatures mirror the retired prebuilt libft8cn.so
// and are left untouched. All calls (setter + compute) happen on the app's
// main thread (the WaterfallScreen LiveData observer), so the globals need no
// locking.

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

extern "C" {
#include "fft/kiss_fftr.h"
}
// fft_display.h guards its own C linkage (extern "C"), so include it outside the
// kissfft block rather than nesting it under this one.
#include "fft_display.h"

// ---------------------------------------------------------------------------
// Display-pipeline state (issue #428). Main-thread only — the setter and every
// getFFTData* call run on the WaterfallScreen LiveData observer's thread.
// ---------------------------------------------------------------------------
static int g_window_type = FT8AF_WIN_HANN; // default matches desktop/iOS
static int g_avg_mode = 0;                 // 0=off, 1=EMA a=0.5, 2=EMA a=0.25

// Window coefficient cache, rebuilt when (type, nfft) changes.
static float* g_window = nullptr;
static int g_window_type_cached = -1;
static int g_window_n = 0;

// Cross-frame EMA accumulator over linear magnitudes; reset (re-primed from
// the next frame) when the bin count or the averaging mode changes.
static float* g_ema_acc = nullptr;
static int g_ema_n = 0;
static int g_ema_mode = 0;
static bool g_ema_primed = false;

extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ui_SpectrumFragment_setFFTDisplayParams(
        JNIEnv*, jclass, jint windowType, jint averagingMode)
{
    g_window_type = (int)windowType;
    if ((int)averagingMode != g_avg_mode) {
        g_avg_mode = (int)averagingMode;
        g_ema_primed = false; // restart smoothing cleanly on mode change
    }
}

// ---------------------------------------------------------------------------
// Core FFT computation: time-domain samples → magnitude array.
//
// input:   float[n] audio samples
// output:  int[n/2] scaled magnitudes (0..255 range for waterfall display)
// denoise: if true, subtract a running-average noise floor estimate
// ---------------------------------------------------------------------------
static void compute_fft_magnitudes(const float* input, int n, int* output, bool denoise)
{
    if (n <= 0) return;

    int nfft = n;
    // kiss_fftr requires even nfft
    if (nfft % 2 != 0) nfft--;
    if (nfft <= 0) return;

    int n_bins = nfft / 2;

    kiss_fftr_cfg cfg = kiss_fftr_alloc(nfft, 0, nullptr, nullptr);
    if (!cfg) return;

    kiss_fft_cpx* freq = (kiss_fft_cpx*)malloc((n_bins + 1) * sizeof(kiss_fft_cpx));
    if (!freq) {
        kiss_fftr_free(cfg);
        return;
    }

    // Apply the selected pre-FFT window (issue #428). Rect skips the multiply
    // and any (re)allocation. The window's constant coherent gain cancels in
    // ft8af_magnitudes_to_display's noise-floor-relative mapping.
    const float* fft_in = input;
    float* windowed = nullptr;
    if (g_window_type != FT8AF_WIN_RECT) {
        if (g_window_type != g_window_type_cached || nfft != g_window_n) {
            float* w = (float*)realloc(g_window, nfft * sizeof(float));
            if (w) {
                g_window = w;
                ft8af_window_fill(g_window_type, g_window, nfft);
                g_window_type_cached = g_window_type;
                g_window_n = nfft;
            }
        }
        if (g_window && g_window_n == nfft) {
            windowed = (float*)malloc(nfft * sizeof(float));
            if (windowed) {
                ft8af_window_apply(g_window, input, windowed, nfft);
                fft_in = windowed;
            }
        }
    }

    kiss_fftr(cfg, fft_in, freq);
    free(windowed);

    // Compute the linear magnitude for each bin.
    float* mag = (float*)malloc(n_bins * sizeof(float));
    if (!mag) {
        free(freq);
        kiss_fftr_free(cfg);
        return;
    }
    for (int i = 0; i < n_bins; ++i)
        mag[i] = sqrtf(freq[i].r * freq[i].r + freq[i].i * freq[i].i);

    // Optional cross-frame EMA over linear magnitudes (issue #428) — the
    // closest cross-frame analog to the desktop's within-capture Welch
    // averaging. Off by default: temporal smoothing is exactly the effect
    // under investigation, so it must be opt-in.
    if (g_avg_mode > 0) {
        if (g_ema_n != n_bins || g_ema_mode != g_avg_mode) {
            float* acc = (float*)realloc(g_ema_acc, n_bins * sizeof(float));
            if (acc) {
                g_ema_acc = acc;
                g_ema_n = n_bins;
                g_ema_mode = g_avg_mode;
                g_ema_primed = false;
            }
        }
        if (g_ema_acc && g_ema_n == n_bins) {
            if (!g_ema_primed) {
                memcpy(g_ema_acc, mag, n_bins * sizeof(float)); // copy-through prime
                g_ema_primed = true;
            } else {
                ft8af_mag_ema(g_ema_acc, mag, n_bins,
                              g_avg_mode == 1 ? 0.5f : 0.25f);
            }
            memcpy(mag, g_ema_acc, n_bins * sizeof(float));
        }
    }

    // Map to 0..255 on a logarithmic (dB), noise-floor-referenced scale. Linear
    // scaling here made the waterfall blank; see fft_display.c.
    ft8af_magnitudes_to_display(mag, n_bins, output, denoise ? 1 : 0);

    free(mag);
    free(freq);
    kiss_fftr_free(cfg);
}

// ===========================================================================
// SpectrumView methods (instance, jobject this)
// ===========================================================================

// getFFTData: int16 input (as int[]), denoised output
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ui_SpectrumView_getFFTData(
        JNIEnv* env, jobject, jintArray data, jintArray fftData)
{
    jsize n = env->GetArrayLength(data);
    jint* in = env->GetIntArrayElements(data, nullptr);
    if (!in) return;

    // Convert int16 samples to float
    float* fbuf = (float*)malloc(n * sizeof(float));
    if (!fbuf) { env->ReleaseIntArrayElements(data, in, JNI_ABORT); return; }
    for (jsize i = 0; i < n; ++i)
        fbuf[i] = (float)in[i] / 32768.0f;
    env->ReleaseIntArrayElements(data, in, JNI_ABORT);

    jsize out_len = env->GetArrayLength(fftData);
    jint* out = env->GetIntArrayElements(fftData, nullptr);
    if (!out) { free(fbuf); return; }

    int bins = n / 2;
    if (bins > out_len) bins = out_len;
    compute_fft_magnitudes(fbuf, n, out, true);

    env->ReleaseIntArrayElements(fftData, out, 0);
    free(fbuf);
}

// getFFTDataFloat: float input, denoised output
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ui_SpectrumView_getFFTDataFloat(
        JNIEnv* env, jobject, jfloatArray data, jintArray fftData)
{
    jsize n = env->GetArrayLength(data);
    jfloat* in = env->GetFloatArrayElements(data, nullptr);
    if (!in) return;

    jsize out_len = env->GetArrayLength(fftData);
    jint* out = env->GetIntArrayElements(fftData, nullptr);
    if (!out) { env->ReleaseFloatArrayElements(data, in, JNI_ABORT); return; }

    compute_fft_magnitudes(in, n, out, true);

    env->ReleaseIntArrayElements(fftData, out, 0);
    env->ReleaseFloatArrayElements(data, in, JNI_ABORT);
}

// getFFTDataRaw: int16 input, raw magnitude output
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ui_SpectrumView_getFFTDataRaw(
        JNIEnv* env, jobject, jintArray data, jintArray fftData)
{
    jsize n = env->GetArrayLength(data);
    jint* in = env->GetIntArrayElements(data, nullptr);
    if (!in) return;

    float* fbuf = (float*)malloc(n * sizeof(float));
    if (!fbuf) { env->ReleaseIntArrayElements(data, in, JNI_ABORT); return; }
    for (jsize i = 0; i < n; ++i)
        fbuf[i] = (float)in[i] / 32768.0f;
    env->ReleaseIntArrayElements(data, in, JNI_ABORT);

    jsize out_len = env->GetArrayLength(fftData);
    jint* out = env->GetIntArrayElements(fftData, nullptr);
    if (!out) { free(fbuf); return; }

    compute_fft_magnitudes(fbuf, n, out, false);

    env->ReleaseIntArrayElements(fftData, out, 0);
    free(fbuf);
}

// getFFTDataRawFloat: float input, raw magnitude output
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ui_SpectrumView_getFFTDataRawFloat(
        JNIEnv* env, jobject, jfloatArray data, jintArray fftData)
{
    jsize n = env->GetArrayLength(data);
    jfloat* in = env->GetFloatArrayElements(data, nullptr);
    if (!in) return;

    jsize out_len = env->GetArrayLength(fftData);
    jint* out = env->GetIntArrayElements(fftData, nullptr);
    if (!out) { env->ReleaseFloatArrayElements(data, in, JNI_ABORT); return; }

    compute_fft_magnitudes(in, n, out, false);

    env->ReleaseIntArrayElements(fftData, out, 0);
    env->ReleaseFloatArrayElements(data, in, JNI_ABORT);
}

// ===========================================================================
// SpectrumFragment methods (instance, jobject this)
// Same implementations, different JNI class names.
// ===========================================================================

extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ui_SpectrumFragment_getFFTData(
        JNIEnv* env, jobject, jintArray data, jintArray fftData)
{
    jsize n = env->GetArrayLength(data);
    jint* in = env->GetIntArrayElements(data, nullptr);
    if (!in) return;

    float* fbuf = (float*)malloc(n * sizeof(float));
    if (!fbuf) { env->ReleaseIntArrayElements(data, in, JNI_ABORT); return; }
    for (jsize i = 0; i < n; ++i)
        fbuf[i] = (float)in[i] / 32768.0f;
    env->ReleaseIntArrayElements(data, in, JNI_ABORT);

    jint* out = env->GetIntArrayElements(fftData, nullptr);
    if (!out) { free(fbuf); return; }

    compute_fft_magnitudes(fbuf, n, out, true);

    env->ReleaseIntArrayElements(fftData, out, 0);
    free(fbuf);
}

extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ui_SpectrumFragment_getFFTDataFloat(
        JNIEnv* env, jobject, jfloatArray data, jintArray fftData)
{
    jsize n = env->GetArrayLength(data);
    jfloat* in = env->GetFloatArrayElements(data, nullptr);
    if (!in) return;

    jint* out = env->GetIntArrayElements(fftData, nullptr);
    if (!out) { env->ReleaseFloatArrayElements(data, in, JNI_ABORT); return; }

    compute_fft_magnitudes(in, n, out, true);

    env->ReleaseIntArrayElements(fftData, out, 0);
    env->ReleaseFloatArrayElements(data, in, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ui_SpectrumFragment_getFFTDataRaw(
        JNIEnv* env, jobject, jintArray data, jintArray fftData)
{
    jsize n = env->GetArrayLength(data);
    jint* in = env->GetIntArrayElements(data, nullptr);
    if (!in) return;

    float* fbuf = (float*)malloc(n * sizeof(float));
    if (!fbuf) { env->ReleaseIntArrayElements(data, in, JNI_ABORT); return; }
    for (jsize i = 0; i < n; ++i)
        fbuf[i] = (float)in[i] / 32768.0f;
    env->ReleaseIntArrayElements(data, in, JNI_ABORT);

    jint* out = env->GetIntArrayElements(fftData, nullptr);
    if (!out) { free(fbuf); return; }

    compute_fft_magnitudes(fbuf, n, out, false);

    env->ReleaseIntArrayElements(fftData, out, 0);
    free(fbuf);
}

extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ui_SpectrumFragment_getFFTDataRawFloat(
        JNIEnv* env, jobject, jfloatArray data, jintArray fftData)
{
    jsize n = env->GetArrayLength(data);
    jfloat* in = env->GetFloatArrayElements(data, nullptr);
    if (!in) return;

    jint* out = env->GetIntArrayElements(fftData, nullptr);
    if (!out) { env->ReleaseFloatArrayElements(data, in, JNI_ABORT); return; }

    compute_fft_magnitudes(in, n, out, false);

    env->ReleaseIntArrayElements(fftData, out, 0);
    env->ReleaseFloatArrayElements(data, in, JNI_ABORT);
}
