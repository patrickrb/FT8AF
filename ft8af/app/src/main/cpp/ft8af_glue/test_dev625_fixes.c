// Regression tests for the dev-625 fixes:
//
//   (1) ft8_snr() FT8 bandwidth calibration. A tester reported on-air FT8 SNRs
//       reading ~+16 where WSJT-X says ~-10: the FT8 path returned a per-FFT-bin
//       ratio with no 2500 Hz reference, unlike the FT4 path (FT4_SNR_CAL_DB).
//       We pin the calibrated arithmetic on a synthetic waterfall with known
//       Costas-tone magnitudes.
//
//   (2) ft8af_magnitudes_to_display(). The from-source waterfall mapped
//       magnitudes LINEARLY with per-frame max auto-gain, collapsing the noise
//       floor and weak signals to black ("can't see a thing"). We verify the new
//       log / noise-floor-referenced mapping keeps the floor visible, saturates a
//       strong signal, and that one loud bin no longer darkens the rest.
//
// HOST BUILD (no device): run_host_tests.ps1 / run_host_tests.sh build and run
// this alongside test_golden_encode.c. Exit code 0 == all pass.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ft8/decode.h"
#include "ft8/constants.h"

#include "fft_display.h"

// ft8_snr is defined (non-static) in decode.c but not declared in decode.h.
int ft8_snr(const waterfall_t* wf, const candidate_t* candidate);

static int g_failures = 0;

#define CHECK_EQ(actual, expected, label)                                       \
    do {                                                                        \
        int a_ = (actual), e_ = (expected);                                     \
        if (a_ != e_) {                                                         \
            printf("  FAIL: %s: got %d, expected %d\n", (label), a_, e_);       \
            ++g_failures;                                                       \
        } else {                                                                \
            printf("  ok:   %s == %d\n", (label), e_);                          \
        }                                                                       \
    } while (0)

#define CHECK_TRUE(cond, label)                                                 \
    do {                                                                        \
        if (!(cond)) {                                                          \
            printf("  FAIL: %s\n", (label));                                    \
            ++g_failures;                                                       \
        } else {                                                                \
            printf("  ok:   %s\n", (label));                                    \
        }                                                                       \
    } while (0)

// ---------------------------------------------------------------------------
// (1) ft8_snr FT8 calibration
//
// Build a waterfall whose three Costas sync groups (symbols 0-6, 36-42, 72-78)
// each have the expected tone at magnitude S and the other seven tones at N.
// Per sync symbol the noise estimate is (3 + 8N's minus the signal)/7 == N, so
//   raw per-bin dB = (S - N) / 2   (magnitudes are 0.5 dB units)
//   reported       = (S - N) / 2 - FT8_SNR_CAL_DB(26)
// ---------------------------------------------------------------------------
static void fill_sync(uint8_t* mag, int block_stride, int S, int N)
{
    for (int block = 0; block < FT8_NN; ++block)
    {
        int k = block % FT8_SYNC_OFFSET;
        if (k >= FT8_LENGTH_SYNC)
            continue; // data symbol — value irrelevant
        uint8_t* p8 = mag + (block * block_stride);
        for (int m = 0; m < 8; ++m)
            p8[m] = (uint8_t)N;
        p8[kFT8_Costas_pattern[k]] = (uint8_t)S;
    }
}

static int run_snr(int S, int N)
{
    const int num_bins = 64;
    const int time_osr = 2, freq_osr = 2;
    waterfall_t wf;
    memset(&wf, 0, sizeof(wf));
    wf.num_bins = num_bins;
    wf.time_osr = time_osr;
    wf.freq_osr = freq_osr;
    wf.block_stride = time_osr * freq_osr * num_bins;
    wf.max_blocks = 100;
    wf.num_blocks = 100;
    wf.protocol = FTX_PROTOCOL_FT8;
    wf.mag = (uint8_t*)calloc((size_t)wf.max_blocks * wf.block_stride, 1);

    fill_sync(wf.mag, wf.block_stride, S, N);

    candidate_t cand;
    memset(&cand, 0, sizeof(cand));
    // time_sub=freq_sub=freq_offset=time_offset=0 -> mag base is wf.mag[0].

    int snr = ft8_snr(&wf, &cand);
    free(wf.mag);
    return snr;
}

