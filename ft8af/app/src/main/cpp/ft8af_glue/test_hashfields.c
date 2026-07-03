// Host unit tests for ft8_hashfields.h — propagating received hash bits for
// "<...>" callsigns up to Java (issue #392). Run by run_host_tests.ps1.
//
// Scenario under test: an operator with a nonstandard callsign (SV8/DM5HF)
// calls CQ; stations answer with the callsign compressed to a hash
// ("<SV8/DM5HF> DL1ABC R-10" over the air). The decoder has never heard the
// full call, so ft8_lib unpacks "<...>" — and used to drop the received hash
// bits, leaving Java's persistent hash map (seeded with the operator's own
// call) nothing to match against. These tests pin down:
//   1. ft8af_derive_hash_fields: a wider hash determines every narrower one,
//      never the reverse.
//   2. The record/consume ledger: failures consumed in order, placeholder
//      text only, bounded capacity.
//   3. End-to-end std message (h22 in a c28 field): encoding
//      "SV8/DM5HF DL1ABC R-10" and decoding it with an empty hash table
//      records exactly the h22 that Java's FT8Package.getHash22 computes
//      for "SV8/DM5HF".
//   4. End-to-end i3=4 message (h12 field): a hand-packed
//      "<SV8/DM5HF> DL1ABC RR73" resolves the same way through h12.
//   5. Lockstep: when the hash table DOES know the call, the lookup succeeds,
//      nothing is recorded, and consume stays silent.

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <ft8/constants.h>
#include <ft8/message.h>
#include <ft8/text.h>

#include "ft8_hashfields.h"

static int g_failures = 0;

static void check(bool ok, const char* what)
{
    printf("  %s %s\n", ok ? "ok:  " : "FAIL:", what);
    if (!ok)
        g_failures++;
}

// The WSJT-X 22-bit callsign hash — the same computation FT8Package.getHash22
// performs when Java seeds Ft8Message.hashList with the operator's callsign
// (ft8_hash_jni.cpp), and the value the tests must see arrive over the air.
static uint32_t compute_n22(const char* call)
{
    uint64_t n58 = 0;
    int i = 0;
    for (; call[i] != '\0' && i < 11; ++i)
    {
        int j = nchar(call[i], FT8_CHAR_TABLE_ALPHANUM_SPACE_SLASH);
        if (j < 0)
            j = 0;
        n58 = 38 * n58 + (uint64_t)j;
    }
    for (; i < 11; ++i)
        n58 = 38 * n58;
    return (uint32_t)(((47055833459ull * n58) >> (64 - 22)) & 0x3FFFFFul);
}

// ---------------------------------------------------------------------------
// Recording hash interface — mirrors the JNI decoders' callbacks: an
// optional single known callsign stands in for the per-decoder hash table,
// and every failed lookup lands in g_unresolved.
// ---------------------------------------------------------------------------
static ft8af_unresolved_t g_unresolved;
static char g_known_call[12];
static uint32_t g_known_n22;

static bool test_lookup(ftx_callsign_hash_type_e type, uint32_t hash, char* callsign)
{
    int nbits = (type == FTX_CALLSIGN_HASH_10_BITS) ? 10 : (type == FTX_CALLSIGN_HASH_12_BITS ? 12 : 22);
    if (g_known_call[0] != '\0' && (g_known_n22 >> (22 - nbits)) == hash)
    {
        strcpy(callsign, g_known_call);
        return true;
    }
    ft8af_unresolved_record(&g_unresolved, nbits, hash);
    callsign[0] = '\0';
    return false;
}

static void test_save(const char* callsign, uint32_t n22)
{
    (void)callsign;
    (void)n22;
}

static void test_derive_hash_fields(void)
{
    printf("derive_hash_fields:\n");
    uint32_t n22 = compute_n22("SV8/DM5HF");
    uint32_t h10, h12, h22;

    ft8af_derive_hash_fields(22, n22, &h10, &h12, &h22);
    check(h22 == n22, "h22 from a 22-bit hash is the hash itself");
    check(h12 == n22 >> 10, "h12 derived from a 22-bit hash");
    check(h10 == n22 >> 12, "h10 derived from a 22-bit hash");

    ft8af_derive_hash_fields(12, n22 >> 10, &h10, &h12, &h22);
    check(h12 == n22 >> 10, "h12 from a 12-bit hash is the hash itself");
    check(h10 == n22 >> 12, "h10 derived from a 12-bit hash matches n22 >> 12");
    check(h22 == 0, "h22 unknowable from a 12-bit hash");

    ft8af_derive_hash_fields(10, n22 >> 12, &h10, &h12, &h22);
    check(h10 == n22 >> 12, "h10 from a 10-bit hash is the hash itself");
    check(h12 == 0 && h22 == 0, "h12/h22 unknowable from a 10-bit hash");
}

