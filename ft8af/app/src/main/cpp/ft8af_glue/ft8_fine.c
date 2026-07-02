// See ft8_fine.h. Structure follows WSJT-X's ft8_downsample + ft8b fine sync
// (Franke/Taylor), reimplemented against this library's kiss FFT and monitor
// geometry.
//
// Signal path per candidate:
//   1. From the cached full-buffer spectrum, extract FINE_NFFT2 bins around
//      the candidate frequency and inverse-FFT them: a complex baseband
//      cd[] at FINE_RATE Hz where the candidate's tone 0 sits at
//      FINE_BASE_BIN * 6.25 Hz (32 samples per FT8 symbol, so an unwindowed
//      32-point DFT has bins exactly at the 8 tone frequencies).
//   2. Fine dt: the candidate grid is good to ±half a time_osr step; search
//      the Costas correlation power over ±8 baseband samples (±40 ms in
//      5 ms steps).
//   3. Fine df: quadratic-fit the Costas power over a ±2.1 Hz probe around
//      the grid frequency, then de-rotate cd by the estimate.
//   4. LLRs: per-symbol 32-point DFT, tone magnitudes to log domain,
//      max-log bit metrics (same Gray mapping as the waterfall path).
//      Symbols outside the capture or without signal give near-zero LLRs —
//      soft erasures the LDPC stage can work around.

#include "ft8_fine.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#ifdef FINE_DEBUG
#include <stdio.h>
#endif

#include "fft/kiss_fft.h"
#include "fft/kiss_fftr.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// Full-buffer FFT length at 12 kHz: >= 16 s so both the app's 13.5 s early
// window and a full 15 s slot fit zero-padded, and factors 2^9*3*5^3 keep
// kiss FFT fast. The downsample ratio maps it to a 3200-point complex
// baseband at 200 Hz covering the same 16 s.
#define FINE_NFFT1 192000
#define FINE_RATE 200
#define FINE_NFFT2 3200 // FINE_NFFT1 * FINE_RATE / 12000
#define FINE_SPS 32     // baseband samples per FT8 symbol (200 * 0.16)

// Tone 0 position in the baseband, in units of the 6.25 Hz tone spacing.
// >0 keeps the whole 8-tone stack away from DC; 2 puts it at 12.5 Hz.
#define FINE_BASE_BIN 2

// Fine-dt search half-range in baseband samples (5 ms each): ±60 ms covers
// the candidate grid error (±20 ms) and reaches the true peak even from a
// candidate one 40 ms grid step off (find_sync regularly emits those for
// the same signal).
#define FINE_DT_SPAN 12

// Fine-df probe offsets (Hz). The grid error is bounded by ±1.6 Hz
// (freq_osr 2); a coarse 3-point parabola over ±2.1 Hz interpolates the
// peak to well under the ~1 Hz level that matters for a 32-point DFT.
#define FINE_DF_PROBE 2.1f

struct ft8_fine_ctx
{
    kiss_fft_cpx* spectrum; // FINE_NFFT1/2 + 1 bins of the padded input
    kiss_fft_cfg ifft_cfg;  // FINE_NFFT2-point inverse complex FFT
    int sample_rate;
};

ft8_fine_ctx_t* ft8_fine_init(const float* samples, int num_samples, int sample_rate)
{
    if (!samples || num_samples <= 0 || num_samples > FINE_NFFT1 || sample_rate <= 0)
        return NULL;

    ft8_fine_ctx_t* ctx = (ft8_fine_ctx_t*)calloc(1, sizeof(ft8_fine_ctx_t));
    if (!ctx)
        return NULL;
    ctx->sample_rate = sample_rate;
    ctx->spectrum = (kiss_fft_cpx*)malloc((FINE_NFFT1 / 2 + 1) * sizeof(kiss_fft_cpx));
    ctx->ifft_cfg = kiss_fft_alloc(FINE_NFFT2, 1 /*inverse*/, NULL, NULL);

    float* padded = (float*)calloc(FINE_NFFT1, sizeof(float));
    kiss_fftr_cfg fwd = kiss_fftr_alloc(FINE_NFFT1, 0, NULL, NULL);
    if (!ctx->spectrum || !ctx->ifft_cfg || !padded || !fwd)
    {
        free(padded);
        free(fwd);
        ft8_fine_free(ctx);
        return NULL;
    }
    memcpy(padded, samples, (size_t)num_samples * sizeof(float));
    kiss_fftr(fwd, padded, ctx->spectrum);
    free(padded);
    free(fwd);
    return ctx;
}

void ft8_fine_free(ft8_fine_ctx_t* ctx)
{
    if (!ctx)
        return;
    free(ctx->spectrum);
    free(ctx->ifft_cfg);
    free(ctx);
}

