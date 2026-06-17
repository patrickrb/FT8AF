// Audio resampler JNI wrappers — from-source replacement for libft8cn.so.
//
// Replaces the prebuilt's 6 resampling JNI entry points on FT8Resample.
// All are static methods. Naming: get{outBits}Resample{inBits}.
//
// Implementation: windowed-sinc (polyphase FIR) interpolation. Common use
// cases are integer-ratio conversions (48000→12000 = 4:1, 44100→12000).
// Multi-channel input is mixed down to mono before resampling.
//
// Java side: com.k1af.ft8af.wave.FT8Resample

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ---------------------------------------------------------------------------
// Windowed-sinc resampler core.
//
// Computes output samples by convolving the input with a windowed sinc filter.
// The filter half-width (taps on each side) is chosen to balance quality and
// speed; 16 taps per side is good for the ratios we encounter (2:1 to 5:1).
// ---------------------------------------------------------------------------
static const int HALF_TAPS = 16;

static inline float sinc(float x)
{
    if (fabsf(x) < 1e-6f) return 1.0f;
    float px = (float)M_PI * x;
    return sinf(px) / px;
}

// Blackman window for anti-aliasing filter
static inline float blackman(float n, float N)
{
    float a0 = 0.42f;
    float a1 = 0.50f;
    float a2 = 0.08f;
    float x = 2.0f * (float)M_PI * n / N;
    return a0 - a1 * cosf(x) + a2 * cosf(2.0f * x);
}

// Resample mono float input to mono float output.
// Returns the number of output samples written.
static int resample_float_to_float(const float* input, int in_len,
                                   int in_rate, int out_rate,
                                   float* output, int out_len)
{
    if (in_rate <= 0 || out_rate <= 0 || in_len <= 0) return 0;

    double ratio = (double)in_rate / (double)out_rate;
    // For downsampling, the cutoff frequency is the output Nyquist
    float cutoff = (ratio > 1.0) ? (1.0f / (float)ratio) : 1.0f;
    // Filter width scales with the higher rate
    float filter_scale = (ratio > 1.0) ? (float)ratio : 1.0f;
    int half_width = (int)(HALF_TAPS * filter_scale + 0.5f);
    if (half_width < HALF_TAPS) half_width = HALF_TAPS;
    float filter_len = 2.0f * half_width;

    int n_out = 0;
    for (int i = 0; i < out_len; ++i) {
        double center = i * ratio; // position in input
        int start = (int)center - half_width;
        int end = (int)center + half_width;

        float sample = 0;
        float weight_sum = 0;
        for (int j = start; j <= end; ++j) {
            if (j < 0 || j >= in_len) continue;
            float d = (float)(j - center);
            float w = sinc(d * cutoff) * blackman((d / filter_scale) + half_width, filter_len);
            sample += input[j] * w;
            weight_sum += w;
        }
        output[i] = (weight_sum > 1e-10f) ? (sample / weight_sum) : 0;
        n_out++;
    }
    return n_out;
}

// Compute expected output length for a given input length and rate conversion.
static int compute_output_length(int in_len, int in_rate, int out_rate)
{
    if (in_rate <= 0) return 0;
    return (int)((long long)in_len * out_rate / in_rate);
}

// Mix multi-channel interleaved samples to mono (average of channels).
static void mix_to_mono_float(const float* interleaved, int total_samples,
                              int channels, float* mono, int mono_len)
{
    if (channels <= 1) {
        int len = total_samples < mono_len ? total_samples : mono_len;
        memcpy(mono, interleaved, len * sizeof(float));
        return;
    }
    int frames = total_samples / channels;
    if (frames > mono_len) frames = mono_len;
    for (int i = 0; i < frames; ++i) {
        float sum = 0;
        for (int c = 0; c < channels; ++c)
            sum += interleaved[i * channels + c];
        mono[i] = sum / channels;
    }
}

