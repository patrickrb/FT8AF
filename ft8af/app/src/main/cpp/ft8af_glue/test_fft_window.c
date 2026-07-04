// Tests for the issue-#428 FFT display knobs: the pre-FFT window functions
// (ft8af_window_fill / ft8af_window_apply) and the cross-frame magnitude EMA
// (ft8af_mag_ema) in fft_display.c.
//
// Includes a behavioral leakage test that pins the mechanism issue #428 is
// about: an off-grid tone FFT'd with no window (the pre-#428 display path)
// splatters energy across the whole row, while a Hann window keeps the skirts
// tens of dB down. Uses the same kissfft real FFT as the display JNI path.
//
// HOST BUILD (no device): run_host_tests.ps1 / run_host_tests.sh build and run
// this alongside the other suites. Exit code 0 == all pass.

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "fft/kiss_fftr.h"

#include "fft_display.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static int g_failures = 0;

#define CHECK_TRUE(cond, label)                                                 \
    do {                                                                        \
        if (!(cond)) {                                                          \
            printf("  FAIL: %s\n", (label));                                    \
            ++g_failures;                                                       \
        } else {                                                                \
            printf("  ok:   %s\n", (label));                                    \
        }                                                                       \
    } while (0)

#define CHECK_EQ_INT(actual, expected, label)                                   \
    do {                                                                        \
        int a_ = (actual), e_ = (expected);                                     \
        if (a_ != e_) {                                                         \
            printf("  FAIL: %s: got %d, expected %d\n", (label), a_, e_);       \
            ++g_failures;                                                       \
        } else {                                                                \
            printf("  ok:   %s\n", (label));                                    \
        }                                                                       \
    } while (0)

#define CHECK_NEAR(actual, expected, tol, label)                                \
    do {                                                                        \
        float a_ = (actual), e_ = (expected);                                   \
        if (fabsf(a_ - e_) > (tol)) {                                           \
            printf("  FAIL: %s: got %f, expected %f (tol %f)\n", (label),       \
                   (double)a_, (double)e_, (double)(tol));                      \
            ++g_failures;                                                       \
        } else {                                                                \
            printf("  ok:   %s ~= %f\n", (label), (double)e_);                  \
        }                                                                       \
    } while (0)

// ---------------------------------------------------------------------------
// Window coefficient shapes: endpoints, midpoint, symmetry, coherent gain.
// ---------------------------------------------------------------------------
static void test_window_coefficients(void)
{
    printf("window coefficients:\n");
    enum { N = 64 };
    float w[N];

    ft8af_window_fill(FT8AF_WIN_RECT, w, N);
    int all_ones = 1;
    for (int i = 0; i < N; ++i)
        if (w[i] != 1.0f)
            all_ones = 0;
    CHECK_TRUE(all_ones, "rect: all coefficients 1.0");

    struct {
        int type;
        const char* name;
        float endpoint;   // w[0] == w[N-1] for the symmetric form
        float coherent;   // mean coefficient (coherent gain)
    } cases[] = {
        { FT8AF_WIN_HANN, "hann", 0.0f, 0.5f },
        { FT8AF_WIN_HAMMING, "hamming", 0.08f, 0.54f },
        { FT8AF_WIN_BLACKMAN, "blackman", 0.0f, 0.42f },
        { FT8AF_WIN_BLACKMAN_HARRIS, "blackman-harris", 6e-5f, 0.35875f },
    };
    for (int c = 0; c < 4; ++c)
    {
        char label[96];
        ft8af_window_fill(cases[c].type, w, N);

        snprintf(label, sizeof label, "%s: endpoint w[0]", cases[c].name);
        CHECK_NEAR(w[0], cases[c].endpoint, 1e-4f, label);

        int symmetric = 1;
        for (int i = 0; i < N; ++i)
            if (fabsf(w[i] - w[N - 1 - i]) > 1e-5f)
                symmetric = 0;
        snprintf(label, sizeof label, "%s: symmetric", cases[c].name);
        CHECK_TRUE(symmetric, label);

        // Midpoint of the symmetric form: for even N the peak straddles
        // N/2-1 and N/2; both sit within a hair of 1.0.
        snprintf(label, sizeof label, "%s: midpoint ~1", cases[c].name);
        CHECK_TRUE(w[N / 2] > 0.99f && w[N / 2 - 1] > 0.99f, label);

        float sum = 0.0f;
        for (int i = 0; i < N; ++i)
            sum += w[i];
        snprintf(label, sizeof label, "%s: coherent gain", cases[c].name);
        // Symmetric (N-1 denominator) windows have mean slightly above the
        // periodic-form gain; 2% relative tolerance covers N=64.
        CHECK_NEAR(sum / N, cases[c].coherent, cases[c].coherent * 0.02f + 1e-3f, label);
    }

    // Unknown type falls back to rectangular.
    ft8af_window_fill(99, w, N);
    int fallback_rect = 1;
    for (int i = 0; i < N; ++i)
        if (w[i] != 1.0f)
            fallback_rect = 0;
    CHECK_TRUE(fallback_rect, "unknown type -> rect");

    // n == 1 is a defined edge: single coefficient 1.0 (no 0/0 from N-1).
    float one = -1.0f;
    ft8af_window_fill(FT8AF_WIN_HANN, &one, 1);
    CHECK_NEAR(one, 1.0f, 1e-6f, "n==1 -> 1.0");

    // n <= 0 must be a no-op (must not crash / write).
    ft8af_window_fill(FT8AF_WIN_HANN, NULL, 0);
    CHECK_TRUE(1, "n==0 no-op");
}

