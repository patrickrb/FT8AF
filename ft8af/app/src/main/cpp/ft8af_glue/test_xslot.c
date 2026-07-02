// Host unit tests for ft8_xslot.c — cross-slot decoding. Run by
// run_host_tests.sh / .ps1.
//
// Covered:
//   1. LLR accumulation: two repeats of one message, each with too many
//      erased bits to decode alone, decode from the summed LLRs — and only
//      across slots of the SAME parity, only once (the entry is consumed).
//   2. Message-history retry: a remembered decode is re-accepted from weak
//      LLRs at the same frequency and parity; rejected for pure noise, for
//      the wrong slot parity, and for a different frequency.
//   3. ft8_xslot_clear drops all state.

#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <ft8/constants.h>
#include <ft8/crc.h>
#include <ft8/decode.h>
#include <ft8/message.h>

#include "decode_params.h"
#include "ft8_xslot.h"

static int g_failures = 0;

static void check(bool ok, const char* what)
{
    printf("  %s %s\n", ok ? "ok:  " : "FAIL:", what);
    if (!ok)
        g_failures++;
}

static uint32_t g_seed = 1;
static uint32_t prng(void)
{
    g_seed = g_seed * 1664525u + 1013904223u;
    return g_seed;
}
static float prng_uniform(void)
{
    return (float)(prng() >> 8) / 16777216.0f;
}

