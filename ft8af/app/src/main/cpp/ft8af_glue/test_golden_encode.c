// Golden-vector regression test for the FT8 native encode + hash surface.
//
// WHY THIS EXISTS
// ---------------
// The FT8 encode/decode logic *is* the product, yet the methods on
// GenerateFT8 (pack77, ft8_encode, synth_gfsk) and FT8Package (getHash10/12/22)
// are JNI `native` methods backed by the prebuilt closed-source libft8cn.so.
// They cannot be reached from a JVM unit test, so until now a silent regression
// in the encoded FT8 frames would have shipped straight to users (issue #61).
//
// The project is reconstructing that .so from source (ft8_lib @ 6f528128 + the
// ft8af_glue JNI layer). This test pins the *correct* output of the encode path
// as golden vectors so the reconstruction — or any future change to the vendored
// ft8_lib — must reproduce it bit-for-bit. It links the same C the rewrite ships
// (pack.c / encode.c / crc.c / constants.c), so it guards exactly the rewrite.
//
// TWO LAYERS OF ASSURANCE
// -----------------------
//   (A) GOLDEN SNAPSHOTS — pack77(message) must equal a frozen 10-byte payload,
//       and ft8_encode(payload) must equal a frozen 79-tone sequence. This
//       catches a *consistent* regression that a self-decoding round-trip would
//       happily pass.
//   (B) INDEPENDENT CROSS-CHECK — to prove the frozen vectors are genuine FT8
//       frames (not just whatever the encoder happens to emit today), each tone
//       sequence is decoded back WITHOUT the encoder: invert the Gray map, drop
//       the Costas sync symbols, rebuild the 174-bit codeword, confirm every
//       LDPC parity check is satisfied (ldpc_check == 0), and confirm the CRC
//       validates exactly the way ft8_lib's decoder validates it (decode.c).
//       The recovered 77-bit payload must match pack77's output, closing the
//       loop. This is the "generate + decode cross-check" methodology: (A) locks
//       regressions, (B) proves the lock is on the right value.
//
// The Costas arrays at symbols 0-6 / 36-42 / 72-78 are also asserted directly —
// those are what a receiver syncs on; corrupting them yields audible-but-
// undecodable TX (see CLAUDE.md "FT8 TX audio pipeline").
//
// HOW TO RUN  (host build, no device/emulator needed):
//   ft8af/app/src/main/cpp/ft8af_glue/run_host_tests.ps1
// Exit code 0 == all pass. See that script for the raw clang invocation.
//
// HOW TO REGENERATE THE GOLDENS (only when the FT8 spec/vectors intentionally
// change): run `run_host_tests.ps1 -Regen` (this binary's --emit mode), which
// prints paste-ready literals computed live from the current ft8_lib. Paste the
// three blocks over the kGolden* tables below; never hand-edit a single byte.

#include <stdio.h>
#include <string.h>
#include <stdint.h>

#include "ft8/pack.h"
#include "ft8/encode.h"
#include "ft8/crc.h"
#include "ft8/constants.h"

// ---------------------------------------------------------------------------
// Reference messages spanning the standard FT8 message-type space.
// ---------------------------------------------------------------------------
static const char* const kMsgs[] = {
    "CQ K1ABC FN42",    // CQ + grid           (Type 1, standard call)
    "CQ DX K1ABC FN42", // CQ DX directed
    "W9XYZ K1ABC FN42", // call-to-call + grid
    "W9XYZ K1ABC -15",  // signal report
    "W9XYZ K1ABC R-08", // R + report
    "W9XYZ K1ABC RRR",  // RRR
    "W9XYZ K1ABC RR73", // RR73
    "W9XYZ K1ABC 73",   // 73
    "CQ G4ABC/P IO91",  // /P suffix           (Type 2)
    "PJ4/K1ABC RR73",   // nonstandard compound (Type 4)
};
#define N_MSG ((int)(sizeof(kMsgs) / sizeof(kMsgs[0])))