// ---------------------------------------------------------------------------
// ft8af_window_apply: elementwise multiply; aliasing allowed.
// ---------------------------------------------------------------------------
static void test_window_apply(void)
{
    printf("window apply:\n");
    float w[4] = { 0.0f, 0.5f, 1.0f, 2.0f };
    float in[4] = { 4.0f, 4.0f, 4.0f, 4.0f };
    float out[4];
    ft8af_window_apply(w, in, out, 4);
    CHECK_TRUE(out[0] == 0.0f && out[1] == 2.0f && out[2] == 4.0f && out[3] == 8.0f,
               "elementwise multiply");

    // In-place (out aliases in).
    ft8af_window_apply(w, in, in, 4);
    CHECK_TRUE(in[1] == 2.0f && in[3] == 8.0f, "in-place multiply");

    ft8af_window_apply(w, NULL, NULL, 0);
    CHECK_TRUE(1, "n==0 no-op");
}

// ---------------------------------------------------------------------------
// Behavioral leakage test: off-grid tone, rect vs Hann, same FFT size as the
// Android display path (1920 samples @ 12 kHz -> 6.25 Hz bins).
// ---------------------------------------------------------------------------
static void test_leakage_rect_vs_hann(void)
{
    printf("spectral leakage (rect vs hann):\n");
    enum { N = 1920, NBINS = N / 2 };
    const float fs = 12000.0f;
    const float f_tone = 1003.7f; // deliberately off the 6.25 Hz grid

    float* samples = (float*)malloc(N * sizeof(float));
    float* windowed = (float*)malloc(N * sizeof(float));
    float* w = (float*)malloc(N * sizeof(float));
    kiss_fft_cpx* freq = (kiss_fft_cpx*)malloc((NBINS + 1) * sizeof(kiss_fft_cpx));
    float* db_rect = (float*)malloc(NBINS * sizeof(float));
    float* db_hann = (float*)malloc(NBINS * sizeof(float));
    if (!samples || !windowed || !w || !freq || !db_rect || !db_hann)
    {
        printf("  FAIL: alloc\n");
        ++g_failures;
        return;
    }

    for (int i = 0; i < N; ++i)
        samples[i] = sinf(2.0f * (float)M_PI * f_tone * (float)i / fs);

    kiss_fftr_cfg cfg = kiss_fftr_alloc(N, 0, NULL, NULL);

    for (int pass = 0; pass < 2; ++pass)
    {
        float* db = pass == 0 ? db_rect : db_hann;
        ft8af_window_fill(pass == 0 ? FT8AF_WIN_RECT : FT8AF_WIN_HANN, w, N);
        ft8af_window_apply(w, samples, windowed, N);
        kiss_fftr(cfg, windowed, freq);
        for (int i = 0; i < NBINS; ++i)
        {
            float m = sqrtf(freq[i].r * freq[i].r + freq[i].i * freq[i].i);
            db[i] = 20.0f * log10f(m + 1e-12f);
        }
    }
    kiss_fftr_free(cfg);

    int peak = (int)(f_tone * N / fs + 0.5f); // ~bin 161
    // Compare the skirt 10+ bins from the peak, relative to each pass's own
    // peak level: rect's sinc sidelobes decay slowly, Hann's fall off fast.
    float rect_skirt = -1e9f, hann_skirt = -1e9f;
    for (int i = 0; i < NBINS; ++i)
    {
        if (abs(i - peak) <= 10)
            continue;
        if (db_rect[i] > rect_skirt) rect_skirt = db_rect[i];
        if (db_hann[i] > hann_skirt) hann_skirt = db_hann[i];
    }
    float rect_rel = db_rect[peak] - rect_skirt; // how far the skirt sits below the peak
    float hann_rel = db_hann[peak] - hann_skirt;
    printf("  info: skirt below peak: rect %.1f dB, hann %.1f dB\n",
           (double)rect_rel, (double)hann_rel);
    CHECK_TRUE(hann_rel >= rect_rel + 20.0f,
               "hann suppresses off-grid skirts >= 20 dB more than rect");
}

