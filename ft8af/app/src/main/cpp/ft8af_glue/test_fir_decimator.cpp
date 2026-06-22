// Host tests for fir_decimator.h, the anti-aliasing decimator that replaced the box-filter
// downsample in usb_audio_capture.cpp.
//
// The point of the change is sensitivity: a box filter only rejects ~10 dB at the alias
// frequencies, so out-of-band hiss folds into the FT8 passband and raises the noise floor.
// These tests pin the three properties that matter: correct decimation ratio, unity gain in
// the passband (we must not change level), and deep rejection of a tone that would otherwise
// alias straight into the FT8 band.
//
// HOST BUILD (no device): run_host_tests.ps1 / run_host_tests.sh compile this with a host
// clang++ and run it. Exit code 0 == all pass.

#include <cmath>
#include <cstdio>
#include <vector>

#include "../fir_decimator.h"

static int g_failures = 0;

static void check(bool cond, const char* label) {
    if (cond) {
        printf("  ok:   %s\n", label);
    } else {
        printf("  FAIL: %s\n", label);
        ++g_failures;
    }
}

// Steady-state output amplitude (relative to input amplitude) for an input sine of frequency
// freqHz, fed through a `ratio` decimator at inRate. Measures the peak over the back half of
// the output so the filter's startup transient is excluded.
static float toneGain(int inRate, int ratio, double freqHz, double inAmp) {
    FirDecimator dec(ratio);
    int n = inRate;  // one second of input
    std::vector<float> in(n);
    for (int i = 0; i < n; ++i) {
        in[i] = (float) (inAmp * std::sin(2.0 * M_PI * freqHz * i / inRate));
    }
    std::vector<float> out;
    dec.process(in.data(), n, out);
    float peak = 0.0f;
    for (size_t i = out.size() / 2; i < out.size(); ++i) {
        peak = std::max(peak, std::fabs(out[i]));
    }
    return peak / (float) inAmp;
}

// Analytic magnitude response of the old length-`ratio` moving-average (box) filter at a
// given frequency, for the comparison assertion.
static double boxGain(int inRate, int ratio, double freqHz) {
    double w = M_PI * freqHz / inRate;
    if (std::fabs(std::sin(w)) < 1e-12) return 1.0;
    return std::fabs(std::sin(ratio * w) / (ratio * std::sin(w)));
}

int main() {
    printf("fir_decimator tests:\n");

    // 1. Decimation ratio and kernel shape.
    {
        FirDecimator dec(4);
        std::vector<float> in(4000, 0.0f), out;
        dec.process(in.data(), (int) in.size(), out);
        check(out.size() == 1000, "decimate-by-4 yields n/4 output samples");
        check(dec.ratio() == 4, "ratio() reports 4");
        check((dec.numTaps() % 2) == 1, "kernel length is odd (symmetric)");
    }

    // 2. Unity DC gain — the decimator must not change level (the meter + decoder rely on it).
    {
        FirDecimator dec(4);
        std::vector<float> in(8000, 1.0f), out;
        dec.process(in.data(), (int) in.size(), out);
        check(!out.empty() && std::fabs(out.back() - 1.0f) < 1e-3f, "unity DC gain");
    }

    // 3. Passband tone (1 kHz, well inside FT8's range) passes at ~unity.
    float gPass = toneGain(48000, 4, 1000.0, 0.5);
    check(gPass > 0.95f, "1 kHz passband tone passes near unity");

    // 4. A 9 kHz tone would alias to |9000 - 12000| = 3 kHz — right into the FT8 band — if not
    //    filtered. The FIR must crush it (> ~20 dB), and must beat the old box filter, which
    //    leaks it badly.
    float gStop = toneGain(48000, 4, 9000.0, 0.5);
    double gBox = boxGain(48000, 4, 9000.0);
    printf("  info: gPass=%.4f  gStop_fir=%.4f  gStop_box=%.4f\n", gPass, gStop, gBox);
    check(gStop < 0.1f, "9 kHz alias tone rejected > 20 dB by FIR");
    check(gStop < (float) gBox, "FIR rejects the alias far better than the box filter");

    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("all fir_decimator tests passed\n");
    return 0;
}
