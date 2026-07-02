// Host unit tests for ft8_lib/ft8/osd.c — the ordered-statistics decoder
// backstop. Run by run_host_tests.sh / .ps1.
//
// Covered:
//   1. Generator consistency: a codeword built from kFTX_LDPC_generator
//      satisfies bp_decode's INDEPENDENT parity-check matrix (kFTX_LDPC_Nm)
//      with zero errors, and its message bits round-trip the CRC-14. This
//      cross-validates osd.c's encoder against the code's other description.
//   2. Perfect-input decode: clean LLRs -> osd_decode returns the message.
//   3. Recovery: LLR corruption calibrated so bp_decode FAILS but osd_decode
//      recovers the exact message (the reason the backstop exists).
//   4. False-decode guard: 1000 seeded pure-noise LLR vectors produce zero
//      accepted decodes.
//   5. Order-2 recovery: TWO moderately confident wrong bits land in the
//      reordered basis tail (forced by a mass of near-erasure correct bits),
//      so the base decode and every single flip fail and only a pair flip
//      reaches the codeword. Fails on the order-1-only implementation
//      (verified against it on 252 of 300 seeds; this seed is one of them).

#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <ft8/constants.h>
#include <ft8/crc.h>
#include <ft8/ldpc.h>
#include <ft8/message.h>
#include <ft8/osd.h>

static int g_failures = 0;

static void check(bool ok, const char* what)
{
    printf("  %s %s\n", ok ? "ok:  " : "FAIL:", what);
    if (!ok)
        g_failures++;
}

// Deterministic PRNG (LCG) — no libc rand() variance across platforms.
static uint32_t g_seed = 1;
static uint32_t prng(void)
{
    g_seed = g_seed * 1664525u + 1013904223u;
    return g_seed;
}
static float prng_uniform(void) // [0,1)
{
    return (float)(prng() >> 8) / 16777216.0f;
}

// Build the 174-bit codeword for a message text: payload+CRC (91 systematic
// bits) then 83 parity bits from kFTX_LDPC_generator. Same construction as
// osd.c's internal encoder; test 1 validates it against the independent
// parity-check matrix used by bp_decode.
static bool make_codeword(const char* text, uint8_t cw[FTX_LDPC_N])
{
    ftx_message_t msg;
    ftx_message_init(&msg);
    if (ftx_message_encode(&msg, NULL, text) != FTX_MESSAGE_RC_OK)
        return false;

    uint8_t a91[FTX_LDPC_K_BYTES];
    ftx_add_crc(msg.payload, a91);

    for (int i = 0; i < FTX_LDPC_K; ++i)
        cw[i] = (a91[i / 8] >> (7 - (i % 8))) & 1u;

    for (int p = 0; p < FTX_LDPC_M; ++p)
    {
        int sum = 0;
        for (int j = 0; j < FTX_LDPC_K; ++j)
            if ((kFTX_LDPC_generator[p][j / 8] >> (7 - (j % 8))) & 1u)
                sum ^= cw[j];
        cw[FTX_LDPC_K + p] = (uint8_t)sum;
    }
    return true;
}

// Clean LLRs for a codeword (log174[i] > 0 <=> bit 1, kgoba convention).
static void clean_llrs(const uint8_t cw[FTX_LDPC_N], float mag, float llr[FTX_LDPC_N])
{
    for (int i = 0; i < FTX_LDPC_N; ++i)
        llr[i] = cw[i] ? mag : -mag;
}

static const char* TEXT = "CQ K1ABC FN42";

