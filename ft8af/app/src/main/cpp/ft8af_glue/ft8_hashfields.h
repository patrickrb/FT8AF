// Received-hash bookkeeping for the decode JNI glue (issue #392).
//
// When ft8_lib decodes a message whose callsign field arrived as a hash the
// decoder has not heard in full yet, unpack writes the placeholder "<...>"
// and the received hash bits are otherwise dropped. The Java layer keeps its
// own persistent hash->callsign map (Ft8Message.hashList — seeded with the
// operator's own callsign and every callsign it decodes or loads from the
// log), and resolves "<...>" through Ft8Message's callTo/FromHash10/12/22
// fields. That only works if those fields carry the bits that actually
// arrived over the air; the glue used to zero them for "<...>", which is
// why replies to a nonstandard own call (e.g. SV8/DM5HF) never resolved.
//
// This module records the FAILED hash lookups of one ftx_message_decode_*
// call, in occurrence order, and hands the bits back out per "<...>" output
// field. Alignment argument: ft8_lib performs at most one hash lookup per
// callsign output, in output order (call_to is unpacked before call_de —
// see ftx_message_decode_std/_nonstd), and only a FAILED lookup produces a
// "<...>" output, so consuming recorded failures in order assigns each to
// the field it came from. Successful lookups return "<CALL>" and record
// nothing, keeping the two sequences in lockstep.
//
// Header-only and dependency-free so the host tests (run_host_tests.ps1)
// exercise the exact code the JNI files compile.
#ifndef FT8AF_FT8_HASHFIELDS_H
#define FT8AF_FT8_HASHFIELDS_H

#include <stdbool.h>
#include <stdint.h>
#include <string.h>

// A decoded message references at most two callsigns plus a DXpedition fox
// hash; 4 gives headroom without ever growing unbounded within one decode.
#define FT8AF_UNRESOLVED_MAX 4

typedef struct
{
    struct
    {
        int nbits; // 10, 12 or 22 — which hash width arrived over the air
        uint32_t bits;
    } entry[FT8AF_UNRESOLVED_MAX];
    int count; // recorded this decode call
    int next;  // consumed so far
} ft8af_unresolved_t;

static inline void ft8af_unresolved_reset(ft8af_unresolved_t* u)
{
    u->count = 0;
    u->next = 0;
}

static inline void ft8af_unresolved_record(ft8af_unresolved_t* u, int nbits, uint32_t bits)
{
    if (u->count < FT8AF_UNRESOLVED_MAX)
    {
        u->entry[u->count].nbits = nbits;
        u->entry[u->count].bits = bits;
        u->count++;
    }
}

// Derive the three Ft8Message hash-field values from one received hash.
// A longer hash determines every shorter one (h12 = n22 >> 10,
// h10 = n22 >> 12); the reverse is unknowable and left 0 — the Java map
// never stores a 0 key, so 0 reads as "unknown" there.
static inline void ft8af_derive_hash_fields(int nbits, uint32_t bits,
                                            uint32_t* h10, uint32_t* h12, uint32_t* h22)
{
    *h10 = *h12 = *h22 = 0;
    switch (nbits)
    {
    case 22:
        *h22 = bits & 0x3FFFFFu;
        *h12 = *h22 >> 10;
        *h10 = *h22 >> 12;
        break;
    case 12:
        *h12 = bits & 0xFFFu;
        *h10 = *h12 >> 2;
        break;
    case 10:
        *h10 = bits & 0x3FFu;
        break;
    default:
        break;
    }
}

// If `call` is the unresolved placeholder and a recorded failure remains,
// consume the next one and fill h10/h12/h22 from it; returns false (fields
// untouched) for any other callsign text, including resolved "<CALL>".
static inline bool ft8af_unresolved_consume(ft8af_unresolved_t* u, const char* call,
                                            uint32_t* h10, uint32_t* h12, uint32_t* h22)
{
    if (u == NULL || call == NULL || strcmp(call, "<...>") != 0 || u->next >= u->count)
        return false;
    ft8af_derive_hash_fields(u->entry[u->next].nbits, u->entry[u->next].bits, h10, h12, h22);
    u->next++;
    return true;
}

#endif // FT8AF_FT8_HASHFIELDS_H