// 174-bit codeword for a message text (same construction as test_osd.c).
static bool make_codeword(const char* text, uint8_t cw[FTX_LDPC_N], uint8_t payload_out[10])
{
    ftx_message_t msg;
    ftx_message_init(&msg);
    if (ftx_message_encode(&msg, NULL, text) != FTX_MESSAGE_RC_OK)
        return false;
    memcpy(payload_out, msg.payload, 10);
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

// A "repeat" of the codeword: clean moderate LLRs with n_erase seeded
// positions zeroed (deep-faded symbols) — enough that a single repeat's
// basis must include erased bits and the decode fails.
static void make_repeat(const uint8_t cw[FTX_LDPC_N], int n_erase, uint32_t seed,
                        float llrs[FTX_LDPC_N])
{
    g_seed = seed;
    for (int i = 0; i < FTX_LDPC_N; ++i)
        llrs[i] = cw[i] ? 0.5f : -0.5f;
    int erased = 0;
    while (erased < n_erase)
    {
        int i = (int)(prng_uniform() * FTX_LDPC_N);
        if (llrs[i] == 0.0f)
            continue;
        llrs[i] = 0.0f;
        erased++;
    }
}

static const char* TEXT = "OK2BJ JG1SRO -15";

int main(void)
{
    uint8_t cw[FTX_LDPC_N], payload[10];
    check(make_codeword(TEXT, cw, payload), "message encodes");

    ftx_message_t message;
    decode_status_t status;
    float rep_a[FTX_LDPC_N], rep_b[FTX_LDPC_N];
    // 100 erasures per repeat: 74 clean bits < 91, so the decoder cannot
    // build a basis from clean bits alone and fails (asserted below). The
    // two repeats erase different positions (independent fading), so the
    // sum has ~57 joint erasures — well within reach.
    make_repeat(cw, 100, 11, rep_a);
    make_repeat(cw, 100, 22, rep_b);

    // ---- 1. accumulation across same-parity slots ---------------------------
    printf("test_xslot_accumulate:\n");
    ft8_xslot_clear();
    check(!ftx_decode_llrs(FTX_PROTOCOL_FT8, rep_a, FT8AF_LDPC_ITERS_DEEP,
                           FT8AF_OSD_DEPTH_DEEP, FT8AF_OSD_LDPC_ERR_GATE,
                           &message, &status),
          "first repeat fails alone");
    check(!ftx_decode_llrs(FTX_PROTOCOL_FT8, rep_b, FT8AF_LDPC_ITERS_DEEP,
                           FT8AF_OSD_DEPTH_DEEP, FT8AF_OSD_LDPC_ERR_GATE,
                           &message, &status),
          "second repeat fails alone");

    ft8_xslot_store(1453.0f, 0, rep_a);
    check(!ft8_xslot_try_accumulate(1453.5f, 15000, rep_b, FT8AF_LDPC_ITERS_DEEP,
                                    FT8AF_OSD_DEPTH_DEEP, FT8AF_OSD_LDPC_ERR_GATE,
                                    &message, &status),
          "wrong slot parity does not combine");
    check(ft8_xslot_try_accumulate(1453.5f, 30000, rep_b, FT8AF_LDPC_ITERS_DEEP,
                                   FT8AF_OSD_DEPTH_DEEP, FT8AF_OSD_LDPC_ERR_GATE,
                                   &message, &status),
          "same parity, next repeat: combined LLRs DECODE");
    check(memcmp(message.payload, payload, 10) == 0, "decoded payload is the message");
    check(!ft8_xslot_try_accumulate(1453.5f, 30000, rep_b, FT8AF_LDPC_ITERS_DEEP,
                                    FT8AF_OSD_DEPTH_DEEP, FT8AF_OSD_LDPC_ERR_GATE,
                                    &message, &status),
          "entry is consumed after a successful combine");

    ft8_xslot_store(1453.0f, 0, rep_a);
    check(!ft8_xslot_try_accumulate(1500.0f, 30000, rep_b, FT8AF_LDPC_ITERS_DEEP,
                                    FT8AF_OSD_DEPTH_DEEP, FT8AF_OSD_LDPC_ERR_GATE,
                                    &message, &status),
          "frequency outside the tolerance does not combine");

    // ---- 2. message-history retry --------------------------------------------
    printf("test_xslot_history:\n");
    ft8_xslot_clear();
    ft8_xslot_remember(payload, 1453.0f, 0);

    // Weak but genuine repeat: correct-signed LLRs at low confidence.
    float weak[FTX_LDPC_N];
    g_seed = 33;
    for (int i = 0; i < FTX_LDPC_N; ++i)
        weak[i] = (cw[i] ? 1.0f : -1.0f) * 0.1f + 0.05f * (2.0f * prng_uniform() - 1.0f);
    float corr = 0.0f;
    check(ft8_xslot_try_history(1453.5f, 30000, weak, &message, &corr),
          "weak genuine repeat is accepted");
    printf("  (correlation %.2f)\n", corr);
    check(corr >= FT8AF_XSLOT_HIST_MIN, "correlation clears the gate");
    check(memcmp(message.payload, payload, 10) == 0, "accepted payload is the remembered message");

    check(!ft8_xslot_try_history(1453.5f, 15000, weak, &message, NULL),
          "wrong slot parity is rejected");
    check(!ft8_xslot_try_history(1470.0f, 30000, weak, &message, NULL),
          "different frequency is rejected");

    float noise[FTX_LDPC_N];
    g_seed = 44;
    for (int i = 0; i < FTX_LDPC_N; ++i)
        noise[i] = 2.0f * prng_uniform() - 1.0f;
    check(!ft8_xslot_try_history(1453.5f, 30000, noise, &message, &corr),
          "pure noise at the same frequency is rejected");
    printf("  (noise correlation %.2f)\n", corr);

    // ---- 3. clear -------------------------------------------------------------
    printf("test_xslot_clear:\n");
    ft8_xslot_store(1453.0f, 0, rep_a);
    ft8_xslot_remember(payload, 1453.0f, 0);
    ft8_xslot_clear();
    check(!ft8_xslot_try_accumulate(1453.0f, 30000, rep_b, FT8AF_LDPC_ITERS_DEEP,
                                    FT8AF_OSD_DEPTH_DEEP, FT8AF_OSD_LDPC_ERR_GATE,
                                    &message, &status),
          "no accumulation after clear");
    check(!ft8_xslot_try_history(1453.0f, 30000, weak, &message, NULL),
          "no history after clear");

    if (g_failures == 0)
    {
        printf("all cross-slot tests passed\n");
        return 0;
    }
    printf("%d cross-slot test(s) FAILED\n", g_failures);
    return 1;
}
