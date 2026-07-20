// Host unit tests for ftx_hash_store.h — the callsign hash table shared by the
// FT8 and FT4/FT2 decoders. Run by run_host_tests.sh / .ps1.
//
// Covered:
//   1. Save then resolve the same call by its 10-, 12- and 22-bit hash (the
//      three widths ft8_lib's decoder queries).
//   2. Idempotence: saving the same 22-bit hash twice keeps one entry.
//   3. Rejection of empty and "<...>" placeholder callsigns.
//   4. 11-character truncation with a guaranteed NUL terminator.
//   5. Lookup miss returns false and empties the output buffer.
//   6. REGRESSION (the reason this file exists): saving into a completely full
//      table must TERMINATE and drop the insert. The previous inline copies in
//      ft8_decode_jni.cpp / ft2_decode_jni.cpp probed with an unbounded
//      `while (slot occupied)` loop, so a saturated table spun forever and hung
//      the decode thread. This test fills all 256 slots and then saves one more
//      distinct call; it can only pass if the probe is bounded.

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "ftx_hash_store.h"

static int g_failures = 0;

static void check(bool ok, const char* what)
{
    printf("  %s %s\n", ok ? "ok:  " : "FAIL:", what);
    if (!ok)
        g_failures++;
}

int main(void)
{
    char out[16];

    // 1. Save then resolve by each hash width. A stored 22-bit hash answers a
    //    10-bit query on its top 10 bits, a 12-bit query on its top 12 bits, and
    //    a 22-bit query on all 22.
    {
        ftx_hash_store_t s;
        memset(&s, 0, sizeof(s));
        uint32_t n22 = 0x2ABCDEu & 0x3FFFFFu; // arbitrary 22-bit value
        ftx_hash_store_save(&s, "SV8/DM5HF", n22);
        check(s.count == 1, "one entry after a single save");

        check(ftx_hash_store_lookup(&s, FTX_CALLSIGN_HASH_22_BITS, n22, out) &&
                  strcmp(out, "SV8/DM5HF") == 0,
              "resolves by 22-bit hash");
        check(ftx_hash_store_lookup(&s, FTX_CALLSIGN_HASH_12_BITS, n22 >> 10, out) &&
                  strcmp(out, "SV8/DM5HF") == 0,
              "resolves by 12-bit hash");
        check(ftx_hash_store_lookup(&s, FTX_CALLSIGN_HASH_10_BITS, n22 >> 12, out) &&
                  strcmp(out, "SV8/DM5HF") == 0,
              "resolves by 10-bit hash");
    }

    // 2. Idempotence on the 22-bit key.
    {
        ftx_hash_store_t s;
        memset(&s, 0, sizeof(s));
        ftx_hash_store_save(&s, "K1ABC", 0x1234u);
        ftx_hash_store_save(&s, "K1ABC", 0x1234u);
        check(s.count == 1, "repeated save of the same hash is idempotent");
    }

    // 3. Empty and placeholder callsigns are skipped.
    {
        ftx_hash_store_t s;
        memset(&s, 0, sizeof(s));
        ftx_hash_store_save(&s, "", 0x55u);
        ftx_hash_store_save(&s, "<...>", 0x56u);
        check(s.count == 0, "empty and <...> callsigns are not stored");
    }

    // 4. Callsigns longer than 11 chars are truncated and NUL-terminated.
    {
        ftx_hash_store_t s;
        memset(&s, 0, sizeof(s));
        ftx_hash_store_save(&s, "ABCDEFGHIJKLMNOP", 0x77u); // 16 chars
        check(ftx_hash_store_lookup(&s, FTX_CALLSIGN_HASH_22_BITS, 0x77u, out),
              "long callsign is stored");
        check(strlen(out) == 11 && strcmp(out, "ABCDEFGHIJK") == 0,
              "long callsign truncated to 11 chars");
    }

    // 5. Miss on an empty table.
    {
        ftx_hash_store_t s;
        memset(&s, 0, sizeof(s));
        strcpy(out, "DIRTY");
        check(!ftx_hash_store_lookup(&s, FTX_CALLSIGN_HASH_22_BITS, 0x99u, out),
              "lookup miss returns false");
        check(out[0] == '\0', "lookup miss empties the output buffer");
    }

    // 6. REGRESSION: a full table must not hang, and drops further inserts.
    {
        ftx_hash_store_t s;
        memset(&s, 0, sizeof(s));
        char call[8];
        for (int i = 0; i < FTX_HASH_STORE_SIZE; ++i)
        {
            snprintf(call, sizeof(call), "C%d", i);
            ftx_hash_store_save(&s, call, (uint32_t)(i + 1)); // distinct hashes
        }
        check(s.count == FTX_HASH_STORE_SIZE, "table fills to capacity");

        // The following save, with a distinct hash absent from a now-full table,
        // is exactly the case that looped forever before the probe was bounded.
        ftx_hash_store_save(&s, "OVERFLOW", 0xFFFFFu);
        check(s.count == FTX_HASH_STORE_SIZE, "insert into a full table is dropped");
        check(!ftx_hash_store_lookup(&s, FTX_CALLSIGN_HASH_22_BITS, 0xFFFFFu, out),
              "the dropped call is not resolvable");
        // Entries stored before saturation remain intact.
        check(ftx_hash_store_lookup(&s, FTX_CALLSIGN_HASH_22_BITS, 1u, out) &&
                  strcmp(out, "C0") == 0,
              "pre-saturation entries survive the dropped insert");
    }

    if (g_failures == 0)
    {
        printf("all hash-store tests passed\n");
        return 0;
    }
    printf("%d hash-store test(s) FAILED\n", g_failures);
    return 1;
}
