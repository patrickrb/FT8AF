// Host unit tests for the EU_VHF (i3=0 n3=2) and CONTESTING (i3=0 n3=6) message
// decoders in ft8_lib/ft8/message.c — issue #403.
//
// Both decoders are dozens of lines of hand-rolled bit extraction (c28/p1/R1/r3/
// s12/g25 shifts, base-24/18/10 grid6 reconstruction) with early-return direct
// formatting, and shipped with no coverage: a wrong shift or mask would silently
// mis-render every EU-VHF / contesting contact.
//
// Method: assemble 77-bit payloads with an independent MSB-first bit writer that
// follows the documented field layout (a deliberately separate code path from the
// decoder's shift/mask arithmetic), take the c28 callsign encoding from the
// vendored packer itself (extracted out of a standard frame it produced, so the
// callsign bits are pack28's real output, not a re-implementation), and assert
// the fully-formatted decode. If any shift/mask in either decoder regresses, the
// rendered text changes and this fails.
//
// Build/run: added to run_host_tests.ps1 / run_host_tests.sh. Standalone:
//   clang -std=c11 -I ../ft8_lib -include host_compat.h test_contest_decode.c \
//       ../ft8_lib/ft8/{pack,encode,crc,constants,text,message}.c -o /tmp/t && /tmp/t

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>

#include "ft8/message.h"
#include "ft8/text.h"
#include "ft8/constants.h"

static int g_failures = 0;

#define CHECK(cond)                                                       \
    do {                                                                  \
        if (!(cond)) {                                                    \
            printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);        \
            ++g_failures;                                                 \
        }                                                                 \
    } while (0)

#define CHECK_STREQ(actual, expected)                                     \
    do {                                                                  \
        if (strcmp((actual), (expected)) != 0) {                          \
            printf("FAIL %s:%d  got [%s] want [%s]\n",                    \
                   __FILE__, __LINE__, (actual), (expected));             \
            ++g_failures;                                                 \
        }                                                                 \
    } while (0)

// ---------------------------------------------------------------------------
// MSB-first payload bit writer/reader. Bit index b lives in payload[b >> 3] at
// mask 0x80 >> (b & 7) — the same convention every decoder in message.c reads
// with, but implemented via a loop rather than per-field shift/mask soup, so
// the two sides are independent.
// ---------------------------------------------------------------------------
static void put_bits(uint8_t* payload, int start, int nbits, uint64_t value)
{
    for (int i = 0; i < nbits; ++i)
    {
        int b = start + i;
        uint64_t bit = (value >> (nbits - 1 - i)) & 1u;
        if (bit)
            payload[b >> 3] |= (uint8_t)(0x80u >> (b & 7));
        else
            payload[b >> 3] &= (uint8_t)~(0x80u >> (b & 7));
    }
}

static uint64_t get_bits(const uint8_t* payload, int start, int nbits)
{
    uint64_t v = 0;
    for (int i = 0; i < nbits; ++i)
    {
        int b = start + i;
        v = (v << 1) | ((payload[b >> 3] >> (7 - (b & 7))) & 1u);
    }
    return v;
}

// The vendored packer's own c28 encoding of a standard callsign: encode a
// standard (type 1) frame "CQ <call> <grid>" and pull c28b out of bits 29..56.
// Using pack28's real output (rather than re-implementing the callsign base
// conversion) makes these tests an encode/decode consistency check.
static uint32_t c28_of(const char* call)
{
    ftx_message_t msg;
    ftx_message_init(&msg);
    ftx_message_rc_t rc = ftx_message_encode_std(&msg, NULL, "CQ", call, "AA00");
    CHECK(rc == FTX_MESSAGE_RC_OK);
    return (uint32_t)get_bits(msg.payload, 29, 28);
}

// g25 for a 6-character grid, per the EU_VHF layout comment in message.c:
// successive bases 18, 10, 10, 24, 24 (letters A-R, digits, digits, letters A-X).
static uint32_t g25_of(const char* grid6)
{
    uint32_t g = (uint32_t)(grid6[0] - 'A');
    g = g * 18 + (uint32_t)(grid6[1] - 'A');
    g = g * 10 + (uint32_t)(grid6[2] - '0');
    g = g * 10 + (uint32_t)(grid6[3] - '0');
    g = g * 24 + (uint32_t)(grid6[4] - 'A');
    g = g * 24 + (uint32_t)(grid6[5] - 'A');
    return g;
}

