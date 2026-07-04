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

// Read the 28-bit callsign field starting at payload bit `start_bit`
// (MSB-first, the convention every decoder in ft8_lib's message.c uses).
static inline uint32_t ft8af_get_field28(const ftx_message_t* msg, int start_bit)
{
    uint32_t v = 0;
    for (int i = 0; i < 28; ++i)
    {
        int b = start_bit + i;
        v = (v << 1) | ((uint32_t)(msg->payload[b >> 3] >> (7 - (b & 7))) & 1u);
    }
    return v;
}

// Generalized recovery (issue #402): if the c28 callsign field at position
// `which` (0 = call_to, 1 = call_de) of ANY known message type was transmitted
// as a 22-bit hash, write that hash to *n22 and return true. Returns false when
// the message type carries no such field, the field is a directed token
// (CQ/DE/QRZ), or a packed standard callsign (mirrors the
// n28 - NTOKENS < MAX22 test in unpack28()).
//
// Field offsets per type, straight from the decoders in ft8_lib's message.c:
//   STANDARD (i3=1,2)     n29a bits 0-28, n29b bits 29-57; c28 = n29 >> 1,
//                         i.e. plain 28-bit reads at bits 0 and 29
//   ARRL_FD (0.3/0.4),
//   DXPEDITION (0.1),
//   CONTESTING (0.6)      c28a bits 0-27, c28b bits 28-55
//   EU_VHF (0.2)          a single c28 at bits 0-27 — the reporting station,
//                         surfaced as call_de (which == 1)
//   ARRL_RTTY (i3=3),
//   WWROF (i3=5)          t1 at bit 0, c28a bits 1-28, c28b bits 29-56
// New decoders that carry c28 fields only need a case here to get hash
// recovery — instead of re-implementing the #392 backstop per JNI branch.
static inline bool ft8af_call_hash22(const ftx_message_t* msg, int which, uint32_t* n22)
{
    int off;
    switch (ftx_message_get_type(msg))
    {
    case FTX_MESSAGE_TYPE_STANDARD:
        off = (which == 0) ? 0 : 29;
        break;
    case FTX_MESSAGE_TYPE_ARRL_FD:
    case FTX_MESSAGE_TYPE_DXPEDITION:
    case FTX_MESSAGE_TYPE_CONTESTING:
        off = (which == 0) ? 0 : 28;
        break;
    case FTX_MESSAGE_TYPE_EU_VHF:
        if (which != 1)
            return false; // only one call in the frame: the reporting station
        off = 0;
        break;
    case FTX_MESSAGE_TYPE_ARRL_RTTY:
    case FTX_MESSAGE_TYPE_WWROF:
        off = (which == 0) ? 1 : 29;
        break;
    default:
        return false; // free text / telemetry / type-4 (see ft8af_nonstd_hash12)
    }

    uint32_t n28 = ft8af_get_field28(msg, off);
    if (n28 < FT8AF_NTOKENS)
        return false; // directed token (CQ/DE/QRZ), not a hash
    n28 -= FT8AF_NTOKENS;
    if (n28 >= FT8AF_MAX22)
        return false; // packed standard callsign, not a hash
    if (n22 != NULL)
        *n22 = n28;
    return true;
}

// Standard message (i3 in {1,2}): if the callsign at position `which`
// (0 = call_to / n28a, 1 = call_de / n28b) was transmitted as a 22-bit hash,
// write that hash to *n22 and return true. Kept as the historical entry point;
// now a thin wrapper over the generalized type-keyed recovery above.
static inline bool ft8af_std_hash22(const ftx_message_t* msg, int which, uint32_t* n22)
{
    ftx_message_type_t type = ftx_message_get_type(msg);
    if (type != FTX_MESSAGE_TYPE_STANDARD)
        return false;
    return ft8af_call_hash22(msg, which, n22);
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
