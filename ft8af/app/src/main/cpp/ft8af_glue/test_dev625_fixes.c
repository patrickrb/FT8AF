// Regression tests for the dev-625 fixes:
//
//   (1) ft8_snr(). dev-625 added the 2500 Hz calibration (on-air FT8 SNRs read
//       ~+16 where WSJT-X said ~-10). Issue #937 then replaced the FT8 noise
//       estimate: it took the other 7 Costas tones of each sync symbol as noise,
//       which signal leakage raised along with the signal, so every report was
//       squeezed into about -14..-2 dB. Noise now comes from the off-signal
//       spectral baseline around the candidate. We pin the arithmetic on
//       synthetic waterfalls with known magnitudes, check the leakage and busy-
//       band cases that broke the old estimate, and pin the unchanged FT4 path.
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
// (1) ft8_snr
//
// Waterfall magnitudes are 0.5 dB steps of power, so a byte difference d is a
// power ratio of 10^(d/20). With a flat floor N and the expected Costas tone at
// S on every sync symbol, the FT8 path reports
//   round(10*log10(10^((S-N)/20) - 1) - FT8_SNR_CAL_DB(30.5))
// clamped to [-24, +40]. The magnitude steps below are odd so the expected
// value sits clear of a rounding boundary.
// ---------------------------------------------------------------------------
#define SNR_NUM_BINS 200
#define SNR_NUM_BLOCKS 93 // a full 15 s FT8 slot at 0.16 s per symbol
#define SNR_CAND_BIN 60

typedef struct
{
    waterfall_t wf;
    candidate_t cand;
} snr_fixture_t;

// Row of the candidate's (time_sub, freq_sub) plane in the given block.
static uint8_t* snr_row(snr_fixture_t* fx, int block)
{
    const waterfall_t* wf = &fx->wf;
    int plane = (fx->cand.time_sub * wf->freq_osr + fx->cand.freq_sub) * wf->num_bins;
    return fx->wf.mag + (size_t)block * wf->block_stride + plane;
}

// Two time and two frequency sub-planes; the candidate sits in (1, 1). The
// other planes are filled loud so reading the wrong plane fails the checks.
static void snr_fixture_init(snr_fixture_t* fx, ftx_protocol_t protocol, int noise)
{
    memset(fx, 0, sizeof(*fx));
    waterfall_t* wf = &fx->wf;
    wf->num_bins = SNR_NUM_BINS;
    wf->time_osr = 2;
    wf->freq_osr = 2;
    wf->block_stride = wf->time_osr * wf->freq_osr * wf->num_bins;
    wf->max_blocks = SNR_NUM_BLOCKS;
    wf->num_blocks = SNR_NUM_BLOCKS;
    wf->protocol = protocol;
    wf->mag = (uint8_t*)malloc((size_t)wf->max_blocks * wf->block_stride);
    memset(wf->mag, 250, (size_t)wf->max_blocks * wf->block_stride);

    fx->cand.time_sub = 1;
    fx->cand.freq_sub = 1;
    fx->cand.freq_offset = SNR_CAND_BIN;
    fx->cand.time_offset = 0;

    for (int block = 0; block < wf->num_blocks; ++block)
        memset(snr_row(fx, block), noise, (size_t)wf->num_bins);
}

// Expected Costas tone at S on every FT8 sync symbol. If leak > 0, the other
// 7 tones of each sync symbol get S - leak: the energy that spills in from the
// neighbouring symbol and from frequency leakage on a real waterfall.
static void snr_put_ft8_sync(snr_fixture_t* fx, int S, int leak)
{
    for (int block = 0; block < FT8_NN; ++block)
    {
        int k = block % FT8_SYNC_OFFSET;
        if (k >= FT8_LENGTH_SYNC)
            continue;
        uint8_t* tones = snr_row(fx, fx->cand.time_offset + block) + fx->cand.freq_offset;
        if (leak > 0)
            for (int t = 0; t < 8; ++t)
                tones[t] = (uint8_t)(S - leak);
        tones[kFT8_Costas_pattern[k]] = (uint8_t)S;
    }
}

static int snr_ft8(int S, int N)
{
    snr_fixture_t fx;
    snr_fixture_init(&fx, FTX_PROTOCOL_FT8, N);
    snr_put_ft8_sync(&fx, S, 0);
    int snr = ft8_snr(&fx.wf, &fx.cand);
    free(fx.wf.mag);
    return snr;
}

