// See ft8_subtract.h. Approach follows the published ft8_lib skimmer work
// (rx-888.com/web/design/digi.html): frequency-domain, per-symbol, replace
// the transmitted tone's magnitude with a local noise estimate. Compared to
// WSJT-X's time-domain waveform subtraction this is far cheaper (microseconds
// per signal, no re-FFT) and works within the 8-bit / 0.5 dB waterfall; the
// benchmark decides whether a time-domain pass is ever needed on top.

#include "ft8_subtract.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#ifdef SUBTRACT_TD_DEBUG
#include <stdio.h>
#endif

#include "ft8/constants.h"
#include "ft8/encode.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// From gfsk.c: per-sample GFSK phase increments for a tone sequence
// (malloc'd, caller frees) — the basis of the complex reference waveform.
float* synth_gfsk_dphi_alloc(const uint8_t* symbols, int n_sym, float f0,
                             float symbol_bt, float symbol_period,
                             int signal_rate, int* n_wave_out);

// Don't punch holes on marginal fits: only replace a bin that actually sits
// above the local noise estimate by at least this much (units of 0.5 dB).
#define SUBTRACT_MIN_EXCESS 2

// Moving-average window (samples at 12 kHz) for the complex-gain estimate in
// ft8_subtract_signal_time. Trade-off: wider tracks the gain with less noise
// but attenuates the estimate when the candidate grid's residual frequency
// error (up to ~±1.6 Hz at freq_osr=2) rotates the phase within the window;
// narrower follows that rotation but estimates the gain from fewer samples.
// Swept on the decode benchmark (corpus recall): 960 -> 92.2, 1200 -> 93.7,
// 1440 -> 94.5, 1680 -> 94.1, 1920 -> 94.3, 2880 -> 92.8, 3840 -> 91.5.
// WSJT-X's subtractft8 uses a comparable ~1500-sample window at 12 kHz.
// (Overridable from the compile line for sweeps.)
#ifndef SUBTRACT_TD_NFILT
#define SUBTRACT_TD_NFILT 1440
#endif

// Floor division for possibly-negative sub-unit indices (DT can be < 0).
static int floor_div(int a, int b)
{
    int q = a / b;
    if ((a % b != 0) && ((a < 0) != (b < 0)))
        --q;
    return q;
}

void ft8_subtract_signal(monitor_t* mon, const uint8_t* a91,
                         float freq_hz, float time_sec)
{
    if (!mon || !a91)
        return;
    waterfall_t* wf = &mon->wf;
    if (!wf->mag || wf->num_blocks <= 0 || wf->protocol != FTX_PROTOCOL_FT8)
        return;

    // Re-encode the 77-bit payload to the 79-tone sequence. a91 carries
    // payload+CRC; clear the 3 CRC bits sharing byte 9 (ft8_encode expects a
    // clean 10-byte payload and recomputes the CRC itself).
    uint8_t payload[10];
    memcpy(payload, a91, sizeof(payload));
    payload[9] &= 0xF8;
    uint8_t tones[FT8_NN];
    ft8_encode(payload, tones);

    const int fosr = wf->freq_osr;
    const int tosr = wf->time_osr;

    // Signal position in sub-bin units (1/(T*freq_osr) Hz each), relative to
    // min_bin: rel = freq_offset*fosr + freq_sub as computed by the decoder's
    // freq_hz formula, inverted and rounded to the nearest sub-bin.
    int rel = (int)lrintf(freq_hz * mon->symbol_period * fosr) - mon->min_bin * fosr;
    // Time position in sub-block units: time_offset*tosr + time_sub.
    int tunits = (int)lrintf(time_sec / mon->symbol_period * tosr);
    int time_offset = floor_div(tunits, tosr);

    for (int k = 0; k < FT8_NN; ++k)
    {
        int blk = time_offset + k;
        if (blk < 0 || blk >= wf->num_blocks)
            continue;
        // Tone-track position of THIS symbol in sub-bin units (tone spacing
        // = 1/T = fosr sub-bins).
        int sig = rel + tones[k] * fosr;

        uint8_t* block_base = wf->mag + (size_t)blk * wf->block_stride;
        for (int ts = 0; ts < tosr; ++ts)
        {
            for (int fs = 0; fs < fosr; ++fs)
            {
                uint8_t* row = block_base + (ts * fosr + fs) * wf->num_bins;

                // Local noise estimate: minimum magnitude over the 8 possible
                // tone bins at this symbol. At most one holds the signal; the
                // minimum is noise (or the floor under a co-channel signal —
                // conservative either way).
                int noise = 255;
                for (int t = 0; t < 8; ++t)
                {
                    int b = (int)lrintf((float)(rel + t * fosr - fs) / fosr);
                    if (b >= 0 && b < wf->num_bins && row[b] < noise)
                        noise = row[b];
                }

                // The transmitted tone lands at float bin (sig - fs)/fosr in
                // this freq plane: replace only the nearest bin. (Replacing
                // the off-center spill neighbor as well was measured to do
                // more collateral damage to coincident co-channel tone
                // tracks than it removes — see test_subtract.c.)
                int b = (int)lrintf((float)(sig - fs) / fosr);
                if (b >= 0 && b < wf->num_bins && row[b] > noise + SUBTRACT_MIN_EXCESS)
                    row[b] = (uint8_t)noise;
            }
        }
    }
}