static void test_record_consume(void)
{
    printf("record/consume ledger:\n");
    ft8af_unresolved_t u;
    uint32_t h10, h12, h22;

    ft8af_unresolved_reset(&u);
    check(!ft8af_unresolved_consume(&u, "<...>", &h10, &h12, &h22),
          "nothing to consume after reset");

    ft8af_unresolved_record(&u, 22, 0x155555u);
    ft8af_unresolved_record(&u, 12, 0xABCu);
    check(!ft8af_unresolved_consume(&u, "DL1ABC", &h10, &h12, &h22),
          "a full callsign consumes nothing");
    check(!ft8af_unresolved_consume(&u, "<SV8/DM5HF>", &h10, &h12, &h22),
          "a resolved bracketed callsign consumes nothing");
    check(ft8af_unresolved_consume(&u, "<...>", &h10, &h12, &h22) && h22 == 0x155555u,
          "first placeholder gets the first failed lookup");
    check(ft8af_unresolved_consume(&u, "<...>", &h10, &h12, &h22) && h12 == 0xABCu && h22 == 0,
          "second placeholder gets the second failed lookup");
    check(!ft8af_unresolved_consume(&u, "<...>", &h10, &h12, &h22),
          "no third entry to consume");

    ft8af_unresolved_reset(&u);
    for (int i = 0; i < FT8AF_UNRESOLVED_MAX + 3; ++i)
        ft8af_unresolved_record(&u, 22, (uint32_t)i);
    check(u.count == FT8AF_UNRESOLVED_MAX, "recording is bounded");
}

// The reply the reporter of issue #392 could not see: a standard (i3=1)
// message whose first c28 field carries SV8/DM5HF as a 22-bit hash.
static void test_std_message_h22(void)
{
    printf("std message with hashed call (h22):\n");

    ftx_message_t msg;
    ftx_message_init(&msg);
    check(ftx_message_encode(&msg, NULL, "SV8/DM5HF DL1ABC R-10") == FTX_MESSAGE_RC_OK,
          "message encodes");
    check(ftx_message_get_type(&msg) == FTX_MESSAGE_TYPE_STANDARD,
          "nonstandard call rides a standard message as a hash");

    ftx_callsign_hash_interface_t hash_if = { test_lookup, test_save };
    g_known_call[0] = '\0';
    ft8af_unresolved_reset(&g_unresolved);

    char call_to[20] = {0}, call_de[20] = {0}, extra[20] = {0};
    check(ftx_message_decode_std(&msg, &hash_if, call_to, call_de, extra) == FTX_MESSAGE_RC_OK,
          "message decodes");
    check(strcmp(call_to, "<...>") == 0, "unknown hash unpacks as the placeholder");
    check(strcmp(call_de, "DL1ABC") == 0, "full callsign unpacks normally");
    check(g_unresolved.count == 1 && g_unresolved.entry[0].nbits == 22,
          "exactly the failed h22 lookup was recorded");

    uint32_t h10 = 0, h12 = 0, h22 = 0;
    uint32_t n22 = compute_n22("SV8/DM5HF");
    check(ft8af_unresolved_consume(&g_unresolved, call_to, &h10, &h12, &h22),
          "placeholder call_to consumes the recorded lookup");
    check(h22 == n22 && h12 == (n22 >> 10) && h10 == (n22 >> 12),
          "received bits match the hash Java seeds for SV8/DM5HF");
    check(!ft8af_unresolved_consume(&g_unresolved, call_de, &h10, &h12, &h22),
          "full call_de consumes nothing");
}