// ---------------------------------------------------------------------------
// Wire-value sanitizers: the JNI boundary must clamp out-of-range knob values
// to the defaults (Hann / off) instead of trusting persisted state.
// ---------------------------------------------------------------------------
static void test_sanitizers(void)
{
    printf("wire-value sanitizers:\n");
    for (int v = FT8AF_WIN_RECT; v <= FT8AF_WIN_BLACKMAN_HARRIS; ++v)
        CHECK_EQ_INT(ft8af_sanitize_window_type(v), v, "valid window passes through");
    CHECK_EQ_INT(ft8af_sanitize_window_type(-1), FT8AF_WIN_HANN, "window -1 -> hann");
    CHECK_EQ_INT(ft8af_sanitize_window_type(5), FT8AF_WIN_HANN, "window 5 -> hann");
    CHECK_EQ_INT(ft8af_sanitize_window_type(99), FT8AF_WIN_HANN, "window 99 -> hann");

    for (int v = 0; v <= 2; ++v)
        CHECK_EQ_INT(ft8af_sanitize_avg_mode(v), v, "valid avg mode passes through");
    CHECK_EQ_INT(ft8af_sanitize_avg_mode(-1), 0, "avg -1 -> off");
    CHECK_EQ_INT(ft8af_sanitize_avg_mode(3), 0, "avg 3 -> off (not heavy EMA)");
    CHECK_EQ_INT(ft8af_sanitize_avg_mode(99), 0, "avg 99 -> off");
}

// ---------------------------------------------------------------------------
// ft8af_mag_ema: passthrough at alpha 1, two-step arithmetic, convergence.
// ---------------------------------------------------------------------------
static void test_mag_ema(void)
{
    printf("magnitude EMA:\n");
    float acc[3] = { 10.0f, 20.0f, 30.0f };
    float m1[3] = { 1.0f, 2.0f, 3.0f };

    // alpha == 1: passthrough copy.
    ft8af_mag_ema(acc, m1, 3, 1.0f);
    CHECK_TRUE(acc[0] == 1.0f && acc[1] == 2.0f && acc[2] == 3.0f, "alpha=1 passthrough");

    // Two-step numeric check at alpha 0.5: acc = 0.5*new + 0.5*old.
    float m2[3] = { 3.0f, 6.0f, 9.0f };
    ft8af_mag_ema(acc, m2, 3, 0.5f);
    CHECK_NEAR(acc[0], 2.0f, 1e-6f, "alpha=0.5 step: 0.5*3 + 0.5*1");
    CHECK_NEAR(acc[2], 6.0f, 1e-6f, "alpha=0.5 step: 0.5*9 + 0.5*3");

    // Repeated constant input converges to that input.
    float target[3] = { 5.0f, 5.0f, 5.0f };
    for (int i = 0; i < 60; ++i)
        ft8af_mag_ema(acc, target, 3, 0.25f);
    CHECK_NEAR(acc[0], 5.0f, 1e-3f, "converges to constant input");

    ft8af_mag_ema(NULL, NULL, 0, 0.5f);
    CHECK_TRUE(1, "n==0 no-op");
}

int main(void)
{
    printf("=== FFT display window / averaging tests (issue #428) ===\n");
    test_window_coefficients();
    test_window_apply();
    test_leakage_rect_vs_hann();
    test_sanitizers();
    test_mag_ema();

    if (g_failures)
    {
        printf("=== %d FAILURE(S) ===\n", g_failures);
        return 1;
    }
    printf("=== all passed ===\n");
    return 0;
}
