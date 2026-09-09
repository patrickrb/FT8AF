// ftx_hash_store.h — the per-decoder callsign hash table shared by the FT8 and
// FT4/FT2 decoders.
//
// WHY THIS EXISTS
// ------------------------------------------------------------------
// ft8_lib's decoder resolves compound/nonstandard callsigns (sent on the air as
// a 10-/12-/22-bit hash) back to their full text using a small hash table that
// it accumulates from calls heard in full. Both ft8_decode_jni.cpp and
// ft2_decode_jni.cpp kept a byte-for-byte identical copy of that table plus its
// save/lookup routines (a fixed-size open-addressing table with linear probing).
//
// Two copies meant the same latent defect lived in both: the save routine's
// linear probe had no loop bound, so once the table filled with no matching
// entry it would probe forever — an infinite loop that hangs the decode thread
// (an ANR on Android). It cannot happen in a normal slot (a fresh, zeroed table
// holds far more calls than one slot decodes), but nothing guaranteed
// termination, so a pathological or adversarial slot could wedge the decoder.
//
// This header collapses the two copies into one bounded, host-tested
// implementation (test_hash_store.c). The behaviour is identical to the old
// inline copies for every non-saturated table; the only change is that a full
// table now drops the insert instead of spinning — mirroring how lookup already
// degrades to a graceful miss.

#ifndef FT8AF_FTX_HASH_STORE_H
#define FT8AF_FTX_HASH_STORE_H

#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include "ft8/message.h" // ftx_callsign_hash_type_e

// Open-addressing table capacity. 256 slots comfortably exceed the distinct
// hashed callsigns any single decode slot produces.
#define FTX_HASH_STORE_SIZE 256

typedef struct
{
    char callsign[12];
    uint32_t hash; // 22-bit
} ftx_hash_entry_t;

typedef struct
{
    ftx_hash_entry_t entries[FTX_HASH_STORE_SIZE];
    int count;
} ftx_hash_store_t;

// Insert callsign->hash into the table (idempotent on the 22-bit hash). Skips
// empty and placeholder ("<...>") callsigns, exactly as the decoders require.
//
// The linear probe is bounded to at most one full pass: on a saturated table
// with no matching entry the insert is dropped rather than looping forever.
static inline void ftx_hash_store_save(ftx_hash_store_t* store, const char* callsign, uint32_t n22)
{
    if (callsign[0] == '\0' || callsign[0] == '<')
        return;
    uint16_t h10 = (n22 >> 12) & 0x3FF;
    int idx = (h10 * 23) % FTX_HASH_STORE_SIZE;
    // Stop after one full pass so a full table degrades to a dropped insert.
    for (int probes = 0; probes < FTX_HASH_STORE_SIZE; ++probes)
    {
        if (store->entries[idx].callsign[0] == '\0')
            break; // found a free slot
        if (store->entries[idx].hash == n22)
            return; // already stored
        idx = (idx + 1) % FTX_HASH_STORE_SIZE;
    }
    if (store->entries[idx].callsign[0] != '\0')
        return; // table full — drop rather than overwrite a live entry
    strncpy(store->entries[idx].callsign, callsign, 11);
    store->entries[idx].callsign[11] = '\0';
    store->entries[idx].hash = n22;
    store->count++;
}

// Resolve a 10-/12-/22-bit hash back to a stored callsign. Writes the callsign
// into `out` (which must hold at least 12 bytes) and returns true on a hit; on a
// miss writes an empty string and returns false.
static inline bool ftx_hash_store_lookup(const ftx_hash_store_t* store,
                                         ftx_callsign_hash_type_e type, uint32_t hash, char* out)
{
    uint8_t shift = (type == FTX_CALLSIGN_HASH_10_BITS) ? 12 : (type == FTX_CALLSIGN_HASH_12_BITS ? 10 : 0);
    for (int i = 0; i < FTX_HASH_STORE_SIZE; ++i)
    {
        if (store->entries[i].callsign[0] == '\0')
            continue;
        if (((store->entries[i].hash & 0x3FFFFFu) >> shift) == hash)
        {
            strcpy(out, store->entries[i].callsign);
            return true;
        }
    }
    out[0] = '\0';
    return false;
}

#endif // FT8AF_FTX_HASH_STORE_H