// pack77(message) golden: 77-bit payload, 10 bytes MSB-first (low 3 bits of
// byte 9 are unused payload bits and are masked out of comparisons).
static const uint8_t kGoldenPayload[N_MSG][10] = {
    { 0x00,0x00,0x00,0x20,0x4D,0xEF,0x1A,0x8A,0x19,0x88 }, // CQ K1ABC FN42
    { 0x2C,0x91,0x27,0x42,0xE2,0x5D,0xA9,0x25,0x68,0x00 }, // CQ DX K1ABC FN42
    { 0x0C,0x29,0x3B,0x80,0x4D,0xEF,0x1A,0x8A,0x19,0x88 }, // W9XYZ K1ABC FN42
    { 0x0C,0x29,0x3B,0x80,0x4D,0xEF,0x1A,0x9F,0xA9,0x08 }, // W9XYZ K1ABC -15
    { 0x0C,0x29,0x3B,0x80,0x4D,0xEF,0x1A,0xBF,0xAA,0xC8 }, // W9XYZ K1ABC R-08
    { 0x0C,0x29,0x3B,0x80,0x4D,0xEF,0x1A,0x9F,0xA4,0x88 }, // W9XYZ K1ABC RRR
    { 0x0C,0x29,0x3B,0x80,0x4D,0xEF,0x1A,0x9F,0xA4,0xC8 }, // W9XYZ K1ABC RR73
    { 0x0C,0x29,0x3B,0x80,0x4D,0xEF,0x1A,0x9F,0xA5,0x08 }, // W9XYZ K1ABC 73
    { 0x2C,0x91,0x2D,0xF3,0xD6,0x11,0xE7,0x57,0xCE,0x00 }, // CQ G4ABC/P IO91
    { 0x56,0x7F,0xD3,0xB0,0x0A,0x0C,0x28,0xC7,0x40,0x00 }, // PJ4/K1ABC RR73
};

// ft8_encode(payload) golden: 79 tones (0..7).
static const uint8_t kGoldenTones[N_MSG][79] = {
    {3,1,4,0,6,5,2,0,0,0,0,0,0,0,0,1,0,0,5,4,7,6,7,0,4,6,0,6,0,2,1,5,3,3,4,3,3,1,4,0,6,5,2,7,3,6,0,1,1,0,4,7,5,1,7,0,0,7,3,3,4,7,4,5,4,5,5,1,3,3,5,4,3,1,4,0,6,5,2},
    {3,1,4,0,6,5,2,1,2,1,1,0,5,5,7,3,0,6,4,1,1,2,6,6,3,3,3,3,6,6,0,0,1,1,3,6,3,1,4,0,6,5,2,7,4,3,6,1,1,3,7,2,7,5,4,3,1,6,3,6,1,4,5,7,4,3,0,2,0,6,3,4,3,1,4,0,6,5,2},
    {3,1,4,0,6,5,2,0,2,0,3,5,5,7,2,5,0,0,5,4,7,6,7,0,4,6,0,6,0,2,1,5,3,5,7,2,3,1,4,0,6,5,2,0,5,3,1,6,5,5,7,4,0,6,1,7,4,0,3,0,0,4,3,4,7,2,2,5,4,1,2,2,3,1,4,0,6,5,2},
    {3,1,4,0,6,5,2,0,2,0,3,5,5,7,2,5,0,0,5,4,7,6,7,0,4,6,1,7,4,6,1,0,2,1,4,0,3,1,4,0,6,5,2,5,4,5,0,4,2,0,1,1,5,0,1,3,6,5,2,4,7,2,2,5,3,1,4,5,1,5,5,2,3,1,4,0,6,5,2},
    {3,1,4,0,6,5,2,0,2,0,3,5,5,7,2,5,0,0,5,4,7,6,7,0,4,6,2,7,4,6,3,4,3,5,4,6,3,1,4,0,6,5,2,2,3,1,5,7,4,3,0,3,2,5,5,7,1,1,7,0,0,5,6,2,6,1,4,5,6,0,7,4,3,1,4,0,6,5,2},
    {3,1,4,0,6,5,2,0,2,0,3,5,5,7,2,5,0,0,5,4,7,6,7,0,4,6,1,7,4,5,5,5,3,0,3,1,3,1,4,0,6,5,2,5,6,4,3,0,5,5,3,5,1,6,1,1,1,7,5,2,4,5,2,3,1,2,7,7,5,3,2,7,3,1,4,0,6,5,2},
    {3,1,4,0,6,5,2,0,2,0,3,5,5,7,2,5,0,0,5,4,7,6,7,0,4,6,1,7,4,5,5,4,2,4,1,2,3,1,4,0,6,5,2,1,3,4,5,0,4,3,1,0,0,7,5,3,3,2,6,2,0,6,6,1,2,7,6,4,1,2,4,3,3,1,4,0,6,5,2},
    {3,1,4,0,6,5,2,0,2,0,3,5,5,7,2,5,0,0,5,4,7,6,7,0,4,6,1,7,4,5,6,0,2,7,3,1,3,1,4,0,6,5,2,6,1,4,5,0,7,5,0,5,2,3,3,7,4,6,5,4,5,0,7,0,4,0,3,0,6,5,5,6,3,1,4,0,6,5,2},
    {3,1,4,0,6,5,2,1,2,1,1,0,5,6,6,7,5,7,6,2,0,3,1,7,1,4,6,2,7,1,4,0,1,3,4,6,3,1,4,0,6,5,2,6,6,1,3,4,1,4,7,5,0,6,2,2,7,4,0,5,2,7,1,7,7,2,5,2,1,2,0,4,3,1,4,0,6,5,2},
    {3,1,4,0,6,5,2,3,6,5,7,7,7,3,2,6,5,0,0,6,0,1,5,1,3,1,5,2,6,0,0,0,1,5,1,5,3,1,4,0,6,5,2,2,7,3,7,1,7,4,3,4,7,4,6,5,1,2,1,6,1,2,6,4,2,7,1,3,1,4,5,3,3,1,4,0,6,5,2},
};

