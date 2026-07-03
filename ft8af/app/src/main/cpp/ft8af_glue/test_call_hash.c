// Host unit test for ft8_call_hash.h — the compound-callsign hash recovery
// behind issue #392 ("Prefix RX doesn't work").
//
// Proves that, given a real FT8 frame that carries a compound call as a short
// hash (12-bit in a Type-4 nonstandard frame, 22-bit in a standard frame), the
// pure extraction helpers recover exactly the WSJT-X callsign hash — the same
// number Java's FT8Package.getHash12/getHash22 compute and seed into
// MessageHashMap. That equality is the whole fix: it lets the seeded Java hash
// list resolve an answer to SV8/DM5HF that the decoder itself returns as
// "<...>".
//
// Method: encode a message with the vendored ft8_lib (the exact packer the app
// ships), then run the helper over the resulting payload and assert against an
// independent reference hash. If the packer's bit layout and the helper's bit
// layout ever diverge, this fails.
//
// Build/run: added to run_host_tests.ps1. Standalone:
//   clang -std=c11 -I ../ft8_lib -include host_compat.h test_call_hash.c \
//       ../ft8_lib/ft8/{pack,encode,crc,constants,text,message}.c -o /tmp/t && /tmp/t

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>

#include "ft8/message.h"
#include "ft8/text.h"
#include "ft8/constants.h"

#include "ft8_call_hash.h"

static int g_failures = 0;

#define CHECK(cond)                                                       \
    do {                                                                  \
        if (!(cond)) {                                                    \
            printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);        \
            ++g_failures;                                                 \
        }                                                                 \
    } while (0)

// Independent reference for the WSJT-X 22-bit callsign hash — a deliberate
// re-implementation (not a call into the helper under test) so the assertions
// pin the packer's output to a known value. Matches compute_n22() in
// ft8_hash_jni.cpp and save_callsign() in ft8_lib.
static uint32_t ref_n22(const char* call)
{
    uint64_t n58 = 0;
    int i = 0;
    for (; call[i] != '\0' && i < 11; ++i)
    {
        char c = call[i];
        if (c >= 'a' && c <= 'z')
            c = (char)(c - 'a' + 'A');
        int j = nchar(c, FT8_CHAR_TABLE_ALPHANUM_SPACE_SLASH);
        if (j < 0)
            j = 0;
        n58 = 38 * n58 + (uint64_t)j;
    }
    for (; i < 11; ++i)
        n58 = 38 * n58;
    return (uint32_t)(((47055833459ull * n58) >> (64 - 22)) & 0x3FFFFFul);
}

// A minimal callsign hash table so the encoder can save/resolve hashes exactly
// as the app does at runtime.
#define HT_SIZE 64
static struct { char call[12]; uint32_t n22; } g_ht[HT_SIZE];
static int g_ht_n = 0;

static void ht_save(const char* callsign, uint32_t n22)
{
    if (!callsign[0] || callsign[0] == '<')
        return;
    for (int i = 0; i < g_ht_n; ++i)
        if (g_ht[i].n22 == n22)
            return;
    if (g_ht_n < HT_SIZE)
    {
        strncpy(g_ht[g_ht_n].call, callsign, 11);
        g_ht[g_ht_n].call[11] = '\0';
        g_ht[g_ht_n].n22 = n22;
        ++g_ht_n;
    }
}

static bool ht_lookup(ftx_callsign_hash_type_e type, uint32_t hash, char* callsign)
{
    uint8_t shift = (type == FTX_CALLSIGN_HASH_10_BITS) ? 12
                  : (type == FTX_CALLSIGN_HASH_12_BITS) ? 10 : 0;
    for (int i = 0; i < g_ht_n; ++i)
        if ((g_ht[i].n22 >> shift) == hash)
        {
            strcpy(callsign, g_ht[i].call);
            return true;
        }
    callsign[0] = '\0';
    return false;
}

static ftx_callsign_hash_interface_t g_hash_if = { ht_lookup, ht_save };