// Fine time sync for the subtraction: sum of per-symbol tone-correlation
// energies |sum_n x[n] e^{-i 2 pi f_k n}|^2 with the correlation windows
// starting at `start`, restricted to symbols k in [k_min, k_max]. Phase-
// insensitive per symbol, so it peaks at the true signal start regardless of
// the (unknown) carrier phase and of small frequency error, and falls off as
// the windows straddle tone transitions. The caller must pass a symbol range
// that stays in-buffer for EVERY trial offset it compares: windows of pure
// noise add a positive floor, so trials that pull more windows into the
// buffer would otherwise always win (measured: dt pegs at the search edge
// for any signal extending past the capture window).
static double tone_coherence(const float* samples, int sample_rate,
                             const uint8_t* tones, float f0, int start,
                             int n_spsym, int k_min, int k_max)
{
    double metric = 0.0;
    for (int k = k_min; k <= k_max; ++k)
    {
        // FT8 tone spacing == symbol rate == sample_rate / n_spsym.
        double f = (double)f0 + tones[k] * ((double)sample_rate / n_spsym);
        double dph = -2.0 * M_PI * f / sample_rate;
        double wr = cos(dph), wi = sin(dph);
        double cr = 1.0, ci = 0.0;
        double sr = 0.0, si = 0.0;
        const float* base = samples + start + k * n_spsym;
        for (int n = 0; n < n_spsym; ++n)
        {
            double x = base[n];
            sr += x * cr;
            si += x * ci;
            double t = cr * wr - ci * wi;
            ci = cr * wi + ci * wr;
            cr = t;
        }
        metric += sr * sr + si * si;
    }
    return metric;
}

// One pass of reference synthesis + prefix sums of w(t) = samples * conj(ref).
// ref = e^{i phi(t)} is the unit-amplitude complex GFSK reference; the
// low-pass of w is the complex gain track camp(t) ~ (A/2) e^{i dtheta(t)}
// (the double-frequency image of the real input averages out over a window
// of >= 1 symbol). df_hz is added to the base frequency of the dphi track.
static void build_ref_prefix(const float* samples, int num_samples, int start,
                             const float* dphi, int n_wave, float df_rad,
                             float* ref_re, float* ref_im,
                             double* pre_re, double* pre_im)
{
    float phi = 0.0f;
    pre_re[0] = 0.0;
    pre_im[0] = 0.0;
    for (int k = 0; k < n_wave; ++k)
    {
        ref_re[k] = cosf(phi);
        ref_im[k] = sinf(phi);
        int idx = start + k;
        float x = (idx >= 0 && idx < num_samples) ? samples[idx] : 0.0f;
        pre_re[k + 1] = pre_re[k] + (double)(x * ref_re[k]);
        pre_im[k + 1] = pre_im[k] - (double)(x * ref_im[k]);
        phi = fmodf(phi + dphi[k] + df_rad, 2.0f * (float)M_PI);
    }
}