// FT8Package callsign-hash golden (the n22 from jni_ft8af.cpp compute_n22; the
// 12- and 10-bit hashes are that value shifted right by 10 and 12).
static const char* const kCalls[] = {
    "K1ABC", "W9XYZ", "G4ABC/P", "PJ4/K1ABC", "VK7BO", "EA1ABC",
};
static const uint32_t kGoldenHash[][3] = { // { h22, h12, h10 }
    { 2920267u, 2851u, 712u }, // K1ABC
    { 3982604u, 3889u, 972u }, // W9XYZ
    { 3288979u, 3211u, 802u }, // G4ABC/P
    { 1420834u, 1387u, 346u }, // PJ4/K1ABC
    { 2117032u, 2067u, 516u }, // VK7BO
    {  527469u,  515u, 128u }, // EA1ABC
};
#define N_CALL ((int)(sizeof(kCalls) / sizeof(kCalls[0])))

// ---------------------------------------------------------------------------
// compute_n22 — the WSJT-X callsign hash that backs FT8Package.getHash10/12/22.
// This is the same arithmetic as save_callsign() in ft8_lib/ft8/message.c (the
// authoritative, tracked source the prebuilt libft8cn.so was built from):
// base-38 over an 11-char field via FT8_CHAR_TABLE_ALPHANUM_SPACE_SLASH, times
// the 47055833459 magic constant, top 22 bits; h12 = n22>>10, h10 = n22>>12.
// Reproduced inline (rather than linked) so the host test stays independent of
// the JNI layer while exercising the identical computation getHash10/12/22 ships.
// ---------------------------------------------------------------------------
static uint32_t compute_n22(const char* call)
{
    uint64_t n58 = 0;
    int i = 0;
    static const char* const T = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/";
    for (; call[i] != '\0' && i < 11; ++i)
    {
        char c = call[i];
        if (c >= 'a' && c <= 'z') c = (char)(c - 'a' + 'A');
        const char* p = strchr(T, c);
        int j = p ? (int)(p - T) : 0;
        n58 = 38 * n58 + (uint64_t)j;
    }
    for (; i < 11; ++i) n58 = 38 * n58;
    return (uint32_t)(((47055833459ull * n58) >> (64 - 22)) & 0x3FFFFFul);
}

// ---------------------------------------------------------------------------
// Independent inverse decode of a tone sequence, mirroring ft8_lib's decoder
// (decode.c / ldpc.c) but without audio synthesis or soft-decision LDPC.
// ---------------------------------------------------------------------------
static int is_costas_pos(int t)
{
    return (t >= 0 && t < 7) || (t >= 36 && t < 43) || (t >= 72 && t < 79);
}

