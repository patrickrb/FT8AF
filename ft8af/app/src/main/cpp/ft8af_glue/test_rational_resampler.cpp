// Host tests for rational_resampler.h, the polyphase L/M resampler behind the
// non-48 kHz USB capture fix (issue #364).
//
// The bug this guards against: a device that only streams 44.1 kHz used to get
// its decimation ratio floored (44100/12000 -> 3), producing 14.7 kHz audio
// labeled 12 kHz — every FT8 tone shifted down by the 12000/14700 ratio and off
// the 6.25 Hz grid, so nothing decoded. These tests pin the properties that
// matter: exact rational output rate (no drift), tone frequency preserved,
// unity passband gain, deep alias rejection, and streaming-state correctness.
//
// HOST BUILD (no device): run_host_tests.ps1 / run_host_tests.sh compile this
// with a host clang++ and run it. Exit code 0 == all pass.

#include <cmath>
#include <cstdio>
#include <vector>

#include "../rational_resampler.h"

static int g_failures = 0;

static void check(bool cond, const char* label) {
    if (cond) {
        printf("  ok:   %s\n", label);
    } else {
        printf("  FAIL: %s\n", label);
        ++g_failures;
    }
}

// Resample `seconds` of a sine at freqHz/inRate through L/M and return the
// output. Shared by the frequency / gain / alias assertions.
static std::vector<float> resampledTone(int inRate, int L, int M,
                                        double freqHz, double seconds) {
    RationalResampler rs(L, M);
    const int n = static_cast<int>(inRate * seconds);
    std::vector<float> in(n);
    for (int i = 0; i < n; ++i) {
        in[i] = static_cast<float>(std::sin(2.0 * M_PI * freqHz * i / inRate));
    }
    std::vector<float> out;
    rs.process(in.data(), n, out);
    return out;
}

// Peak amplitude over the back half of the output (skips filter warm-up).
static float steadyPeak(const std::vector<float>& out) {
    float peak = 0.0f;
    for (std::size_t i = out.size() / 2; i < out.size(); ++i) {
        peak = std::max(peak, std::fabs(out[i]));
    }
    return peak;
}

// Estimate the dominant frequency of the back half of `out` (rate outRate)
// by counting zero crossings — robust for a clean single tone.
static double zeroCrossFreq(const std::vector<float>& out, int outRate) {
    std::size_t start = out.size() / 2;
    int crossings = 0;
    for (std::size_t i = start + 1; i < out.size(); ++i) {
        if ((out[i - 1] < 0.0f) != (out[i] < 0.0f)) ++crossings;
    }
    const double seconds = (double)(out.size() - start) / outRate;
    return crossings / (2.0 * seconds);
}