// The i3=4 follow-up ("<SV8/DM5HF> DL1ABC RR73"): the nonstandard call is a
// 12-bit hash, the standard call rides the 58-bit field. Hand-packed with the
// bit layout of ftx_message_decode_nonstd so the h12 path is deterministic.
static void test_nonstd_message_h12(void)
{
    printf("i3=4 message with hashed call (h12):\n");

    uint32_t n22 = compute_n22("SV8/DM5HF");
    uint16_t n12 = (uint16_t)(n22 >> 10);
    uint64_t n58 = 0;
    for (const char* p = "DL1ABC"; *p != '\0'; ++p)
        n58 = n58 * 38 + (uint64_t)nchar(*p, FT8_CHAR_TABLE_ALPHANUM_SPACE_SLASH);

    ftx_message_t msg;
    ftx_message_init(&msg);
    const uint8_t iflip = 0; // hashed call is call_to
    const uint8_t nrpt = 2;  // RR73
    const uint8_t icq = 0;
    const uint8_t i3 = 4;
    msg.payload[0] = (uint8_t)(n12 >> 4);
    msg.payload[1] = (uint8_t)(((n12 & 0x0Fu) << 4) | ((n58 >> 54) & 0x0Fu));
    msg.payload[2] = (uint8_t)((n58 >> 46) & 0xFFu);
    msg.payload[3] = (uint8_t)((n58 >> 38) & 0xFFu);
    msg.payload[4] = (uint8_t)((n58 >> 30) & 0xFFu);
    msg.payload[5] = (uint8_t)((n58 >> 22) & 0xFFu);
    msg.payload[6] = (uint8_t)((n58 >> 14) & 0xFFu);
    msg.payload[7] = (uint8_t)((n58 >> 6) & 0xFFu);
    msg.payload[8] = (uint8_t)(((n58 & 0x3Fu) << 2) | (iflip << 1) | (nrpt >> 1));
    msg.payload[9] = (uint8_t)(((nrpt & 1u) << 7) | (icq << 6) | (i3 << 3));

    ftx_callsign_hash_interface_t hash_if = { test_lookup, test_save };
    g_known_call[0] = '\0';
    ft8af_unresolved_reset(&g_unresolved);

    char call_to[20] = {0}, call_de[20] = {0}, extra[20] = {0};
    check(ftx_message_decode_nonstd(&msg, &hash_if, call_to, call_de, extra) == FTX_MESSAGE_RC_OK,
          "message decodes");
    check(strcmp(call_to, "<...>") == 0, "unknown h12 unpacks as the placeholder");
    check(strcmp(call_de, "DL1ABC") == 0, "58-bit callsign unpacks normally");
    check(strcmp(extra, "RR73") == 0, "report decodes");

    uint32_t h10 = 0, h12 = 0, h22 = 0;
    check(ft8af_unresolved_consume(&g_unresolved, call_to, &h10, &h12, &h22),
          "placeholder call_to consumes the recorded lookup");
    check(h12 == n12 && h10 == (n22 >> 12) && h22 == 0,
          "received h12 matches the hash Java seeds for SV8/DM5HF");
}

// When the decoder heard the full call earlier in the slot, the lookup
// succeeds — nothing must be recorded and nothing consumed, keeping the
// failure ledger aligned with the "<...>" outputs.
static void test_resolved_lookup_records_nothing(void)
{
    printf("resolved lookup stays out of the ledger:\n");

    ftx_message_t msg;
    ftx_message_init(&msg);
    check(ftx_message_encode(&msg, NULL, "SV8/DM5HF DL1ABC R-10") == FTX_MESSAGE_RC_OK,
          "message encodes");

    ftx_callsign_hash_interface_t hash_if = { test_lookup, test_save };
    strcpy(g_known_call, "SV8/DM5HF");
    g_known_n22 = compute_n22("SV8/DM5HF");
    ft8af_unresolved_reset(&g_unresolved);

    char call_to[20] = {0}, call_de[20] = {0}, extra[20] = {0};
    check(ftx_message_decode_std(&msg, &hash_if, call_to, call_de, extra) == FTX_MESSAGE_RC_OK,
          "message decodes");
    check(strcmp(call_to, "<SV8/DM5HF>") == 0, "known hash resolves in-decoder");
    check(g_unresolved.count == 0, "successful lookup recorded nothing");

    uint32_t h10 = 0, h12 = 0, h22 = 0;
    check(!ft8af_unresolved_consume(&g_unresolved, call_to, &h10, &h12, &h22),
          "resolved call consumes nothing");
    g_known_call[0] = '\0';
}

int main(void)
{
    printf("ft8_hashfields tests (issue #392)\n");
    test_derive_hash_fields();
    test_record_consume();
    test_std_message_h22();
    test_nonstd_message_h12();
    test_resolved_lookup_records_nothing();

    if (g_failures == 0)
    {
        printf("ALL PASS\n");
        return 0;
    }
    printf("%d FAILURE(S)\n", g_failures);
    return 1;
}