static void test_ft8_snr_baseline(void)
{
    printf("test_ft8_snr_baseline:\n");

    // S-N = 81 -> 40.5 dB per bin -> 10*log10(10^4.05 - 1) - 30.5 = +10.
    CHECK_EQ(snr_ft8(201, 120), 10, "S-N=81 -> +10 dB");
    // S-N = 61 -> 30.5 dB per bin -> 0. A 20-step (10 dB) change in the sync
    // tone moves the report by exactly 10 dB: unit slope.
    CHECK_EQ(snr_ft8(181, 120), 0, "S-N=61 -> 0 dB");
    // S-N = 21 -> 10.5 dB per bin -> 10*log10(10^1.05 - 1) - 30.5 = -20.
    CHECK_EQ(snr_ft8(141, 120), -20, "S-N=21 -> -20 dB");
    // The absolute level doesn't matter, only the ratio to the floor.
    CHECK_EQ(snr_ft8(161, 80), 10, "S-N=81 at a lower floor -> +10 dB");

    // Clamps: a sync tone at the floor reads -24 (WSJT-X's floor), and a very
    // strong local stops at +40.
    CHECK_EQ(snr_ft8(120, 120), -24, "S==N -> -24 dB floor");
    CHECK_EQ(snr_ft8(90, 120), -24, "S<N -> -24 dB floor");
    CHECK_EQ(snr_ft8(250, 60), 40, "S-N=190 -> +40 dB clamp");

    // No sync symbols inside the waterfall: deep floor rather than garbage.
    snr_fixture_t fx;
    snr_fixture_init(&fx, FTX_PROTOCOL_FT8, 120);
    fx.cand.time_offset = SNR_NUM_BLOCKS; // starts after the last block
    CHECK_EQ(ft8_snr(&fx.wf, &fx.cand), -24, "no sync symbols in range -> -24");
    fx.wf.num_blocks = 0;
    fx.cand.time_offset = 0;
    CHECK_EQ(ft8_snr(&fx.wf, &fx.cand), -24, "empty waterfall -> -24");
    free(fx.wf.mag);

    // Too few bins to measure the noise: a 12-bin waterfall leaves 4 bins above
    // the tones and none below, under the 8-bin minimum per side.
    snr_fixture_init(&fx, FTX_PROTOCOL_FT8, 120);
    snr_put_ft8_sync(&fx, 201, 0);
    fx.wf.num_bins = 12;
    CHECK_EQ(ft8_snr(&fx.wf, &fx.cand), -24, "no side wide enough -> -24");
    // Candidate past the end of the bins: empty window, no read of its tones.
    fx.cand.freq_offset = 12 + 48;
    CHECK_EQ(ft8_snr(&fx.wf, &fx.cand), -24, "window outside the waterfall -> -24");
    free(fx.wf.mag);
}

// The #937 failure: leakage into the other Costas tones scales with the
// signal. The old estimate used those tones as noise, so a 30 dB stronger
// signal read about the same. The report must now track the signal.
static void test_ft8_snr_leakage(void)
{
    printf("test_ft8_snr_leakage:\n");

    int snr[3];
    const int S[3] = { 161, 201, 221 };
    for (int i = 0; i < 3; ++i)
    {
        snr_fixture_t fx;
        snr_fixture_init(&fx, FTX_PROTOCOL_FT8, 120);
        snr_put_ft8_sync(&fx, S[i], 24); // other tones 12 dB below the sync tone
        snr[i] = ft8_snr(&fx.wf, &fx.cand);
        free(fx.wf.mag);
    }
    // Same values as without leakage: the other tones are not noise.
    CHECK_EQ(snr[0], -10, "leaky S-N=41 -> -10 dB");
    CHECK_EQ(snr[1], 10, "leaky S-N=81 -> +10 dB");
    CHECK_EQ(snr[2], 20, "leaky S-N=101 -> +20 dB");
    CHECK_TRUE(snr[2] > 0, "strong signal reads above 0 dB");
}