// 79 tones -> 174-bit hard codeword (one byte per bit), via inverse Gray map.
// Returns 1 on success, 0 if any tone is out of range (> 7) or the bit count
// doesn't land on exactly FTX_LDPC_N. Validating the tone range keeps a corrupt
// golden table (or a regression that emits an out-of-range tone) from indexing
// inv_gray[] out of bounds — the test reports a clean failure instead of UB.
static int tones_to_codeword(const uint8_t tones[79], uint8_t cw[FTX_LDPC_N])
{
    uint8_t inv_gray[8];
    for (int b = 0; b < 8; ++b) inv_gray[kFT8_Gray_map[b]] = (uint8_t)b;
    int bit = 0;
    for (int t = 0; t < 79; ++t)
    {
        if (is_costas_pos(t)) continue;
        if (tones[t] > 7) return 0; // out-of-range tone: bail before OOB read
        uint8_t b3 = inv_gray[tones[t]];
        cw[bit++] = (b3 >> 2) & 1;
        cw[bit++] = (b3 >> 1) & 1;
        cw[bit++] = (b3 >> 0) & 1;
    }
    return bit == FTX_LDPC_N; // 58 data symbols * 3 bits == 174
}

// Count unsatisfied LDPC parity checks over a hard codeword. Verbatim port of
// the static ldpc_check() in ft8_lib/ft8/ldpc.c; 0 == valid codeword.
static int ldpc_check_hard(const uint8_t cw[FTX_LDPC_N])
{
    int errors = 0;
    for (int m = 0; m < FTX_LDPC_M; ++m)
    {
        uint8_t x = 0;
        for (int i = 0; i < kFTX_LDPC_Num_rows[m]; ++i)
            x ^= cw[kFTX_LDPC_Nm[m][i] - 1];
        if (x) ++errors;
    }
    return errors;
}

// Pack the first FTX_LDPC_K (91) hard bits, MSB-first, into a 12-byte a91.
static void pack_a91(const uint8_t cw[FTX_LDPC_N], uint8_t a91[12])
{
    memset(a91, 0, 12);
    for (int i = 0; i < FTX_LDPC_K; ++i)
        if (cw[i]) a91[i / 8] |= (uint8_t)(0x80u >> (i % 8));
}

// ---------------------------------------------------------------------------
// Assertions
// ---------------------------------------------------------------------------
static int g_fail = 0;
#define CHECK(cond, ...) do { if (!(cond)) { ++g_fail; printf("  FAIL: "); printf(__VA_ARGS__); printf("\n"); } } while (0)

// Compare two 77-bit payloads (bytes 0..8 full, byte 9 top 5 bits).
static int payload77_eq(const uint8_t a[10], const uint8_t b[10])
{
    for (int i = 0; i < 9; ++i) if (a[i] != b[i]) return 0;
    return ((a[9] & 0xF8) == (b[9] & 0xF8));
}

static void test_pack77_golden(void)
{
    printf("pack77 -> golden 77-bit payload:\n");
    for (int k = 0; k < N_MSG; ++k)
    {
        uint8_t got[10] = {0};
        int rc = pack77(kMsgs[k], got);
        CHECK(rc == 0, "[%s] pack77 returned %d", kMsgs[k], rc);
        CHECK(payload77_eq(got, kGoldenPayload[k]),
              "[%s] payload mismatch vs golden", kMsgs[k]);
    }
}

static void test_ft8_encode_golden_and_costas(void)
{
    printf("ft8_encode -> golden tones + Costas sync arrays:\n");
    for (int k = 0; k < N_MSG; ++k)
    {
        uint8_t tones[79];
        ft8_encode(kGoldenPayload[k], tones);
        int eq = (memcmp(tones, kGoldenTones[k], 79) == 0);
        CHECK(eq, "[%s] tone sequence mismatch vs golden", kMsgs[k]);

        // Costas sync arrays must sit at 0-6, 36-42, 72-78 (receiver sync).
        for (int s = 0; s < 7; ++s)
        {
            CHECK(tones[s] == kFT8_Costas_pattern[s] &&
                  tones[36 + s] == kFT8_Costas_pattern[s] &&
                  tones[72 + s] == kFT8_Costas_pattern[s],
                  "[%s] Costas symbol %d wrong", kMsgs[k], s);
        }
    }
}

