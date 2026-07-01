#ifndef _INCLUDE_OSD_H_
#define _INCLUDE_OSD_H_

#include <stdbool.h>
#include <stdint.h>

#include "constants.h"

#ifdef __cplusplus
extern "C"
{
#endif

/// Ordered-statistics decoder for the FT8/FT4 LDPC(174,91) code — the
/// backstop behind belief propagation. When bp_decode fails on a weak or
/// partly-corrupted candidate, OSD re-derives the 91 message bits from the
/// 91 most reliable *linearly independent* received bits (via Gauss-Jordan
/// over GF(2) on the generator matrix), then retries with single-bit flips
/// of the least reliable of those positions. Ported from ft8mon (Robert
/// Morris, AB1HL; the approach originates in WSJT-X's osd174_91.f90),
/// adapted to this library's conventions: kFTX_LDPC_generator from
/// constants.c, and log174[i] > 0 meaning bit i = 1.
///
/// A candidate is accepted only if it is a valid codeword whose CRC-14
/// checks AND whose re-encoding disagrees with the received hard decisions
/// in no more than an empirically-set number of positions (see OSD_NHARD_MAX
/// in osd.c) — the false-decode guard that a bare CRC cannot provide alone.
///
/// @param[in]  log174    Received log-likelihoods (log174[i] > 0 -> bit 1)
/// @param[in]  depth     Number of single-bit-flip retries over the least
///                       reliable basis positions (0 = base decode only)
/// @param[out] plain174  Full decoded codeword bits (systematic layout:
///                       91 message bits then 83 parity bits)
/// @param[out] out_depth Flip depth at which the winner was found (-1 base)
/// @return true if a CRC-valid, plausibility-checked codeword was found
bool osd_decode(const float log174[FTX_LDPC_N], int depth,
                uint8_t plain174[FTX_LDPC_N], int* out_depth);

#ifdef __cplusplus
}
#endif

#endif // _INCLUDE_OSD_H_