static void mix_to_mono_short(const int16_t* interleaved, int total_samples,
                              int channels, float* mono, int mono_len)
{
    if (channels <= 1) {
        int len = total_samples < mono_len ? total_samples : mono_len;
        for (int i = 0; i < len; ++i)
            mono[i] = (float)interleaved[i] / 32768.0f;
        return;
    }
    int frames = total_samples / channels;
    if (frames > mono_len) frames = mono_len;
    for (int i = 0; i < frames; ++i) {
        float sum = 0;
        for (int c = 0; c < channels; ++c)
            sum += (float)interleaved[i * channels + c] / 32768.0f;
        mono[i] = sum / channels;
    }
}

// ===========================================================================
// JNI entry points
// ===========================================================================

// get16Resample16: short[] in → short[] out
extern "C" JNIEXPORT jshortArray JNICALL
Java_com_k1af_ft8af_wave_FT8Resample_get16Resample16(
        JNIEnv* env, jclass,
        jshortArray inputData, jint inputRate, jint outputRate, jint channels)
{
    jsize in_total = env->GetArrayLength(inputData);
    jshort* in_raw = env->GetShortArrayElements(inputData, nullptr);
    if (!in_raw) return env->NewShortArray(0);

    int in_frames = (channels > 0) ? (in_total / channels) : in_total;
    float* mono = (float*)malloc(in_frames * sizeof(float));
    if (!mono) { env->ReleaseShortArrayElements(inputData, in_raw, JNI_ABORT); return env->NewShortArray(0); }
    mix_to_mono_short(in_raw, in_total, channels > 0 ? channels : 1, mono, in_frames);
    env->ReleaseShortArrayElements(inputData, in_raw, JNI_ABORT);

    int out_len = compute_output_length(in_frames, inputRate, outputRate);
    if (out_len <= 0) { free(mono); return env->NewShortArray(0); }

    float* out_f = (float*)malloc(out_len * sizeof(float));
    if (!out_f) { free(mono); return env->NewShortArray(0); }

    resample_float_to_float(mono, in_frames, inputRate, outputRate, out_f, out_len);
    free(mono);

    jshortArray result = env->NewShortArray(out_len);
    if (!result) { free(out_f); return env->NewShortArray(0); }

    jshort* out_s = env->GetShortArrayElements(result, nullptr);
    if (!out_s) { free(out_f); return result; }
    for (int i = 0; i < out_len; ++i) {
        float v = out_f[i] * 32767.0f;
        if (v > 32767.0f) v = 32767.0f;
        if (v < -32768.0f) v = -32768.0f;
        out_s[i] = (jshort)v;
    }
    env->ReleaseShortArrayElements(result, out_s, 0);
    free(out_f);
    return result;
}

// get32Resample16: short[] in → float[] out
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_k1af_ft8af_wave_FT8Resample_get32Resample16(
        JNIEnv* env, jclass,
        jshortArray inputData, jint inputRate, jint outputRate, jint channels)
{
    jsize in_total = env->GetArrayLength(inputData);
    jshort* in_raw = env->GetShortArrayElements(inputData, nullptr);
    if (!in_raw) return env->NewFloatArray(0);

    int in_frames = (channels > 0) ? (in_total / channels) : in_total;
    float* mono = (float*)malloc(in_frames * sizeof(float));
    if (!mono) { env->ReleaseShortArrayElements(inputData, in_raw, JNI_ABORT); return env->NewFloatArray(0); }
    mix_to_mono_short(in_raw, in_total, channels > 0 ? channels : 1, mono, in_frames);
    env->ReleaseShortArrayElements(inputData, in_raw, JNI_ABORT);

    int out_len = compute_output_length(in_frames, inputRate, outputRate);
    if (out_len <= 0) { free(mono); return env->NewFloatArray(0); }

    float* out_f = (float*)malloc(out_len * sizeof(float));
    if (!out_f) { free(mono); return env->NewFloatArray(0); }

    resample_float_to_float(mono, in_frames, inputRate, outputRate, out_f, out_len);
    free(mono);

    jfloatArray result = env->NewFloatArray(out_len);
    if (!result) { free(out_f); return env->NewFloatArray(0); }
    env->SetFloatArrayRegion(result, 0, out_len, out_f);
    free(out_f);
    return result;
}