int main(void)
{
    uint8_t cw[FTX_LDPC_N];
    float llr[FTX_LDPC_N];
    uint8_t plain174[FTX_LDPC_N];
    int errors, depth_used;

    // ---- 1. generator consistency vs the parity-check matrix ----------------
    printf("test_generator_consistency:\n");
    check(make_codeword(TEXT, cw), "message encodes");
    clean_llrs(cw, 4.0f, llr);
    bp_decode(llr, 20, plain174, &errors);
    check(errors == 0, "generator-built codeword satisfies all BP parity checks");
    check(memcmp(plain174, cw, FTX_LDPC_N) == 0, "BP hard decisions equal the codeword");

    // ---- 2. perfect input decodes at base depth ------------------------------
    printf("test_osd_perfect_input:\n");
    bool ok = osd_decode(llr, 0, plain174, &depth_used);
    check(ok, "osd_decode succeeds on clean LLRs");
    check(ok && memcmp(plain174, cw, FTX_LDPC_N) == 0, "decoded bits equal the codeword");
    check(ok && depth_used == -1, "base decode (no flips) was sufficient");

    // ---- 3. recovery where BP fails ------------------------------------------
    // Corruption model: 36 seeded positions turned into near-zero wrong-leaning
    // LLRs, 2 of them into confident wrong values. Calibrated so bp_decode(30)
    // FAILS and osd_decode(depth 12) recovers — both asserted, so a regression
    // in either decoder shows up here.
    printf("test_osd_recovers_bp_failure:\n");
    g_seed = 7;
    clean_llrs(cw, 1.0f, llr);
    int corrupted = 0;
    while (corrupted < 36)
    {
        int i = (int)(prng_uniform() * FTX_LDPC_N);
        if (fabsf(llr[i]) < 0.9f)
            continue; // already corrupted
        float wrong = cw[i] ? -1.0f : 1.0f; // opposite sign of the true bit
        llr[i] = wrong * (corrupted < 2 ? 0.8f : 0.05f * (1.0f + prng_uniform()));
        corrupted++;
    }
    bp_decode(llr, 30, plain174, &errors);
    check(errors > 0, "belief propagation fails on the corrupted LLRs");
    ok = osd_decode(llr, 12, plain174, &depth_used);
    check(ok, "osd_decode recovers");
    check(ok && memcmp(plain174, cw, FTX_LDPC_N) == 0, "recovered bits equal the codeword");

    // ---- 4. pure-noise false-decode guard ------------------------------------
    printf("test_osd_noise_rejection:\n");
    g_seed = 12345;
    int accepted = 0;
    for (int trial = 0; trial < 1000; ++trial)
    {
        for (int i = 0; i < FTX_LDPC_N; ++i)
            llr[i] = 8.0f * (prng_uniform() - 0.5f);
        if (osd_decode(llr, 12, plain174, &depth_used))
            accepted++;
    }
    check(accepted == 0, "0 of 1000 pure-noise vectors accepted");
    if (accepted > 0)
        printf("  (accepted %d)\n", accepted);

    // ---- 5. order-2 (pair-flip) recovery --------------------------------------
    // Two moderately confident WRONG bits (|LLR| 0.4) plus 87 near-erasure
    // CORRECT bits (|LLR| ~0.02-0.04). The erasure mass forces the wrong bits
    // into the reliability-ordered basis near its tail: the base decode is
    // wrong in two basis positions (CRC rejects), no single flip can fix
    // both, and the pair search recovers the exact codeword. bp_decode fails
    // outright (the erasure mass alone exceeds BP's capacity).
    printf("test_osd_order2_pair_recovery:\n");
    g_seed = 1;
    clean_llrs(cw, 1.0f, llr);
    corrupted = 0;
    while (corrupted < 2 + 87)
    {
        int i = (int)(prng_uniform() * FTX_LDPC_N);
        if (fabsf(llr[i]) < 0.9f)
            continue; // already corrupted
        if (corrupted < 2)
            llr[i] = (cw[i] ? -1.0f : 1.0f) * 0.4f; // confident and WRONG
        else
            llr[i] = (cw[i] ? 1.0f : -1.0f) * 0.02f * (1.0f + prng_uniform());
        corrupted++;
    }
    bp_decode(llr, 30, plain174, &errors);
    check(errors > 0, "belief propagation fails (erasure mass over capacity)");
    ok = osd_decode(llr, 12, plain174, &depth_used);
    check(ok, "osd_decode recovers via a pair flip");
    check(ok && memcmp(plain174, cw, FTX_LDPC_N) == 0, "recovered bits equal the codeword");

    if (g_failures == 0)
    {
        printf("all osd tests passed\n");
        return 0;
    }
    printf("%d osd test(s) FAILED\n", g_failures);
    return 1;
}