bool ft8_subtract_signal_time(const monitor_t* mon, float* samples,
                              int num_samples, int sample_rate,
                              const uint8_t* a91, float freq_hz, float time_sec)
{
    if (!mon || !samples || num_samples <= 0 || sample_rate <= 0 || !a91)
        return false;

    // Re-encode the 77-bit payload to the 79-tone sequence (same contract as
    // ft8_subtract_signal: clear the CRC bits sharing byte 9).
    uint8_t payload[10];
    memcpy(payload, a91, sizeof(payload));
    payload[9] &= 0xF8;
    uint8_t tones[FT8_NN];
    ft8_encode(payload, tones);

    int n_wave = 0;
    float* dphi = synth_gfsk_dphi_alloc(tones, FT8_NN, freq_hz, 2.0f,
                                        FT8_SYMBOL_PERIOD, sample_rate, &n_wave);
    if (!dphi)
        return false;
    const int n_spsym = n_wave / FT8_NN;

    // Candidate time -> signal start sample. The waterfall's time base lags
    // the raw stream: the STFT frame for block b / sub-block t is a Hann
    // window over the trailing nfft samples ending at b*block_size +
    // (t+1)*subblock_size, so a symbol starting at sample s0 peaks at
    // (b + t/time_osr)*block_size = s0 + block_size/2 + nfft/2 -
    // subblock_size (measured: exactly +0.2 s at 12 kHz / time_osr 4 /
    // freq_osr 2 — ten times the fine-sync search radius below).
    const int stft_lag = mon->block_size / 2 + mon->nfft / 2 - mon->subblock_size;

    // --- Fine time sync ------------------------------------------------------
    // The candidate grid is only good to ±half a time_osr step (±20 ms at
    // time_osr=4); a 20 ms reference misalignment randomizes the per-symbol
    // phase at every tone transition and caps suppression near -8 dB
    // (measured), leaving a residual that still masks co-channel signals.
    // Search ±24 ms in 4 ms steps for the tone-coherence peak, scored over
    // the symbols that stay in-buffer at every trial offset (see the note on
    // tone_coherence for why the range must be common across trials).
    const int start0 = (int)lrintf(time_sec * (float)sample_rate) - stft_lag;
    const int dt_step = sample_rate * 4 / 1000;
    const int dt_span = 6 * dt_step;
    int k_min = 0;
    while (k_min < FT8_NN && start0 - dt_span + k_min * n_spsym < 0)
        ++k_min;
    int k_max = FT8_NN - 1;
    while (k_max >= 0 && start0 + dt_span + (k_max + 1) * n_spsym > num_samples)
        --k_max;
    int start = start0;
    if (k_max - k_min >= FT8_NN / 4) // enough in-buffer symbols to sync on
    {
        double best_m = -1.0;
        for (int s = -6; s <= 6; ++s)
        {
            int st = start0 + s * dt_step;
            double m = tone_coherence(samples, sample_rate, tones,
                                      freq_hz, st, n_spsym, k_min, k_max);
            if (m > best_m)
            {
                best_m = m;
                start = st;
            }
        }
    }

    float* ref_re = (float*)malloc((size_t)n_wave * sizeof(float));
    float* ref_im = (float*)malloc((size_t)n_wave * sizeof(float));
    double* pre_re = (double*)malloc(((size_t)n_wave + 1) * sizeof(double));
    double* pre_im = (double*)malloc(((size_t)n_wave + 1) * sizeof(double));
    if (!ref_re || !ref_im || !pre_re || !pre_im)
    {
        free(dphi);
        free(ref_re);
        free(ref_im);
        free(pre_re);
        free(pre_im);
        return false;
    }

    build_ref_prefix(samples, num_samples, start, dphi, n_wave, 0.0f,
                     ref_re, ref_im, pre_re, pre_im);

    // --- Fine frequency sync -------------------------------------------------
    // Residual frequency error (up to ~±1.6 Hz off the freq_osr=2 grid)
    // shows up as a steady rotation of the gain track. Standard phase
    // discriminator: per-symbol phasors p_k from the prefix sums, then
    // df = arg(sum_k p_{k+1} conj(p_k)) / (2 pi T). Unambiguous to ±1/(2T)
    // = ±3.125 Hz, well beyond the grid error.
    double acc_re = 0.0, acc_im = 0.0;
    double prev_re = 0.0, prev_im = 0.0;
    bool have_prev = false;
    for (int k = 0; k < FT8_NN; ++k)
    {
        double p_re = pre_re[(k + 1) * n_spsym] - pre_re[k * n_spsym];
        double p_im = pre_im[(k + 1) * n_spsym] - pre_im[k * n_spsym];
        if (have_prev)
        {
            acc_re += p_re * prev_re + p_im * prev_im;
            acc_im += p_im * prev_re - p_re * prev_im;
        }
        prev_re = p_re;
        prev_im = p_im;
        have_prev = true;
    }
    float df_hz = 0.0f;
    if (acc_re != 0.0 || acc_im != 0.0)
        df_hz = (float)(atan2(acc_im, acc_re) / (2.0 * M_PI * FT8_SYMBOL_PERIOD));
    // A real decode's frequency error is bounded by the candidate grid
    // (±1.6 Hz) plus a little drift; a larger estimate means the
    // discriminator locked onto noise or a neighbor — distrust it.
    if (fabsf(df_hz) > 2.0f)
        df_hz = 0.0f;
    float df_rad = 2.0f * (float)M_PI * df_hz / (float)sample_rate;

    // Rebuild the reference at the refined frequency (skippable when the
    // discriminator found nothing meaningful).
    if (fabsf(df_hz) > 0.02f)
        build_ref_prefix(samples, num_samples, start, dphi, n_wave, df_rad,
                         ref_re, ref_im, pre_re, pre_im);
    free(dphi);

    // In-range portion of the reference: only samples the buffer actually
    // holds contribute to the gain average (out-of-range ones added zero
    // above and must not dilute the normalization at the buffer edges).
    const int lo = (start < 0) ? -start : 0;
    const int hi_excl = (start + n_wave > num_samples) ? num_samples - start : n_wave;
    const int half = SUBTRACT_TD_NFILT / 2;

    for (int k = lo; k < hi_excl; ++k)
    {
        int w_lo = k - half;
        int w_hi = k + half; // inclusive
        if (w_lo < lo)
            w_lo = lo;
        if (w_hi >= hi_excl)
            w_hi = hi_excl - 1;
        int cnt = w_hi - w_lo + 1;
        if (cnt <= 0)
            continue;
        float camp_re = (float)((pre_re[w_hi + 1] - pre_re[w_lo]) / cnt);
        float camp_im = (float)((pre_im[w_hi + 1] - pre_im[w_lo]) / cnt);
        // s_est(t) = 2*Re(camp * ref) reconstructs A cos(phi + dtheta).
        samples[start + k] -= 2.0f * (camp_re * ref_re[k] - camp_im * ref_im[k]);
    }

    free(ref_re);
    free(ref_im);
    free(pre_re);
    free(pre_im);

#ifdef SUBTRACT_TD_DEBUG
    if (k_max >= k_min)
    {
        double pre = tone_coherence(samples, sample_rate, tones,
                                    freq_hz + df_hz, start, n_spsym, k_min, k_max);
        printf("TD-SUB f=%7.1f t=%5.2f dt_fix=%+3d ms df=%+5.2f Hz post-coh %10.3g\n",
               freq_hz, time_sec, (start - start0) * 1000 / sample_rate, df_hz, pre);
    }
#endif
    return true;
}
