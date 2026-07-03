// ft8_call_hash.h — recover the raw callsign hash carried in a decoded frame.
//
// WHY THIS EXISTS  (issue #392: "Prefix RX doesn't work")
// ------------------------------------------------------------------
// A compound/nonstandard callsign (e.g. SV8/DM5HF) does not fit FT8's 28-bit
// standard-callsign field, so on the air it is sent as a short hash: a 12-bit
// hash in a Type-4 "nonstandard call" frame, or a 22-bit hash in a later
// standard frame once both stations know the mapping. The receiver resolves
// that hash back to the full call using a hash table it has accumulated.
//
// ft8_lib's decoder keeps its own per-decode hash table, but that table is only
// ever populated from calls heard *over the air* in full — it is never seeded
// with the operator's own call. So when someone answers SV8/DM5HF, the reply
// carries only the 12-bit hash of SV8/DM5HF, the decoder's table doesn't have
// it, and the call comes back as the placeholder "<...>". The JNI layer then
// zeroed the message's hash fields (see ft8_set_call_hashes: a "<...>" string
// has no computable hash), throwing away the one number — the hash itself —
// that the Java side (Ft8Message + MessageHashMap) *could* have resolved,
// because Java DOES seed its persistent hash list with the operator's own call
// (DatabaseOpr / ConfigFragment). Result: answers to a prefixed call show only
// "<...>" and no QSO is possible.
//
// The fix: when the decoder yields "<...>", pull the raw hash straight out of
// the frame payload and hand it to Java so the seeded MessageHashMap can look
// it up. These helpers do that extraction. They are pure functions of the
// 77-bit payload, mirroring the bit layout in ft8_lib's ftx_message_decode_std
// / ftx_message_decode_nonstd, and are unit-tested host-side in
// test_call_hash.c.

#ifndef FT8AF_FT8_CALL_HASH_H
#define FT8AF_FT8_CALL_HASH_H

#include <stdbool.h>
#include <stdint.h>

#include "ft8/message.h" // ftx_message_t

// pack28 layout constants (ft8_lib message.c): a 28-bit callsign field below
// NTOKENS is a directed token (CQ/DE/QRZ/…); the next MAX22 values are a
// 22-bit callsign hash; everything above that is a packed standard callsign.
#define FT8AF_NTOKENS 2063592u
#define FT8AF_MAX22 4194304u

// Standard message (i3 in {1,2}): if the callsign at position `which`
// (0 = call_to / n28a, 1 = call_de / n28b) was transmitted as a 22-bit hash,
// write that hash to *n22 and return true. Returns false when the field is a
// real callsign or a directed token (nothing to resolve). Mirrors the
// n28 - NTOKENS < MAX22 test in unpack28().
static inline bool ft8af_std_hash22(const ftx_message_t* msg, int which, uint32_t* n22)
{
    // n29a/n29b as extracted by ftx_message_decode_std; the 28-bit field is the
    // top 28 bits (n29 >> 1); the low bit is the /R /P suffix flag.
    uint32_t n29a = ((uint32_t)msg->payload[0] << 21) |
                    ((uint32_t)msg->payload[1] << 13) |
                    ((uint32_t)msg->payload[2] << 5) |
                    ((uint32_t)msg->payload[3] >> 3);
    uint32_t n29b = (((uint32_t)msg->payload[3] & 0x07u) << 26) |
                    ((uint32_t)msg->payload[4] << 18) |
                    ((uint32_t)msg->payload[5] << 10) |
                    ((uint32_t)msg->payload[6] << 2) |
                    ((uint32_t)msg->payload[7] >> 6);
    uint32_t n28 = (which == 0 ? n29a : n29b) >> 1;

    if (n28 < FT8AF_NTOKENS)
        return false; // directed token (CQ/DE/QRZ), not a hash
    n28 -= FT8AF_NTOKENS;
    if (n28 >= FT8AF_MAX22)
        return false; // packed standard callsign, not a hash
    if (n22 != NULL)
        *n22 = n28;
    return true;
}

// Non-standard message (Type 4, i3=4): the frame carries exactly one 12-bit
// hashed call (the other is sent plain in the 58-bit field). Write that hash to
// *n12 and set *is_call_to to which side it labels, then return true. Returns
// false for a "CQ <nonstd>" frame, which carries no hashed counterpart.
// Mirrors the n12 / iflip / icq extraction in ftx_message_decode_nonstd().
static inline bool ft8af_nonstd_hash12(const ftx_message_t* msg, uint32_t* n12, bool* is_call_to)
{
    uint16_t hash = (uint16_t)((msg->payload[0] << 4) | (msg->payload[1] >> 4));
    uint8_t iflip = (msg->payload[8] >> 1) & 0x01u;
    uint8_t icq = (msg->payload[9] >> 6) & 0x01u;

    if (icq)
        return false; // "CQ <call58>" — no hashed call to recover

    // iflip==0: call_de is plain (58-bit), call_to is the hash.
    // iflip==1: call_to is plain, call_de is the hash.
    if (n12 != NULL)
        *n12 = hash;
    if (is_call_to != NULL)
        *is_call_to = (iflip == 0);
    return true;
}

#endif // FT8AF_FT8_CALL_HASH_H