// Neighbouring signals in the +/-300 Hz noise window must not raise the floor:
// the noise is a low percentile, not a mean.
static void test_ft8_snr_busy_band(void)
{
    printf("test_ft8_snr_busy_band:\n");

    snr_fixture_t fx;
    snr_fixture_init(&fx, FTX_PROTOCOL_FT8, 120);
    snr_put_ft8_sync(&fx, 201, 0);
    // Four other 8-tone signals 40 dB over the floor for the whole slot, two
    // on each side, inside the window.
    const int neighbours[4] = { SNR_CAND_BIN - 40, SNR_CAND_BIN - 20,
                                SNR_CAND_BIN + 16, SNR_CAND_BIN + 36 };
    for (int block = 0; block < SNR_NUM_BLOCKS; ++block)
        for (int i = 0; i < 4; ++i)
            memset(snr_row(&fx, block) + neighbours[i], 200, 8);
    CHECK_EQ(ft8_snr(&fx.wf, &fx.cand), 10, "busy band: S-N=81 still +10 dB");
    free(fx.wf.mag);

    // Candidate at either end of the waterfall: the window clamps, the empty
    // side is skipped and the other side finds the floor.
    snr_fixture_init(&fx, FTX_PROTOCOL_FT8, 120);
    fx.cand.freq_offset = 0;
    snr_put_ft8_sync(&fx, 201, 0);
    CHECK_EQ(ft8_snr(&fx.wf, &fx.cand), 10, "bottom edge: S-N=81 -> +10 dB");
    free(fx.wf.mag);
    snr_fixture_init(&fx, FTX_PROTOCOL_FT8, 120);
    fx.cand.freq_offset = SNR_NUM_BINS - 8;
    snr_put_ft8_sync(&fx, 201, 0);
    CHECK_EQ(ft8_snr(&fx.wf, &fx.cand), 10, "top edge: S-N=81 -> +10 dB");
    free(fx.wf.mag);
}

// A rig's audio filter edge inside the noise window: the bins past it sit well
// below the in-band floor. A percentile over the whole window would land on
// them and inflate the report by up to the roll-off depth; measuring each side
// separately and keeping the louder one ignores the rolled-off side.
static void test_ft8_snr_filter_edge(void)
{
    printf("test_ft8_snr_filter_edge:\n");

    for (int upper = 0; upper < 2; ++upper)
    {
        snr_fixture_t fx;
        snr_fixture_init(&fx, FTX_PROTOCOL_FT8, 120);
        // Filter edge 2 bins past the tones; 30 steps (15 dB) of roll-off.
        int edge = upper ? SNR_CAND_BIN + 10 : SNR_CAND_BIN - 2;
        for (int block = 0; block < SNR_NUM_BLOCKS; ++block)
        {
            uint8_t* row = snr_row(&fx, block);
            if (upper)
                memset(row + edge, 90, (size_t)(SNR_NUM_BINS - edge));
            else
                memset(row, 90, (size_t)edge);
        }
        snr_put_ft8_sync(&fx, 201, 0);
        CHECK_EQ(ft8_snr(&fx.wf, &fx.cand), 10,
                 upper ? "filter edge above: S-N=81 still +10 dB" : "filter edge below: S-N=81 still +10 dB");
        free(fx.wf.mag);
    }
}

// The FT4/FT2 branch is out of scope for #937 and must not change: per sync
// symbol the expected tone is signal and the other three are noise, in raw
// byte units, minus FT4_SNR_CAL_DB (20).
static void test_ft4_snr_unchanged(void)
{
    printf("test_ft4_snr_unchanged:\n");

    const ftx_protocol_t protocols[2] = { FTX_PROTOCOL_FT4, FTX_PROTOCOL_FT2 };
    for (int p = 0; p < 2; ++p)
    {
        snr_fixture_t fx;
        snr_fixture_init(&fx, protocols[p], 120);
        for (int m = 0; m < FT4_NUM_SYNC; ++m)
            for (int k = 0; k < FT4_LENGTH_SYNC; ++k)
            {
                int block = 1 + FT4_SYNC_OFFSET * m + k;
                if (block >= SNR_NUM_BLOCKS)
                    continue; // past the slot; ft8_snr stops there too
                uint8_t* tones = snr_row(&fx, block) + fx.cand.freq_offset;
                tones[kFT4_Costas_pattern[m][k]] = 150;
            }
        CHECK_EQ(ft8_snr(&fx.wf, &fx.cand), 10,
                 p == 0 ? "FT4: S-N=30 -> 30 - 20 = +10" : "FT2: S-N=30 -> 30 - 20 = +10");
        free(fx.wf.mag);
    }
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
    test_ft8_snr_baseline();
    test_ft8_snr_leakage();
    test_ft8_snr_busy_band();
    test_ft8_snr_filter_edge();
    test_ft4_snr_unchanged();
    test_display_mapping();

    if (g_failures == 0)
    {
        printf("\nALL DEV-625 TESTS PASSED\n");
        return 0;
    }
    printf("\n%d DEV-625 TEST(S) FAILED\n", g_failures);
    return 1;
}