// get16Resample32: float[] in → short[] out
extern "C" JNIEXPORT jshortArray JNICALL
Java_com_k1af_ft8af_wave_FT8Resample_get16Resample32(
        JNIEnv* env, jclass,
        jfloatArray inputData, jint inputRate, jint outputRate, jint channels)
{
    jsize in_total = env->GetArrayLength(inputData);
    jfloat* in_raw = env->GetFloatArrayElements(inputData, nullptr);
    if (!in_raw) return env->NewShortArray(0);

    int in_frames = (channels > 0) ? (in_total / channels) : in_total;
    float* mono = (float*)malloc(in_frames * sizeof(float));
    if (!mono) { env->ReleaseFloatArrayElements(inputData, in_raw, JNI_ABORT); return env->NewShortArray(0); }
    mix_to_mono_float(in_raw, in_total, channels > 0 ? channels : 1, mono, in_frames);
    env->ReleaseFloatArrayElements(inputData, in_raw, JNI_ABORT);

    int out_len = compute_output_length(in_frames, inputRate, outputRate);
    if (out_len <= 0) { free(mono); return env->NewShortArray(0); }

    float* out_f = (float*)malloc(out_len * sizeof(float));
    if (!out_f) { free(mono); return env->NewShortArray(0); }

    resample_float_to_float(mono, in_frames, inputRate, outputRate, out_f, out_len);
    free(mono);

    jshortArray result = env->NewShortArray(out_len);
    if (!result) { free(out_f); return env->NewShortArray(0); }

    jshort* out_s = env->GetShortArrayElements(result, nullptr);
    if (!out_s) { free(out_f); return result; }
    for (int i = 0; i < out_len; ++i) {
        float v = out_f[i] * 32767.0f;
        if (v > 32767.0f) v = 32767.0f;
        if (v < -32768.0f) v = -32768.0f;
        out_s[i] = (jshort)v;
    }
    env->ReleaseShortArrayElements(result, out_s, 0);
    free(out_f);
    return result;
}

// get32Resample32: float[] in → float[] out
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_k1af_ft8af_wave_FT8Resample_get32Resample32(
        JNIEnv* env, jclass,
        jfloatArray inputData, jint inputRate, jint outputRate, jint channels)
{
    jsize in_total = env->GetArrayLength(inputData);
    jfloat* in_raw = env->GetFloatArrayElements(inputData, nullptr);
    if (!in_raw) return env->NewFloatArray(0);

    int in_frames = (channels > 0) ? (in_total / channels) : in_total;
    float* mono = (float*)malloc(in_frames * sizeof(float));
    if (!mono) { env->ReleaseFloatArrayElements(inputData, in_raw, JNI_ABORT); return env->NewFloatArray(0); }
    mix_to_mono_float(in_raw, in_total, channels > 0 ? channels : 1, mono, in_frames);
    env->ReleaseFloatArrayElements(inputData, in_raw, JNI_ABORT);

    int out_len = compute_output_length(in_frames, inputRate, outputRate);
    if (out_len <= 0) { free(mono); return env->NewFloatArray(0); }

    float* out_f = (float*)malloc(out_len * sizeof(float));
    if (!out_f) { free(mono); return env->NewFloatArray(0); }

    resample_float_to_float(mono, in_frames, inputRate, outputRate, out_f, out_len);
    free(mono);

    jfloatArray result = env->NewFloatArray(out_len);
    if (!result) { free(out_f); return env->NewFloatArray(0); }
    env->SetFloatArrayRegion(result, 0, out_len, out_f);
    free(out_f);
    return result;
}

