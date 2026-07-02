// Fine-sync coherent FT8 demodulator — a second-chance LLR source for
// candidates the waterfall path can't decode.
//
// The standard path derives bit likelihoods from the monitor's 8-bit
// log-magnitude waterfall at the candidate grid's alignment (±20 ms /
// ±1.6 Hz at time_osr 4 / freq_osr 2). This module re-demodulates a
// candidate from the RAW float samples the way WSJT-X's ft8b does: extract
// a ~200 Hz complex baseband around the candidate (one full-buffer FFT per
// context, one small inverse FFT per candidate), fine-align dt and df on
// the Costas sync symbols, then take per-symbol 32-point DFTs to produce
// float log-domain LLRs. Compared to the waterfall LLRs this restores the
// alignment loss, adds frequency-error correction the grid can't express,
// and — because symbols with no signal produce near-flat spectra — hands
// the LDPC stage soft erasures instead of confidently-wrong bits (the
// failure mode of partially-transmitted signals, e.g. a station keying up
// seconds late).
//
// Shared by the Android JNI glue (ft8_decode_jni.cpp) and the host decode
// benchmark (decode_bench.c). FT8-only.
#ifndef FT8AF_FT8_FINE_H
#define FT8AF_FT8_FINE_H

#include <stdbool.h>

#include "common/monitor.h"
#include "ft8/constants.h"
#include "ft8/decode.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ft8_fine_ctx ft8_fine_ctx_t;

// Build a demod context from one slot of raw audio (mono float, 12 kHz;
// up to 16 s). Performs the full-buffer forward FFT once. Returns NULL on
// allocation failure or unsupported input. The samples are not retained.
ft8_fine_ctx_t* ft8_fine_init(const float* samples, int num_samples, int sample_rate);

void ft8_fine_free(ft8_fine_ctx_t* ctx);

// Re-demodulate `cand` (a candidate of `mon`, FT8 protocol) from the raw
// samples: baseband extraction, Costas fine dt/df sync, per-symbol DFT ->
// 174 log-domain LLRs in llrs_out. Optionally reports the refined signal
// frequency and start time (candidate time base, i.e. directly comparable
// to the values the decoder derives from the candidate) and the sync
// quality — the aligned Costas correlation power over the mean of the
// misaligned trials (~1 for junk candidates, rising with real signal
// strength; callers gate decode attempts on it to keep noise candidates
// from re-rolling the CRC lottery). Pass NULL for outputs not needed.
// Returns false when the candidate geometry cannot be demodulated (out of
// band / degenerate input).
bool ft8_fine_demod(ft8_fine_ctx_t* ctx, const monitor_t* mon,
                    const candidate_t* cand, float llrs_out[FTX_LDPC_N],
                    float* freq_hz_out, float* time_sec_out, float* quality_out);

#ifdef __cplusplus
}
#endif

#endif // FT8AF_FT8_FINE_H