// 32-point complex DFT of cd[n0..n0+31] at fractional bin b (frequency
// b * 6.25 Hz + df): returns |X|^2. Samples outside [0, FINE_NFFT2) count
// as zero (symbol partially or fully outside the capture).
static float symbol_bin_power(const kiss_fft_cpx* cd, int n0, float freq_hz, float df_hz)
{
    double sr = 0.0, si = 0.0;
    double dph = -2.0 * M_PI * (double)(freq_hz + df_hz) / FINE_RATE;
    double wr = cos(dph), wi = sin(dph);
    double cr = 1.0, ci = 0.0;
    for (int n = 0; n < FINE_SPS; ++n)
    {
        int idx = n0 + n;
        if (idx >= 0 && idx < FINE_NFFT2)
        {
            sr += cd[idx].r * cr - cd[idx].i * ci;
            si += cd[idx].r * ci + cd[idx].i * cr;
        }
        double t = cr * wr - ci * wi;
        ci = cr * wi + ci * wr;
        cr = t;
    }
    return (float)(sr * sr + si * si);
}

// Costas correlation power at a trial baseband start and frequency offset.
static float costas_power(const kiss_fft_cpx* cd, int start, float df_hz)
{
    float power = 0.0f;
    for (int m = 0; m < FT8_NUM_SYNC; ++m)
    {
        for (int k = 0; k < FT8_LENGTH_SYNC; ++k)
        {
            int sym = m * FT8_SYNC_OFFSET + k;
            float f = (FINE_BASE_BIN + kFT8_Costas_pattern[k]) * 6.25f;
            power += symbol_bin_power(cd, start + sym * FINE_SPS, f, df_hz);
        }
    }
    return power;
}

