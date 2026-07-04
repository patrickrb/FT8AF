// Waterfall display intensity mapping.
//
// WHY THIS EXISTS / WHAT WENT WRONG
// ---------------------------------
// The from-source replacement for the prebuilt libft8cn.so (the FFT display path
// in ft8_fft_jni.cpp) mapped magnitudes to the 0..255 waterfall range with a
// LINEAR auto-gain: output = mag * (255 / max_mag). That is wrong for a
// spectrogram. Linear amplitude has no usable dynamic range: the noise floor and
// every weak signal collapse into the bottom handful of codes while the single
// loudest bin (often a carrier or birdie) pins the scale to 255 — so the
// waterfall looked blank ("can't see a thing"). Real waterfalls are logarithmic.
//
// This module maps magnitudes on a dB scale referenced to the frame's noise
// floor, which is exactly what the decoder's own monitor.c does (10*log10 ->
// 2*db+240). The reference is the MEDIAN bin dB rather than the max, so one
// strong signal no longer darkens the rest of the row.

#include "fft_display.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// dB above the noise floor that maps to full (255) brightness.
#define FT8AF_DISPLAY_DB_RANGE 50.0f
// Intensity assigned to the noise floor itself. In the raw view we keep the floor
// faintly visible (a black floor reads as a dead display, which is the symptom
// users hit); the denoise view pushes the floor to black.
#define FT8AF_FLOOR_LEVEL_RAW 40
#define FT8AF_FLOOR_LEVEL_DENOISE 0

static int cmp_float(const void* a, const void* b)
{
    float fa = *(const float*)a;
    float fb = *(const float*)b;
    return (fa > fb) - (fa < fb);
}

void ft8af_magnitudes_to_display(const float* mag, int n_bins, int* output, int denoise)
{
    if (n_bins <= 0)
        return;

    // Logarithmic intensity. power = mag^2  =>  dB = 20*log10(mag). The 1e-12
    // floor keeps log10 finite for silent bins.
    float* db = (float*)malloc((size_t)n_bins * sizeof(float));
    if (!db)
        return;
    for (int i = 0; i < n_bins; ++i)
        db[i] = 20.0f * log10f(mag[i] + 1e-12f);

    if (denoise)
    {
        // Flatten slowly-varying band tilt: subtract a local sliding-window average
        // (in dB) so a broadband noise slope doesn't wash out one side of the band.
        // Window ~5% of bins, min 8; prefix sum gives O(1) per-bin averaging.
        int win = n_bins / 20;
        if (win < 8)
            win = 8;
        if (win > n_bins)
            win = n_bins;

        float* prefix = (float*)malloc(((size_t)n_bins + 1) * sizeof(float));
        if (prefix)
        {
            prefix[0] = 0.0f;
            for (int i = 0; i < n_bins; ++i)
                prefix[i + 1] = prefix[i] + db[i];

            for (int i = 0; i < n_bins; ++i)
            {
                int left = i - win / 2;
                int right = left + win;
                if (left < 0) { left = 0; right = win; }
                if (right > n_bins) { right = n_bins; left = right - win; }
                if (left < 0) left = 0;

                db[i] -= (prefix[right] - prefix[left]) / (float)(right - left);
            }
            free(prefix);
        }
    }

    // Noise-floor reference = median bin dB. Robust while signals occupy < 50% of
    // the bins, which holds for an FT8/FT4 slot. Replaces the old per-frame max
    // auto-gain, under which one strong bin darkened the whole row.
    float noise_floor = 0.0f;
    float* tmp = (float*)malloc((size_t)n_bins * sizeof(float));
    if (tmp)
    {
        memcpy(tmp, db, (size_t)n_bins * sizeof(float));
        qsort(tmp, (size_t)n_bins, sizeof(float), cmp_float);
        noise_floor = tmp[n_bins / 2];
        free(tmp);
    }

    int floor_level = denoise ? FT8AF_FLOOR_LEVEL_DENOISE : FT8AF_FLOOR_LEVEL_RAW;
    float gain = (255.0f - (float)floor_level) / FT8AF_DISPLAY_DB_RANGE;
    for (int i = 0; i < n_bins; ++i)
    {
        int val = floor_level + (int)((db[i] - noise_floor) * gain);
        if (val < 0)
            val = 0;
        if (val > 255)
            val = 255;
        output[i] = val;
    }

    free(db);
}

// --- FFT display window functions + frame averaging (issue #428) -----------

void ft8af_window_fill(int type, float* w, int n)
{
    if (n <= 0)
        return;
    if (n == 1)
    {
        w[0] = 1.0f;
        return;
    }
    for (int i = 0; i < n; ++i)
    {
        // Symmetric form: x sweeps 0..2π across the n samples.
        float x = 2.0f * (float)M_PI * (float)i / (float)(n - 1);
        switch (type)
        {
        case FT8AF_WIN_HANN:
            w[i] = 0.5f - 0.5f * cosf(x);
            break;
        case FT8AF_WIN_HAMMING:
            w[i] = 0.54f - 0.46f * cosf(x);
            break;
        case FT8AF_WIN_BLACKMAN:
            w[i] = 0.42f - 0.5f * cosf(x) + 0.08f * cosf(2.0f * x);
            break;
        case FT8AF_WIN_BLACKMAN_HARRIS:
            w[i] = 0.35875f - 0.48829f * cosf(x) + 0.14128f * cosf(2.0f * x)
                   - 0.01168f * cosf(3.0f * x);
            break;
        case FT8AF_WIN_RECT:
        default: // unknown types read as "no window"
            w[i] = 1.0f;
            break;
        }
    }
}

void ft8af_window_apply(const float* w, const float* in, float* out, int n)
{
    for (int i = 0; i < n; ++i)
        out[i] = in[i] * w[i];
}

void ft8af_mag_ema(float* acc, const float* mag, int n, float alpha)
{
    for (int i = 0; i < n; ++i)
        acc[i] = alpha * mag[i] + (1.0f - alpha) * acc[i];
}
