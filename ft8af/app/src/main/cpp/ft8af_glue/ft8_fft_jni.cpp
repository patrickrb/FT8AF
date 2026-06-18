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

    kiss_fftr(cfg, input, freq);

    // Compute the linear magnitude for each bin.
    float* mag = (float*)malloc(n_bins * sizeof(float));
    if (!mag) {
        free(freq);
        kiss_fftr_free(cfg);
        return;
    }
    for (int i = 0; i < n_bins; ++i)
        mag[i] = sqrtf(freq[i].r * freq[i].r + freq[i].i * freq[i].i);

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