bool ft8_fine_demod(ft8_fine_ctx_t* ctx, const monitor_t* mon,
                    const candidate_t* cand, float llrs_out[FTX_LDPC_N],
                    float* freq_hz_out, float* time_sec_out, float* quality_out)
{
    if (!ctx || !mon || !cand || !llrs_out || mon->wf.protocol != FTX_PROTOCOL_FT8)
        return false;

    // Candidate geometry, exactly as the decoder derives it.
    float freq_hz = (mon->min_bin + cand->freq_offset +
                     (float)cand->freq_sub / mon->wf.freq_osr) / mon->symbol_period;
    float time_sec = (cand->time_offset +
                      (float)cand->time_sub / mon->wf.time_osr) * mon->symbol_period;

    // Candidate time base -> raw-sample signal start (same STFT lag as
    // ft8_subtract_signal_time; see the derivation there).
    const int stft_lag = mon->block_size / 2 + mon->nfft / 2 - mon->subblock_size;
    float start_sec = time_sec - (float)stft_lag / ctx->sample_rate;

    // Baseband extraction: FINE_NFFT2 spectrum bins starting FINE_BASE_BIN
    // tone-spacings below the candidate, inverse-FFT'd to cd[] at 200 Hz.
    const double df1 = (double)ctx->sample_rate / FINE_NFFT1; // spectrum bin width
    int k0 = (int)lrint(((double)freq_hz - FINE_BASE_BIN * 6.25) / df1);
    if (k0 < 0 || k0 + FINE_NFFT2 > FINE_NFFT1 / 2 + 1)
        return false; // candidate too close to the band edges

    static _Thread_local kiss_fft_cpx bins[FINE_NFFT2];
    static _Thread_local kiss_fft_cpx cd[FINE_NFFT2];
    memcpy(bins, ctx->spectrum + k0, sizeof(bins));
    // Taper the extraction edges to soften the brick-wall band cut.
    const int ntaper = 100;
    for (int i = 0; i < ntaper; ++i)
    {
        float w = (float)(0.5 * (1.0 - cos(M_PI * i / ntaper)));
        bins[i].r *= w;
        bins[i].i *= w;
        bins[FINE_NFFT2 - 1 - i].r *= w;
        bins[FINE_NFFT2 - 1 - i].i *= w;
    }
    kiss_fft(ctx->ifft_cfg, bins, cd);

    // Residual of the candidate frequency vs the spectrum grid, folded into
    // the df estimate below (|k0*df1 - (freq - base)| < df1/2 = 0.03 Hz —
    // negligible, but free to carry).
    float grid_df = (float)(((double)freq_hz - FINE_BASE_BIN * 6.25) - k0 * df1);

    // --- Fine dt ---------------------------------------------------------
    int start0 = (int)lrintf(start_sec * FINE_RATE);
    float trial_p[2 * FINE_DT_SPAN + 1];
    int best_s = 0;
    float best_p = -1.0f;
    for (int s = -FINE_DT_SPAN; s <= FINE_DT_SPAN; ++s)
    {
        float p = costas_power(cd, start0 + s, grid_df);
        trial_p[s + FINE_DT_SPAN] = p;
        if (p > best_p)
        {
            best_p = p;
            best_s = s;
        }
    }
    int best_start = start0 + best_s;
    // Sync quality: peak Costas power over the mean of the trials at least
    // 3 samples (15 ms) away from it. A real signal's aligned correlation
    // stands well above its off-peak floor; a junk candidate's trials are
    // all noise-flat (ratio ~1). Excluding the peak's shoulders keeps the
    // metric comparable between candidates centered on the signal and
    // candidates a grid step off (whose in-span trials are mostly floor).
    float rest_sum = 0.0f;
    int rest_n = 0;
    for (int s = -FINE_DT_SPAN; s <= FINE_DT_SPAN; ++s)
    {
        if (abs(s - best_s) < 3)
            continue;
        rest_sum += trial_p[s + FINE_DT_SPAN];
        rest_n++;
    }
    float rest = (rest_n > 0) ? rest_sum / rest_n : 0.0f;
    float quality = (rest > 0.0f) ? best_p / rest : 0.0f;
    // A peak pinned to the search edge is not localized: the candidate sits
    // too far from the signal (find_sync emits a better-centered duplicate
    // for it) or there is no peak at all. Zero the quality so gated callers
    // skip it rather than decode 40+ ms off and mis-report dt.
    if (best_s == -FINE_DT_SPAN || best_s == FINE_DT_SPAN)
        quality = 0.0f;

#ifdef FINE_DEBUG
    printf("FINE f=%.1f t=%.3f start0=%d best_s=%d q=%.2f trials:", freq_hz, time_sec, start0, best_s, quality);
    for (int s = -FINE_DT_SPAN; s <= FINE_DT_SPAN; ++s)
        printf(" %.2g", trial_p[s + FINE_DT_SPAN]);
    printf("\n");
#endif

    // --- Fine df ---------------------------------------------------------
    // Three-point parabola over {-probe, 0, +probe} around the grid
    // frequency; clamp to the probe range (a flat or inverted fit means
    // there is no usable peak — keep the grid value).
    float p_m = costas_power(cd, best_start, grid_df - FINE_DF_PROBE);
    float p_0 = best_p;
    float p_p = costas_power(cd, best_start, grid_df + FINE_DF_PROBE);
    float df = 0.0f;
    float denom = p_m - 2.0f * p_0 + p_p;
    if (denom < 0.0f)
    {
        df = 0.5f * (p_m - p_p) / denom * FINE_DF_PROBE;
        if (df > FINE_DF_PROBE)
            df = FINE_DF_PROBE;
        if (df < -FINE_DF_PROBE)
            df = -FINE_DF_PROBE;
    }
    df += grid_df;

    // --- Per-symbol LLRs ---------------------------------------------------
    // Log-domain tone magnitudes, max-log bit metrics with the same Gray
    // mapping as decode.c's ft8_extract_symbol. Dead or out-of-capture
    // symbols yield near-flat log magnitudes -> near-zero LLRs.
    for (int k = 0; k < FT8_ND; ++k)
    {
        int sym_idx = k + ((k < 29) ? 7 : 14); // skip Costas symbols
        int n0 = best_start + sym_idx * FINE_SPS;

        float ldb[8];
        for (int tone = 0; tone < 8; ++tone)
        {
            float f = (FINE_BASE_BIN + tone) * 6.25f;
            float p = symbol_bin_power(cd, n0, f, df);
            ldb[tone] = 0.5f * logf(p + 1e-30f); // log magnitude
        }

        float s2[8];
        for (int j = 0; j < 8; ++j)
            s2[j] = ldb[kFT8_Gray_map[j]];

        float* logl = llrs_out + 3 * k;
        float max1_0 = fmaxf(fmaxf(s2[4], s2[5]), fmaxf(s2[6], s2[7]));
        float max0_0 = fmaxf(fmaxf(s2[0], s2[1]), fmaxf(s2[2], s2[3]));
        float max1_1 = fmaxf(fmaxf(s2[2], s2[3]), fmaxf(s2[6], s2[7]));
        float max0_1 = fmaxf(fmaxf(s2[0], s2[1]), fmaxf(s2[4], s2[5]));
        float max1_2 = fmaxf(fmaxf(s2[1], s2[3]), fmaxf(s2[5], s2[7]));
        float max0_2 = fmaxf(fmaxf(s2[0], s2[2]), fmaxf(s2[4], s2[6]));
        logl[0] = max1_0 - max0_0;
        logl[1] = max1_1 - max0_1;
        logl[2] = max1_2 - max0_2;
    }

    // df started at grid_df (== the candidate frequency exactly); the fit
    // moved it by (df - grid_df) relative to the candidate.
    if (freq_hz_out)
        *freq_hz_out = freq_hz + (df - grid_df);
    if (time_sec_out)
        *time_sec_out = time_sec + (float)(best_start - start0) / FINE_RATE;
    if (quality_out)
        *quality_out = quality;
    return true;
}