static void test_ft8_snr_calibration(void)
{
    printf("test_ft8_snr_calibration:\n");

    // S-N = 40 -> 40/2 - 26 = -6 (a normal, slightly-strong report).
    CHECK_EQ(run_snr(160, 120), -6, "S-N=40 -> -6 dB");

    // S-N = 120 -> 120/2 - 26 = 60 - 26 = +34 (a very strong local).
    CHECK_EQ(run_snr(220, 100), 34, "S-N=120 -> +34 dB");

    // S == N (noise only): 0 - 26 = -26 (deep, near the decode floor).
    CHECK_EQ(run_snr(100, 100), -26, "S==N -> -26 dB (floor)");

    // Sanity: without the 26 dB calibration this last case would read 0 dB, the
    // exact "+16 where it should be negative" class of bug we are fixing.
    CHECK_TRUE(run_snr(100, 100) < 0, "noise-only report is negative");
}

// ---------------------------------------------------------------------------
// (2) ft8af_magnitudes_to_display
// ---------------------------------------------------------------------------
static void test_display_mapping(void)
{
    printf("test_display_mapping:\n");

    const int n = 200;
    float* mag = (float*)malloc(n * sizeof(float));
    int* out_raw = (int*)malloc(n * sizeof(int));
    int* out_dn = (int*)malloc(n * sizeof(int));

    // Flat noise field (mag 1.0 -> 0 dB) with one strong spike 60 dB above it.
    for (int i = 0; i < n; ++i)
        mag[i] = 1.0f;
    mag[100] = 1000.0f; // 20*log10(1000) = 60 dB

    ft8af_magnitudes_to_display(mag, n, out_raw, 0);
    ft8af_magnitudes_to_display(mag, n, out_dn, 1);

    // Raw view: the noise floor (median dB == 0) maps to the visible floor level
    // (40), NOT to black. This is the core regression: under the old linear
    // auto-gain the floor collapsed to ~0 and the waterfall looked empty.
    CHECK_EQ(out_raw[0], 40, "raw: noise floor -> 40 (visible)");
    CHECK_TRUE(out_raw[0] > 0, "raw: noise floor is not black");

    // The 60 dB spike saturates.
    CHECK_EQ(out_raw[100], 255, "raw: strong spike -> 255");

    // The single loud bin must not darken the rest of the row (old max auto-gain
    // bug): every noise bin stays at the floor level regardless of the spike.
    int all_floor = 1;
    for (int i = 0; i < n; ++i)
        if (i != 100 && out_raw[i] != 40) all_floor = 0;
    CHECK_TRUE(all_floor, "raw: noise unaffected by a strong neighbor");

    // Denoise view pushes the noise floor to black while the signal stays bright.
    CHECK_EQ(out_dn[0], 0, "denoise: noise floor -> 0 (black)");
    CHECK_TRUE(out_dn[100] > 200, "denoise: spike still bright");

    // Monotonicity: a louder bin never maps darker than a quieter one.
    float twin[3] = { 1.0f, 10.0f, 100.0f }; // 0, 20, 40 dB
    int twout[3];
    ft8af_magnitudes_to_display(twin, 3, twout, 0);
    CHECK_TRUE(twout[0] <= twout[1] && twout[1] <= twout[2], "monotonic in dB");

    // Degenerate sizes don't crash / write.
    int dummy = 123;
    ft8af_magnitudes_to_display(mag, 0, &dummy, 0);
    CHECK_EQ(dummy, 123, "n_bins=0 writes nothing");

    free(mag);
    free(out_raw);
    free(out_dn);
}

int main(void)
{
    test_ft8_snr_calibration();
    test_display_mapping();

    if (g_failures == 0)
    {
        printf("\nALL DEV-625 TESTS PASSED\n");
        return 0;
    }
    printf("\n%d DEV-625 TEST(S) FAILED\n", g_failures);
    return 1;
}