int main() {
    printf("rational_resampler tests:\n");

    // 1. Ratio reduction + guards (mirrors decimationRatioFor's degenerate cases).
    {
        RationalRatio r = rationalRatioFor(48000, 12000);
        check(r.L == 1 && r.M == 4, "48k/12k -> L/M = 1/4 (integer path)");
        r = rationalRatioFor(44100, 12000);
        check(r.L == 40 && r.M == 147, "44.1k/12k -> L/M = 40/147");
        r = rationalRatioFor(96000, 12000);
        check(r.L == 1 && r.M == 8, "96k/12k -> L/M = 1/8");
        r = rationalRatioFor(48000, 0);
        check(r.L == 1 && r.M == 1, "zero target rate -> pass-through 1/1");
        r = rationalRatioFor(48000, -12000);
        check(r.L == 1 && r.M == 1, "negative target rate -> pass-through 1/1");
        r = rationalRatioFor(8000, 12000);
        check(r.L == 1 && r.M == 1, "input below target -> pass-through 1/1 (no upsample)");
        r = rationalRatioFor(12000, 12000);
        check(r.L == 1 && r.M == 1, "equal rates -> pass-through 1/1");
    }

    // 2. Exact output count over a long stream — the floored decimator produced
    //    22.5% too few samples per second; the rational one must not drift.
    {
        RationalResampler rs(40, 147);
        const int n = 441000;  // 10 s at 44.1 kHz
        std::vector<float> in(n, 0.0f), out;
        rs.process(in.data(), n, out);
        // Outputs live at upsampled indices 0, M, 2M, ... <= (n-1)*L.
        const long expected = (long)((std::int64_t)(n - 1) * 40 / 147) + 1;  // 120000
        printf("  info: 10 s @44.1k -> %zu outputs (expected %ld)\n", out.size(), expected);
        check((long)out.size() == expected, "10 s of 44.1k input -> exactly 120000 outputs at 12k");
    }

    // 3. Unity DC gain — per-branch normalization must hold for every phase.
    {
        RationalResampler rs(40, 147);
        const int n = 44100;
        std::vector<float> in(n, 1.0f), out;
        rs.process(in.data(), n, out);
        float worst = 0.0f;
        for (std::size_t i = out.size() / 2; i < out.size(); ++i) {
            worst = std::max(worst, std::fabs(out[i] - 1.0f));
        }
        printf("  info: DC worst-case deviation %.6f\n", worst);
        check(worst < 1e-3f, "unity DC gain on all output phases");
    }

    // 4. The core #364 property: a 1500 Hz tone at 44.1 kHz stays 1500 Hz at
    //    12 kHz. (With the old floored /3 path it would land at ~1224 Hz.)
    {
        std::vector<float> out = resampledTone(44100, 40, 147, 1500.0, 1.0);
        const double f = zeroCrossFreq(out, 12000);
        printf("  info: 1500 Hz tone measured at %.2f Hz after 44.1k->12k\n", f);
        check(std::fabs(f - 1500.0) < 5.0, "1500 Hz tone stays 1500 Hz through 44.1k->12k");
        const float peak = steadyPeak(out);
        printf("  info: 1500 Hz passband gain %.4f\n", peak);
        check(peak > 0.95f && peak < 1.05f, "1500 Hz passband tone passes near unity");
    }

    // 5. A 2.5 kHz tone (upper FT8 band) also survives with correct frequency.
    {
        std::vector<float> out = resampledTone(44100, 40, 147, 2500.0, 1.0);
        const double f = zeroCrossFreq(out, 12000);
        check(std::fabs(f - 2500.0) < 5.0, "2500 Hz tone stays 2500 Hz through 44.1k->12k");
    }

    // 6. Alias rejection: 10 kHz at 44.1k would fold to |10000 - 12000| = 2 kHz —
    //    mid-FT8-band — without the low-pass. Must be crushed.
    {
        std::vector<float> out = resampledTone(44100, 40, 147, 10000.0, 1.0);
        const float peak = steadyPeak(out);
        printf("  info: 10 kHz alias residue %.5f\n", peak);
        check(peak < 0.01f, "10 kHz alias tone rejected > 40 dB");
    }

    // 7. Streaming across buffers matches a single call (state carries over —
    //    the capture loop feeds ~1 ms USB packets).
    {
        const int n = 44100 / 5;
        std::vector<float> in(n);
        for (int i = 0; i < n; ++i) {
            in[i] = static_cast<float>(std::sin(2.0 * M_PI * 700.0 * i / 44100.0));
        }
        RationalResampler whole(40, 147);
        std::vector<float> outWhole;
        whole.process(in.data(), n, outWhole);

        RationalResampler chunked(40, 147);
        std::vector<float> outChunks;
        const int chunk = 97;  // deliberately not aligned to L or M
        for (int off = 0; off < n; off += chunk) {
            const int len = std::min(chunk, n - off);
            chunked.process(in.data() + off, len, outChunks);
        }
        bool same = outWhole.size() == outChunks.size();
        for (std::size_t i = 0; same && i < outWhole.size(); ++i) {
            same = outWhole[i] == outChunks[i];
        }
        check(same, "chunked streaming output identical to single-call output");
    }

    if (g_failures) {
        printf("%d FAILURE(S)\n", g_failures);
        return 1;
    }
    printf("all rational_resampler tests passed\n");
    return 0;
}