int main(void)
{
    const char* COMPOUND = "SV8/DM5HF";

    // ---- Standard frame: "SV8/DM5HF K1ABC JN35" ----------------------------
    // A station answering the CQ with a grid uses a standard (type 1) frame;
    // the compound call_to is packed as a 22-bit hash.
    {
        ftx_message_t msg;
        ftx_message_rc_t rc =
            ftx_message_encode_std(&msg, &g_hash_if, COMPOUND, "K1ABC", "JN35");
        CHECK(rc == FTX_MESSAGE_RC_OK);

        uint32_t n22 = 0xDEADBEEF;
        CHECK(ft8af_std_hash22(&msg, 0, &n22));    // call_to is a hash
        CHECK(n22 == ref_n22(COMPOUND));

        // call_de (K1ABC) is a real standard call — not a hash.
        CHECK(!ft8af_std_hash22(&msg, 1, &n22));
    }

    // ---- Standard frame with a plain call_to: "CQ K1ABC FN42" --------------
    // Neither field is a hash; the helper must report false for both.
    {
        ftx_message_t msg;
        ftx_message_rc_t rc =
            ftx_message_encode_std(&msg, &g_hash_if, "CQ", "K1ABC", "FN42");
        CHECK(rc == FTX_MESSAGE_RC_OK);
        CHECK(!ft8af_std_hash22(&msg, 0, NULL));   // "CQ" token
        CHECK(!ft8af_std_hash22(&msg, 1, NULL));   // standard call
    }

    // ---- Nonstandard (type 4) frame: "SV8/DM5HF K1ABC RR73" ----------------
    // call_de (K1ABC) is sent plain in the 58-bit field; call_to (SV8/DM5HF)
    // is the 12-bit hash. iflip==0 → the hash labels call_to.
    {
        ftx_message_t msg;
        ftx_message_rc_t rc =
            ftx_message_encode_nonstd(&msg, &g_hash_if, COMPOUND, "K1ABC", "RR73");
        CHECK(rc == FTX_MESSAGE_RC_OK);

        uint32_t n12 = 0xDEADBEEF;
        bool is_to = false;
        CHECK(ft8af_nonstd_hash12(&msg, &n12, &is_to));
        CHECK(is_to);
        CHECK(n12 == (ref_n22(COMPOUND) >> 10));
    }

    // ---- Nonstandard "CQ SV8/DM5HF": no hashed counterpart -----------------
    {
        ftx_message_t msg;
        ftx_message_rc_t rc =
            ftx_message_encode_nonstd(&msg, &g_hash_if, "CQ", COMPOUND, "");
        CHECK(rc == FTX_MESSAGE_RC_OK);
        CHECK(!ft8af_nonstd_hash12(&msg, NULL, NULL));
    }

    // ---- WSJT-X interop golden vectors -------------------------------------
    // The 77-bit payloads below were produced by WSJT-X's own reference packer
    // (ft8code, WSJT-X 2.x) for the two frames a station sends when answering
    // CQ from SV8/DM5HF, with the compound call carried as a 22-bit hash:
    //   "<SV8/DM5HF> K1ABC JN35"  (hash in the call_to / n28a position)
    //   "K1ABC <SV8/DM5HF> R-10"  (hash in the call_de / n28b position)
    // WSJT-X's hash22calc reports SV8/DM5HF => 711279. Pinning WSJT-X's bytes
    // (not our own packer's) proves the helper interoperates with WSJT-X
    // specifically; a WAV of the first frame decoded through the app pipeline
    // resolves to "SV8/DM5HF K1ABC JN35".
    {
        struct { const char* bits; int pos; } cases[] = {
            { "00000010101001010111010101110000010011011110111100011010100100010001111111001", 0 },
            { "00001001101111011110001101010000000101010010101110101011101111111010101001001", 1 },
        };
        for (int i = 0; i < 2; ++i)
        {
            ftx_message_t msg;
            memset(msg.payload, 0, sizeof(msg.payload));
            for (int b = 0; cases[i].bits[b]; ++b)
                if (cases[i].bits[b] == '1')
                    msg.payload[b >> 3] |= (uint8_t)(0x80u >> (b & 7));

            uint32_t n22 = 0;
            CHECK(ft8af_std_hash22(&msg, cases[i].pos, &n22));
            CHECK(n22 == 711279u);              // == WSJT-X hash22calc
            CHECK(n22 == ref_n22("SV8/DM5HF")); // == the app's own hash
        }
    }

    if (g_failures == 0)
        printf("test_call_hash: all checks passed\n");
    else
        printf("test_call_hash: %d check(s) FAILED\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