// Layer (B): prove each golden tone set is a genuine FT8 frame for its payload,
// using the decoder's own LDPC + CRC validation — no encoder involved.
static void test_inverse_crosscheck(void)
{
    printf("inverse cross-check (LDPC parity + CRC, decoder-side):\n");
    for (int k = 0; k < N_MSG; ++k)
    {
        uint8_t cw[FTX_LDPC_N];
        if (!tones_to_codeword(kGoldenTones[k], cw))
        {
            CHECK(0, "[%s] tones->codeword failed (out-of-range tone)", kMsgs[k]);
            continue; // cw is undefined on failure; skip LDPC/CRC for this msg
        }

        CHECK(ldpc_check_hard(cw) == 0,
              "[%s] LDPC parity not satisfied", kMsgs[k]);

        uint8_t a91[12];
        pack_a91(cw, a91);
        uint16_t crc_extracted = ftx_extract_crc(a91);
        a91[9] &= 0xF8; // zero the CRC region exactly as decode.c does
        a91[10] = 0;
        uint16_t crc_calculated = ftx_compute_crc(a91, 96 - 14);
        CHECK(crc_extracted == crc_calculated,
              "[%s] CRC mismatch (extracted %04X != calculated %04X)",
              kMsgs[k], crc_extracted, crc_calculated);

        // recovered 77-bit payload must equal the golden payload
        CHECK(payload77_eq(a91, kGoldenPayload[k]),
              "[%s] recovered payload != golden payload", kMsgs[k]);
    }
}

static void test_hash_golden(void)
{
    printf("compute_n22 (getHash10/12/22) -> golden:\n");
    for (int k = 0; k < N_CALL; ++k)
    {
        uint32_t n22 = compute_n22(kCalls[k]);
        CHECK(n22 == kGoldenHash[k][0],
              "[%s] h22 %u != golden %u", kCalls[k], n22, kGoldenHash[k][0]);
        CHECK((n22 >> 10) == kGoldenHash[k][1],
              "[%s] h12 %u != golden %u", kCalls[k], n22 >> 10, kGoldenHash[k][1]);
        CHECK((n22 >> 12) == kGoldenHash[k][2],
              "[%s] h10 %u != golden %u", kCalls[k], n22 >> 12, kGoldenHash[k][2]);
    }
}

// Print paste-ready C literals computed live from the current ft8_lib, for the
// rare intentional spec/vector change. Run the test binary with `--emit`; copy
// the three blocks over the kGolden* tables above. Never hand-edit single bytes.
static int emit_goldens(void)
{
    printf("// kGoldenPayload:\n");
    uint8_t payloads[N_MSG][10];
    for (int k = 0; k < N_MSG; ++k)
    {
        uint8_t p[10] = {0};
        int rc = pack77(kMsgs[k], p);
        memcpy(payloads[k], p, 10);
        printf("    { ");
        for (int i = 0; i < 10; ++i) printf("0x%02X%s", p[i], i == 9 ? " }," : ",");
        printf(" // %s%s\n", kMsgs[k], rc == 0 ? "" : "  (pack77 FAILED!)");
    }
    printf("// kGoldenTones:\n");
    for (int k = 0; k < N_MSG; ++k)
    {
        uint8_t tones[79];
        ft8_encode(payloads[k], tones);
        printf("    {");
        for (int i = 0; i < 79; ++i) printf("%d%s", tones[i], i == 78 ? "" : ",");
        printf("}, // %s\n", kMsgs[k]);
    }
    printf("// kGoldenHash:\n");
    for (int k = 0; k < N_CALL; ++k)
    {
        uint32_t n = compute_n22(kCalls[k]);
        printf("    { %7uu, %4uu, %3uu }, // %s\n", n, n >> 10, n >> 12, kCalls[k]);
    }
    return 0;
}

int main(int argc, char** argv)
{
    if (argc > 1 && strcmp(argv[1], "--emit") == 0)
        return emit_goldens();

    test_pack77_golden();
    test_ft8_encode_golden_and_costas();
    test_inverse_crosscheck();
    test_hash_golden();

    if (g_fail == 0)
        printf("\nALL GOLDEN TESTS PASS (%d messages, %d callsigns)\n", N_MSG, N_CALL);
    else
        printf("\n%d GOLDEN CHECK(S) FAILED\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
