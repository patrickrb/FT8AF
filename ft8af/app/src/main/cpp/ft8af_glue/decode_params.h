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
// DEPTH = single-bit-flip retries over the least reliable basis positions.
// Swept on the bench: 6 -> 78.6%, 12 -> 79.1%, 24 -> flat with more junk.
// ERR_GATE = max unsatisfied BP parity checks for which OSD is attempted
// (junk candidates typically fail 40-80 checks and are skipped).
#define FT8AF_OSD_DEPTH_DEEP 12
#define FT8AF_OSD_LDPC_ERR_GATE 24

#endif // FT8AF_DECODE_PARAMS_H