// g15 for a 4-character grid, matching unpackgrid(): bases 18, 18, 10, 10.
static uint16_t g15_of(const char* grid4)
{
    uint16_t g = (uint16_t)(grid4[0] - 'A');
    g = (uint16_t)(g * 18 + (grid4[1] - 'A'));
    g = (uint16_t)(g * 10 + (grid4[2] - '0'));
    g = (uint16_t)(g * 10 + (grid4[3] - '0'));
    return g;
}

// ---------------------------------------------------------------------------
// EU_VHF: c28 (0-27) p1 (28) R1 (29) r3 (30-32) s12 (33-44) g25 (45-69),
// n3=2 (71-73), i3=0 (74-76). Rendered "call[/P] [R ]5<r3+2><serial:4> grid6".
// ---------------------------------------------------------------------------
static void build_eu_vhf(ftx_message_t* msg, const char* call, int p1, int R1,
                         int r3, int s12, const char* grid6)
{
    ftx_message_init(msg);
    put_bits(msg->payload, 0, 28, c28_of(call));
    put_bits(msg->payload, 28, 1, (uint64_t)p1);
    put_bits(msg->payload, 29, 1, (uint64_t)R1);
    put_bits(msg->payload, 30, 3, (uint64_t)r3);
    put_bits(msg->payload, 33, 12, (uint64_t)s12);
    put_bits(msg->payload, 45, 25, g25_of(grid6));
    put_bits(msg->payload, 71, 3, 2); // n3 = 2
    put_bits(msg->payload, 74, 3, 0); // i3 = 0
}

// ---------------------------------------------------------------------------
// CONTESTING: c28a (0-27) c28b (28-55) g15 (56-70), n3=6 (71-73), i3=0 (74-76).
// Rendered "call_to RR73; CQ call_de grid4".
// ---------------------------------------------------------------------------
static void build_contesting(ftx_message_t* msg, const char* call_to,
                             const char* call_de, const char* grid4)
{
    ftx_message_init(msg);
    put_bits(msg->payload, 0, 28, c28_of(call_to));
    put_bits(msg->payload, 28, 28, c28_of(call_de));
    put_bits(msg->payload, 56, 15, g15_of(grid4));
    put_bits(msg->payload, 71, 3, 6); // n3 = 6
    put_bits(msg->payload, 74, 3, 0); // i3 = 0
}

