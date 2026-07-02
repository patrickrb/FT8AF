// FT8 decoder tuning parameters, shared by the Android JNI glue
// (ft8_decode_jni.cpp) and the host decode benchmark (decode_bench.c).
//
// KEEP IN SYNC (manually): desktop/src-tauri/src/dsp/decoder.rs duplicates
// these values as Rust consts — it cannot include a C header.
//
// Values are benchmark-driven: see decode_bench.c and testdata/ft8/floors.txt.
// Measured on the committed corpus (recall vs WSJT-X jt9, 13.5 s window):
//   stock (140/10/osr2)            71.5%
//   TIME_OSR 2 -> 4                73.6%   <- the meaningful gain
//   + candidates 256, min_score 5  73.8%
//   FREQ_OSR 2 -> 4                57.3%   <- REGRESSION, never raise this
#ifndef FT8AF_DECODE_PARAMS_H
#define FT8AF_DECODE_PARAMS_H

// Sync-candidate heap size. 140 doesn't bind on the benchmark corpus, but a
// crowded band with time_osr=4 produces more distinct candidates; the cost of
// a larger heap is a few KB and bounded decode attempts.
#define FT8AF_MAX_CANDIDATES 256

// Costas sync-score threshold for accepting a candidate. The deep passes use
// a lower threshold to pursue marginal candidates the fast pass skips (the
// LDPC+CRC gate downstream rejects junk; the candidate cap bounds the cost).
#define FT8AF_MIN_SCORE_FAST 10
#define FT8AF_MIN_SCORE_DEEP 5

// STFT oversampling. time_osr=4 halves the worst-case symbol-timing error
// (the largest single decode-rate gain measured). freq_osr must stay 2: 4
// doubles the FFT length, smearing symbols in time and REGRESSING recall.
#define FT8AF_TIME_OSR 4
#define FT8AF_FREQ_OSR 2

// LDPC belief-propagation iteration caps. Measured: raising the deep cap to
// 60 gains nothing (BP has converged or never will; that's what OSD is for).
#define FT8AF_LDPC_ITERS_FAST 20
#define FT8AF_LDPC_ITERS_DEEP 30

// Ordered-statistics backstop (ft8_lib/ft8/osd.c), deep passes only.
// DEPTH = flip window over the least reliable basis positions: `depth`
// single-bit retries plus C(depth,2) pair retries (order-2; pairs use a
// stricter acceptance gate, see OSD_NHARD_MAX_ORDER2 in osd.c). Swept on
// the bench: order-1 6 -> 78.6%, 12 -> 79.1%, 24 -> flat with more junk;
// with order-2 pairs (post time-domain subtraction) depth 12 -> 94.9%,
// 16..32 -> matched flat while false decodes climb. Keep 12.
// ERR_GATE = max unsatisfied BP parity checks for which OSD is attempted
// (junk candidates typically fail 40-80 checks and are skipped).
#define FT8AF_OSD_DEPTH_DEEP 12
#define FT8AF_OSD_LDPC_ERR_GATE 24

// Fine-demod retry (ft8af_glue/ft8_fine.c), deep passes only: candidates the
// waterfall LLRs can't decode are re-demodulated from the raw samples with
// Costas fine dt/df sync. SYNC_MIN gates the retry on the fine sync quality
// (aligned Costas power over misaligned trials) so noise candidates don't
// get a second draw against the CRC+nhard gates. Swept: 1.0 -> 460 matched /
// 65 extras, 1.2 -> 460/64, 1.4 -> 459/62, 1.7 -> 455/58, 2.0 -> 449/52 —
// real decodes fall off fast above 1.2.
// RARE_MIN applies instead when the decoded message is NOT a standard or
// non-standard-call message: fabricated decodes land disproportionately in
// the free-text/contest types (random 77-bit payloads), while genuine
// traffic in those types is rare and strong. Junk observed at the base
// gate ("1EQ8YXYQDW2 .", "W99WMQ JX3GAH 539 4274") sails through quality
// ~1.2-2 on a busy band; genuine rare-type decodes clear 2.5 easily.
#ifndef FT8AF_FINE_SYNC_MIN
#define FT8AF_FINE_SYNC_MIN 1.2f
#endif
#ifndef FT8AF_FINE_SYNC_MIN_RARE
#define FT8AF_FINE_SYNC_MIN_RARE 2.5f
#endif

#endif // FT8AF_DECODE_PARAMS_H