// get8Resample16: short[] in → byte[] out (8-bit unsigned)
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_k1af_ft8af_wave_FT8Resample_get8Resample16(
        JNIEnv* env, jclass,
        jshortArray inputData, jint inputRate, jint outputRate, jint channels)
{
    jsize in_total = env->GetArrayLength(inputData);
    jshort* in_raw = env->GetShortArrayElements(inputData, nullptr);
    if (!in_raw) return env->NewByteArray(0);

    int in_frames = (channels > 0) ? (in_total / channels) : in_total;
    float* mono = (float*)malloc(in_frames * sizeof(float));
    if (!mono) { env->ReleaseShortArrayElements(inputData, in_raw, JNI_ABORT); return env->NewByteArray(0); }
    mix_to_mono_short(in_raw, in_total, channels > 0 ? channels : 1, mono, in_frames);
    env->ReleaseShortArrayElements(inputData, in_raw, JNI_ABORT);

    int out_len = compute_output_length(in_frames, inputRate, outputRate);
    if (out_len <= 0) { free(mono); return env->NewByteArray(0); }

    float* out_f = (float*)malloc(out_len * sizeof(float));
    if (!out_f) { free(mono); return env->NewByteArray(0); }

    resample_float_to_float(mono, in_frames, inputRate, outputRate, out_f, out_len);
    free(mono);

    jbyteArray result = env->NewByteArray(out_len);
    if (!result) { free(out_f); return env->NewByteArray(0); }

    jbyte* out_b = env->GetByteArrayElements(result, nullptr);
    if (!out_b) { free(out_f); return result; }
    for (int i = 0; i < out_len; ++i) {
        // Convert -1.0..1.0 float to 0..255 unsigned byte (stored as signed jbyte)
        int v = (int)((out_f[i] + 1.0f) * 127.5f);
        if (v > 255) v = 255;
        if (v < 0) v = 0;
        out_b[i] = (jbyte)(v - 128); // Java byte is signed (-128..127)
    }
    env->ReleaseByteArrayElements(result, out_b, 0);
    free(out_f);
    return result;
}

// get8Resample32: float[] in → byte[] out (8-bit unsigned)
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_k1af_ft8af_wave_FT8Resample_get8Resample32(
        JNIEnv* env, jclass,
        jfloatArray inputData, jint inputRate, jint outputRate, jint channels)
{
    jsize in_total = env->GetArrayLength(inputData);
    jfloat* in_raw = env->GetFloatArrayElements(inputData, nullptr);
    if (!in_raw) return env->NewByteArray(0);

    int in_frames = (channels > 0) ? (in_total / channels) : in_total;
    float* mono = (float*)malloc(in_frames * sizeof(float));
    if (!mono) { env->ReleaseFloatArrayElements(inputData, in_raw, JNI_ABORT); return env->NewByteArray(0); }
    mix_to_mono_float(in_raw, in_total, channels > 0 ? channels : 1, mono, in_frames);
    env->ReleaseFloatArrayElements(inputData, in_raw, JNI_ABORT);

    int out_len = compute_output_length(in_frames, inputRate, outputRate);
    if (out_len <= 0) { free(mono); return env->NewByteArray(0); }

    float* out_f = (float*)malloc(out_len * sizeof(float));
    if (!out_f) { free(mono); return env->NewByteArray(0); }

    resample_float_to_float(mono, in_frames, inputRate, outputRate, out_f, out_len);
    free(mono);

    jbyteArray result = env->NewByteArray(out_len);
    if (!result) { free(out_f); return env->NewByteArray(0); }

    jbyte* out_b = env->GetByteArrayElements(result, nullptr);
    if (!out_b) { free(out_f); return result; }
    for (int i = 0; i < out_len; ++i) {
        int v = (int)((out_f[i] + 1.0f) * 127.5f);
        if (v > 255) v = 255;
        if (v < 0) v = 0;
        out_b[i] = (jbyte)(v - 128);
    }
    env->ReleaseByteArrayElements(result, out_b, 0);
    free(out_f);
    return result;
}
