// Cross-slot FT8 decoding — recall below the single-slot threshold by using
// the repetition structure of FT8 traffic. WSJT-X decodes every 15 s slot in
// isolation; stations, however, repeat the same message for many cycles
// (CQs and 73s especially), always on the same slot parity and frequency.
// This module keeps a small process-global cache across slots with two
// mechanisms, both operating on the fine-demod float LLRs (ft8_fine.h):
//
//  A. LLR accumulation. A candidate that fails to decode (but has genuine
//     sync quality) stores its 174 LLRs keyed by frequency and slot parity.
//     When the next same-parity slot produces a failed candidate at the
//     same frequency, the two LLR sets are summed — a coherent-information
//     combine worth ~3 dB — and the BP+OSD+CRC chain reruns on the sum.
//     This decodes stations that are below threshold in EVERY single slot.
//
//  B. Message-history retry. Every successful decode is remembered
//     (payload + frequency). A failed candidate at a remembered frequency
//     is tested against the remembered codeword by normalized soft
//     correlation of the LLRs; a strong match means the station repeated
//     its transmission but faded below decode threshold this cycle. The
//     CRC is vacuous here (the payload is known), so acceptance rests on
//     the correlation gate — see FT8AF_XSLOT_HIST_MIN in decode_params.h,
//     benchmark-swept against false accepts.
//
// Entries expire on their own (an accumulated LLR after ~2 same-parity
// slots, a history entry after ~2 minutes), so band changes self-heal;
// ft8_xslot_clear() empties everything immediately.
//
// State is process-global and NOT thread-safe by design: the app decodes
// one slot at a time on a single worker thread, and the bench is
// single-threaded. utc_ms timestamps drive slot parity ((utc/15000) % 2)
// and expiry; callers must pass consistent values within a session.
#ifndef FT8AF_FT8_XSLOT_H
#define FT8AF_FT8_XSLOT_H

#include <stdbool.h>
#include <stdint.h>

#include "ft8/constants.h"
#include "ft8/decode.h"
#include "ft8/message.h"

#ifdef __cplusplus
extern "C" {
#endif

// Record the fine-demod LLRs of a candidate that failed every decode
// attempt this slot. Replaces an existing same-parity entry within the
// frequency tolerance (the newest attempt supersedes older ones).
void ft8_xslot_store(float freq_hz, int64_t utc_ms, const float llrs[FTX_LDPC_N]);

// Mechanism A: combine `llrs` with a stored same-parity, same-frequency
// entry from an earlier slot and rerun BP+OSD+CRC on the sum. On success
// fills message/status (like ftx_decode_llrs) and consumes the entry.
bool ft8_xslot_try_accumulate(float freq_hz, int64_t utc_ms, const float llrs[FTX_LDPC_N],
                              int max_iterations, int osd_depth, int osd_err_gate,
                              ftx_message_t* message, decode_status_t* status);

// Remember a successful decode (payload + where it was heard) for
// mechanism B. Payloads follow ftx_message_t: 77 bits in 10 bytes.
void ft8_xslot_remember(const uint8_t payload[10], float freq_hz, int64_t utc_ms);

// Mechanism B: test `llrs` against remembered messages near freq_hz by
// normalized soft correlation. On a match above FT8AF_XSLOT_HIST_MIN,
// fills `message` with the remembered payload and returns true.
// correlation_out (optional) reports the winning score.
bool ft8_xslot_try_history(float freq_hz, int64_t utc_ms, const float llrs[FTX_LDPC_N],
                           ftx_message_t* message, float* correlation_out);

// Drop all cross-slot state (e.g. on band change).
void ft8_xslot_clear(void);

#ifdef __cplusplus
}
#endif

#endif // FT8AF_FT8_XSLOT_H