int main(void)
{
    // ---- sanity: the c28 extraction really is the packer's callsign ---------
    // Decode the standard frame we mine c28 from; if the bit positions were
    // wrong, everything downstream would be garbage in a confusing way.
    {
        ftx_message_t msg;
        ftx_message_init(&msg);
        CHECK(ftx_message_encode_std(&msg, NULL, "CQ", "G4ABC", "IO91")
              == FTX_MESSAGE_RC_OK);
        char text[64] = {0};
        CHECK(ftx_message_decode(&msg, NULL, text) == FTX_MESSAGE_RC_OK);
        CHECK_STREQ(text, "CQ G4ABC IO91");
    }

    // ---- type routing --------------------------------------------------------
    {
        ftx_message_t msg;
        build_eu_vhf(&msg, "G4ABC", 0, 0, 5, 1, "IO91AB");
        CHECK(ftx_message_get_type(&msg) == FTX_MESSAGE_TYPE_EU_VHF);
        CHECK(ftx_message_get_i3(&msg) == 0);
        CHECK(ftx_message_get_n3(&msg) == 2);

        build_contesting(&msg, "K1ABC", "W9XYZ", "FN42");
        CHECK(ftx_message_get_type(&msg) == FTX_MESSAGE_TYPE_CONTESTING);
        CHECK(ftx_message_get_i3(&msg) == 0);
        CHECK(ftx_message_get_n3(&msg) == 6);
    }

    // ---- EU_VHF: full exchange with /P and R ---------------------------------
    // r3=7 -> strength 9 -> RST "59"; serial 1234 -> "591234" (EU VHF sends
    // RST+serial as one 6-digit group).
    {
        ftx_message_t msg;
        build_eu_vhf(&msg, "G4ABC", 1, 1, 7, 1234, "JO62QL");
        char text[64] = {0};
        CHECK(ftx_message_decode(&msg, NULL, text) == FTX_MESSAGE_RC_OK);
        CHECK_STREQ(text, "G4ABC/P R 591234 JO62QL");
    }

    // ---- EU_VHF: plain call, no R, low report, zero-padded serial ------------
    // r3=0 -> strength 2 -> "52"; serial 7 -> "0007".
    {
        ftx_message_t msg;
        build_eu_vhf(&msg, "OK1XYZ", 0, 0, 0, 7, "JN79GG");
        char text[64] = {0};
        CHECK(ftx_message_decode(&msg, NULL, text) == FTX_MESSAGE_RC_OK);
        CHECK_STREQ(text, "OK1XYZ 520007 JN79GG");
    }

    // ---- EU_VHF: serial field boundaries --------------------------------------
    // s12 is 12 bits; 4095 must render in full, and the grid arithmetic must be
    // unaffected by all-ones neighbours (shift/mask bleed shows up here).
    {
        ftx_message_t msg;
        build_eu_vhf(&msg, "DL1AAA", 0, 1, 7, 4095, "JO31AA");
        char text[64] = {0};
        CHECK(ftx_message_decode(&msg, NULL, text) == FTX_MESSAGE_RC_OK);
        CHECK_STREQ(text, "DL1AAA R 594095 JO31AA");
    }

    // ---- EU_VHF: grid6 extremes ------------------------------------------------
    // AA00AA is g25 == 0 (all the base conversions bottom out); IR99XX pushes
    // every digit to its maximum legal value.
    {
        ftx_message_t msg;
        build_eu_vhf(&msg, "F1ABC", 0, 0, 3, 42, "AA00AA");
        char text[64] = {0};
        CHECK(ftx_message_decode(&msg, NULL, text) == FTX_MESSAGE_RC_OK);
        CHECK_STREQ(text, "F1ABC 550042 AA00AA");

        build_eu_vhf(&msg, "F1ABC", 0, 0, 3, 42, "IR99XX");
        text[0] = '\0';
        CHECK(ftx_message_decode(&msg, NULL, text) == FTX_MESSAGE_RC_OK);
        CHECK_STREQ(text, "F1ABC 550042 IR99XX");
    }

    // ---- CONTESTING: the Fox-style combo message ------------------------------
    {
        ftx_message_t msg;
        build_contesting(&msg, "K1ABC", "W9XYZ", "FN42");
        char text[64] = {0};
        CHECK(ftx_message_decode(&msg, NULL, text) == FTX_MESSAGE_RC_OK);
        CHECK_STREQ(text, "K1ABC RR73; CQ W9XYZ FN42");
    }

    // ---- CONTESTING: c28a/c28b field independence -----------------------------
    // Swap the calls: proves the two 28-bit fields don't bleed into each other
    // across the byte-3 nibble boundary (c28a ends and c28b starts mid-byte).
    {
        ftx_message_t msg;
        build_contesting(&msg, "W9XYZ", "K1ABC", "EM12");
        char text[64] = {0};
        CHECK(ftx_message_decode(&msg, NULL, text) == FTX_MESSAGE_RC_OK);
        CHECK_STREQ(text, "W9XYZ RR73; CQ K1ABC EM12");
    }

    // ---- CONTESTING: grid extremes --------------------------------------------
    {
        ftx_message_t msg;
        build_contesting(&msg, "JA1AAA", "VK9ZZZ", "AA00");
        char text[64] = {0};
        CHECK(ftx_message_decode(&msg, NULL, text) == FTX_MESSAGE_RC_OK);
        CHECK_STREQ(text, "JA1AAA RR73; CQ VK9ZZZ AA00");

        build_contesting(&msg, "JA1AAA", "VK9ZZZ", "RR99");
        text[0] = '\0';
        CHECK(ftx_message_decode(&msg, NULL, text) == FTX_MESSAGE_RC_OK);
        CHECK_STREQ(text, "JA1AAA RR73; CQ VK9ZZZ RR99");
    }

    if (g_failures == 0)
        printf("test_contest_decode: all checks passed\n");
    else
        printf("test_contest_decode: %d check(s) FAILED\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
