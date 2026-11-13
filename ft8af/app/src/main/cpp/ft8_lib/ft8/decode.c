#include "decode.h"
#include "constants.h"
#include "crc.h"
#include "ldpc.h"
#include "osd.h"
#include "unpack.h"

#include <stdbool.h>
#include <stddef.h> // NULL (not guaranteed by the headers above on glibc/macOS)
#include <math.h>

// #define LOG_LEVEL LOG_DEBUG
// #include "debug.h"

// dB subtracted from the raw FT4/FT2 SNR estimate to align its 4-GFSK magnitude scale with
// FT8's reported range. Empirical; see the note in ft8_snr(). Tune here if FT4 reads high/low.
#define FT4_SNR_CAL_DB 20

// FT8 SNR (issue #937). ft8_snr() compares the candidate's Costas sync tones with the
// off-signal spectral baseline around it, the way WSJT-X does, and reports the result in
// WSJT-X's 2500 Hz reference bandwidth.
//
// The noise is a low percentile of the time-averaged linear power in the
// FT8_SNR_NOISE_HALF_BINS bins (6.25 Hz each) on each side of the candidate's 8 tones. The
// percentile skips the bins other signals occupy, so a busy band doesn't raise the floor.
// Each side is measured separately and the louder one wins: when a rig's filter edge falls
// inside the window, the rolled-off side reads low and is ignored. The previous estimate
// used the other 7 Costas tones of the same symbol as noise. With our 2-symbol FFT window
// and time_osr=4, neighbouring symbols and leakage put signal energy in those tones, so
// the noise rose with the signal and every report was squeezed into about -14..-2 dB.
#define FT8_SNR_NOISE_HALF_BINS 48
#define FT8_SNR_NOISE_PERCENTILE 0.1f
// A side with fewer bins than this (at the edge of the waterfall) is not used.
#define FT8_SNR_NOISE_MIN_BINS 8

// Per-bin to 2500 Hz calibration, fitted against the testdata/ft8 jt9 truth (decode_bench
// --assert-snr). Includes the 10*log10(2500/6.25) = 26 dB bandwidth term plus the bias of
// the Hann-window power estimate. Re-fit by sweeping -DFT8_SNR_CAL_DB=<x>f through
// decode_bench (it reports mae and bias against jt9) if the waterfall
// changes (nfft, window, osr).
#ifndef FT8_SNR_CAL_DB
#define FT8_SNR_CAL_DB 30.5f
#endif

// Reported range. WSJT-X floors FT8 at -24; the top keeps a strong local's report sane.
#define FT8_SNR_MIN_DB (-24)
#define FT8_SNR_MAX_DB 40

// Linear power of each waterfall byte: monitor_process stores 2 * dB + 240 in 0.5 dB steps,
// so power = 10^((m - 240) / 20). Precomputed so the noise average needs no pow() per cell.
// Keep in step with the encoding in monitor_process (common/monitor.c).
static const float kMagToPower[256] = {
    1.0000000e-12f, 1.1220185e-12f, 1.2589254e-12f, 1.4125375e-12f, 1.5848932e-12f, 1.7782794e-12f,
    1.9952623e-12f, 2.2387211e-12f, 2.5118864e-12f, 2.8183829e-12f, 3.1622777e-12f, 3.5481339e-12f,
    3.9810717e-12f, 4.4668359e-12f, 5.0118723e-12f, 5.6234133e-12f, 6.3095734e-12f, 7.0794578e-12f,
    7.9432823e-12f, 8.9125094e-12f, 1.0000000e-11f, 1.1220185e-11f, 1.2589254e-11f, 1.4125375e-11f,
    1.5848932e-11f, 1.7782794e-11f, 1.9952623e-11f, 2.2387211e-11f, 2.5118864e-11f, 2.8183829e-11f,
    3.1622777e-11f, 3.5481339e-11f, 3.9810717e-11f, 4.4668359e-11f, 5.0118723e-11f, 5.6234133e-11f,
    6.3095734e-11f, 7.0794578e-11f, 7.9432823e-11f, 8.9125094e-11f, 1.0000000e-10f, 1.1220185e-10f,
    1.2589254e-10f, 1.4125375e-10f, 1.5848932e-10f, 1.7782794e-10f, 1.9952623e-10f, 2.2387211e-10f,
    2.5118864e-10f, 2.8183829e-10f, 3.1622777e-10f, 3.5481339e-10f, 3.9810717e-10f, 4.4668359e-10f,
    5.0118723e-10f, 5.6234133e-10f, 6.3095734e-10f, 7.0794578e-10f, 7.9432823e-10f, 8.9125094e-10f,
    1.0000000e-09f, 1.1220185e-09f, 1.2589254e-09f, 1.4125375e-09f, 1.5848932e-09f, 1.7782794e-09f,
    1.9952623e-09f, 2.2387211e-09f, 2.5118864e-09f, 2.8183829e-09f, 3.1622777e-09f, 3.5481339e-09f,
    3.9810717e-09f, 4.4668359e-09f, 5.0118723e-09f, 5.6234133e-09f, 6.3095734e-09f, 7.0794578e-09f,
    7.9432823e-09f, 8.9125094e-09f, 1.0000000e-08f, 1.1220185e-08f, 1.2589254e-08f, 1.4125375e-08f,
    1.5848932e-08f, 1.7782794e-08f, 1.9952623e-08f, 2.2387211e-08f, 2.5118864e-08f, 2.8183829e-08f,
    3.1622777e-08f, 3.5481339e-08f, 3.9810717e-08f, 4.4668359e-08f, 5.0118723e-08f, 5.6234133e-08f,
    6.3095734e-08f, 7.0794578e-08f, 7.9432823e-08f, 8.9125094e-08f, 1.0000000e-07f, 1.1220185e-07f,
    1.2589254e-07f, 1.4125375e-07f, 1.5848932e-07f, 1.7782794e-07f, 1.9952623e-07f, 2.2387211e-07f,
    2.5118864e-07f, 2.8183829e-07f, 3.1622777e-07f, 3.5481339e-07f, 3.9810717e-07f, 4.4668359e-07f,
    5.0118723e-07f, 5.6234133e-07f, 6.3095734e-07f, 7.0794578e-07f, 7.9432823e-07f, 8.9125094e-07f,
    1.0000000e-06f, 1.1220185e-06f, 1.2589254e-06f, 1.4125375e-06f, 1.5848932e-06f, 1.7782794e-06f,
    1.9952623e-06f, 2.2387211e-06f, 2.5118864e-06f, 2.8183829e-06f, 3.1622777e-06f, 3.5481339e-06f,
    3.9810717e-06f, 4.4668359e-06f, 5.0118723e-06f, 5.6234133e-06f, 6.3095734e-06f, 7.0794578e-06f,
    7.9432823e-06f, 8.9125094e-06f, 1.0000000e-05f, 1.1220185e-05f, 1.2589254e-05f, 1.4125375e-05f,
    1.5848932e-05f, 1.7782794e-05f, 1.9952623e-05f, 2.2387211e-05f, 2.5118864e-05f, 2.8183829e-05f,
    3.1622777e-05f, 3.5481339e-05f, 3.9810717e-05f, 4.4668359e-05f, 5.0118723e-05f, 5.6234133e-05f,
    6.3095734e-05f, 7.0794578e-05f, 7.9432823e-05f, 8.9125094e-05f, 1.0000000e-04f, 1.1220185e-04f,
    1.2589254e-04f, 1.4125375e-04f, 1.5848932e-04f, 1.7782794e-04f, 1.9952623e-04f, 2.2387211e-04f,
    2.5118864e-04f, 2.8183829e-04f, 3.1622777e-04f, 3.5481339e-04f, 3.9810717e-04f, 4.4668359e-04f,
    5.0118723e-04f, 5.6234133e-04f, 6.3095734e-04f, 7.0794578e-04f, 7.9432823e-04f, 8.9125094e-04f,
    1.0000000e-03f, 1.1220185e-03f, 1.2589254e-03f, 1.4125375e-03f, 1.5848932e-03f, 1.7782794e-03f,
    1.9952623e-03f, 2.2387211e-03f, 2.5118864e-03f, 2.8183829e-03f, 3.1622777e-03f, 3.5481339e-03f,
    3.9810717e-03f, 4.4668359e-03f, 5.0118723e-03f, 5.6234133e-03f, 6.3095734e-03f, 7.0794578e-03f,
    7.9432823e-03f, 8.9125094e-03f, 1.0000000e-02f, 1.1220185e-02f, 1.2589254e-02f, 1.4125375e-02f,
    1.5848932e-02f, 1.7782794e-02f, 1.9952623e-02f, 2.2387211e-02f, 2.5118864e-02f, 2.8183829e-02f,
    3.1622777e-02f, 3.5481339e-02f, 3.9810717e-02f, 4.4668359e-02f, 5.0118723e-02f, 5.6234133e-02f,
    6.3095734e-02f, 7.0794578e-02f, 7.9432823e-02f, 8.9125094e-02f, 1.0000000e-01f, 1.1220185e-01f,
    1.2589254e-01f, 1.4125375e-01f, 1.5848932e-01f, 1.7782794e-01f, 1.9952623e-01f, 2.2387211e-01f,
    2.5118864e-01f, 2.8183829e-01f, 3.1622777e-01f, 3.5481339e-01f, 3.9810717e-01f, 4.4668359e-01f,
    5.0118723e-01f, 5.6234133e-01f, 6.3095734e-01f, 7.0794578e-01f, 7.9432823e-01f, 8.9125094e-01f,
    1.0000000e+00f, 1.1220185e+00f, 1.2589254e+00f, 1.4125375e+00f, 1.5848932e+00f, 1.7782794e+00f,
    1.9952623e+00f, 2.2387211e+00f, 2.5118864e+00f, 2.8183829e+00f, 3.1622777e+00f, 3.5481339e+00f,
    3.9810717e+00f, 4.4668359e+00f, 5.0118723e+00f, 5.6234133e+00f,
};

/// Compute log likelihood log(p(1) / p(0)) of 174 message bits for later use in soft-decision LDPC decoding
/// @param[in] wf Waterfall data collected during message slot
/// @param[in] cand Candidate to extract the message from
/// @param[in] code_map Symbol encoding map
/// @param[out] log174 Output of decoded log likelihoods for each of the 174 message bits
static void ft4_extract_likelihood(const waterfall_t* wf, const candidate_t* cand, float* log174);
static void ft8_extract_likelihood(const waterfall_t* wf, const candidate_t* cand, float* log174);

/// Packs a string of bits each represented as a zero/non-zero byte in bit_array[],
/// as a string of packed bits starting from the MSB of the first byte of packed[]
/// @param[in] plain Array of bits (0 and nonzero values) with num_bits entires
/// @param[in] num_bits Number of bits (entries) passed in bit_array
/// @param[out] packed Byte-packed bits representing the data in bit_array
static void pack_bits(const uint8_t bit_array[], int num_bits, uint8_t packed[]);

static float max2(float a, float b);
static float max4(float a, float b, float c, float d);
static void heapify_down(candidate_t heap[], int heap_size);
static void heapify_up(candidate_t heap[], int heap_size);

static void ftx_normalize_logl(float* log174);
static void ft4_extract_symbol(const uint8_t* wf, float* logl);
static void ft8_extract_symbol(const uint8_t* wf, float* logl);
static void ft8_decode_multi_symbols(const uint8_t* wf, int num_bins, int n_syms, int bit_idx, float* log174);

// Pointer to the candidate's frequency/sub-block slot in absolute block 0 of the waterfall.
// We deliberately leave candidate->time_offset OUT of this base: time_offset ranges -12..23
// (see ft8_find_sync), so folding it in here would form an out-of-bounds pointer (undefined
// behavior) whenever it is negative, even though the per-symbol block_abs guards stop us from
// ever dereferencing a negative block. Callers add `block_abs * block_stride` instead, where
// block_abs = time_offset + block is range-checked to [0, num_blocks) before use — keeping all
// pointer arithmetic in-bounds. time_sub < time_osr, freq_sub < freq_osr and freq_offset+7 <
// num_bins, so this base itself always lands inside block 0.
static const uint8_t* get_cand_mag_base(const waterfall_t* wf, const candidate_t* candidate)
{
    int offset = candidate->time_sub;
    offset = (offset * wf->freq_osr) + candidate->freq_sub;
    offset = (offset * wf->num_bins) + candidate->freq_offset;
    return wf->mag + offset;
}

// Value at fraction pct (0..1) of x[0..n-1] in ascending order. Sorts x in place
// (insertion sort; n is at most FT8_SNR_NOISE_HALF_BINS).
static float ft8_snr_percentile(float* x, int n, float pct)
{
    for (int i = 1; i < n; ++i)
    {
        float v = x[i];
        int j = i - 1;
        while (j >= 0 && x[j] > v)
        {
            x[j + 1] = x[j];
            --j;
        }
        x[j + 1] = v;
    }
    return x[(int)(pct * (n - 1))];
}

int ft8_snr(const waterfall_t* wf, const candidate_t* candidate)
{
    int sum_signal = 0;
    int sum_noise = 0;
    int num_average = 0;

    // Base pointer to block 0; symbols are reached via block_abs (see get_cand_mag_base).
    const uint8_t* mag_base = get_cand_mag_base(wf, candidate);

    // FT4 and FT2 share a 4-GFSK layout (105 symbols, 4 sync groups of 4) completely
    // different from FT8's, so they need their own signal/noise estimate. Without this
    // branch the FT8 loop below ran FT8's 79-symbol / 8-tone Costas structure over an
    // FT4/FT2 signal, mis-locating the sync tones and reading the SNR ~15 dB low. Mirror
    // ft4_sync_score's geometry: at each sync symbol the expected tone
    // (kFT4_Costas_pattern[m][k]) is the signal and the other three tones are the noise.
    if (wf->protocol == FTX_PROTOCOL_FT4 || wf->protocol == FTX_PROTOCOL_FT2)
    {
        for (int m = 0; m < FT4_NUM_SYNC; ++m)
        {
            for (int k = 0; k < FT4_LENGTH_SYNC; ++k)
            {
                int block = 1 + (FT4_SYNC_OFFSET * m) + k;
                int block_abs = candidate->time_offset + block;
                if (block_abs < 0)
                    continue;
                if (block_abs >= wf->num_blocks)
                    break;

                const uint8_t* p4 = mag_base + (block_abs * wf->block_stride);
                int sm = kFT4_Costas_pattern[m][k]; // expected tone (0..3)
                sum_signal += p4[sm];
                // Noise = average of the other three of the four FT4 tones (+1 rounds /3).
                sum_noise += (1 + (int)p4[0] + (int)p4[1] + (int)p4[2] + (int)p4[3] - (int)p4[sm]) / 3;
                ++num_average;
            }
        }
        if (num_average == 0)
            return -24; // no usable sync symbols in this window; report a deep floor
        // Calibration: the raw 4-GFSK magnitude difference reads ~20 dB hotter than FT8's
        // scale (the monitor normalizes the FT4/FT2 magnitudes differently than FT8's
        // 8-GFSK), so subtract a fixed offset to bring decoded FT4/FT2 SNRs into the same dB
        // range as FT8. Tuned against on-air FT4 reciprocity: a station reporting us +10 was
        // read here at +31 before this offset. Adjust if FT4 reads consistently high/low
        // versus WSJT-X / reciprocal reports.
        return (sum_signal - sum_noise) / num_average - FT4_SNR_CAL_DB;
    }

    // Noise: time-averaged linear power per bin in the candidate's (time_sub, freq_sub)
    // plane over the window around its 8 tones, then a low percentile of each side.
    // Only the window is averaged (at most 104 bins), not the whole plane: ft8_snr runs
    // once per decoded message and the waterfall is rebuilt after every subtraction
    // pass, so a whole-plane cache would do more work than it saves.
    if (wf->num_blocks <= 0)
        return FT8_SNR_MIN_DB;
    const int fo = candidate->freq_offset;
    int lo = fo - FT8_SNR_NOISE_HALF_BINS;
    int hi = fo + 8 + FT8_SNR_NOISE_HALF_BINS;
    if (lo < 0)
        lo = 0;
    if (hi > wf->num_bins)
        hi = wf->num_bins;
    if (hi <= lo)
        return FT8_SNR_MIN_DB;

    float profile[2 * FT8_SNR_NOISE_HALF_BINS + 8] = { 0 };
    const uint8_t* plane = wf->mag + ((candidate->time_sub * wf->freq_osr) + candidate->freq_sub) * wf->num_bins + lo;
    for (int block = 0; block < wf->num_blocks; ++block)
    {
        const uint8_t* row = plane + (size_t)block * wf->block_stride;
        for (int i = 0; i < hi - lo; ++i)
            profile[i] += kMagToPower[row[i]];
    }
    // Lower side [lo, fo) and upper side [fo + 8, hi); the candidate's own bins are skipped.
    const int side_start[2] = { lo, fo + 8 };
    const int side_end[2] = { fo, hi };
    float noise = 0;
    for (int side = 0; side < 2; ++side)
    {
        int start = (side_start[side] > lo ? side_start[side] : lo) - lo;
        int end = (side_end[side] < hi ? side_end[side] : hi) - lo;
        if (end - start < FT8_SNR_NOISE_MIN_BINS)
            continue;
        float level = ft8_snr_percentile(profile + start, end - start, FT8_SNR_NOISE_PERCENTILE);
        if (level > noise)
            noise = level;
    }
    if (noise <= 0)
        return FT8_SNR_MIN_DB; // no side wide enough to measure
    noise /= wf->num_blocks;

    // Signal: mean linear power of the expected tone over the three Costas sync groups
    // (symbols 0-6, 36-42, 72-78). Data symbols are skipped because picking the loudest
    // of 8 tones inflates the "signal" when S/N is low.
    float sum_power = 0;
    for (int block = 0; block < FT8_NN; ++block)
    {
        int block_abs = candidate->time_offset + block;
        if (block_abs < 0)
            continue;
        if (block_abs >= wf->num_blocks)
            break;

        int k = block % FT8_SYNC_OFFSET;
        if (k >= FT8_LENGTH_SYNC)
            continue; // skip data symbols

        const uint8_t* p8 = mag_base + (block_abs * wf->block_stride);
        sum_power += kMagToPower[p8[kFT8_Costas_pattern[k]]];
        ++num_average;
    }
    if (num_average == 0)
        return FT8_SNR_MIN_DB;

    // The sync tone carries signal plus noise; subtract the baseline. Floor the excess at
    // -30 dB re noise so a sync tone at or below the baseline lands on the -24 clamp.
    float excess = sum_power / num_average - noise;
    if (excess < noise * 1e-3f)
        excess = noise * 1e-3f;
    int snr = (int)lrintf(10.0f * log10f(excess / noise) - FT8_SNR_CAL_DB);
    if (snr < FT8_SNR_MIN_DB)
        return FT8_SNR_MIN_DB;
    if (snr > FT8_SNR_MAX_DB)
        return FT8_SNR_MAX_DB;
    return snr;
}

static int ft8_sync_score(const waterfall_t* wf, const candidate_t* candidate)
{
    int score = 0;
    int num_average = 0;

    // Base pointer to block 0; symbols are reached via block_abs (see get_cand_mag_base).
    const uint8_t* mag_base = get_cand_mag_base(wf, candidate);

    // Compute average score over sync symbols (m+k = 0-7, 36-43, 72-79)
    for (int m = 0; m < FT8_NUM_SYNC; ++m)
    {
        for (int k = 0; k < FT8_LENGTH_SYNC; ++k)
        {
            int block = (FT8_SYNC_OFFSET * m) + k;          // relative to the message
            int block_abs = candidate->time_offset + block; // relative to the captured signal
            // Check for time boundaries
            if (block_abs < 0)
                continue;
            if (block_abs >= wf->num_blocks)
                break;

            // Get the pointer to symbol 'block' of the candidate
            const uint8_t* p8 = mag_base + (block_abs * wf->block_stride);

            // Weighted difference between the expected and all other symbols
            // Does not work as well as the alternative score below
            // score += 8 * p8[kFT8_Costas_pattern[k]] -
            //          p8[0] - p8[1] - p8[2] - p8[3] -
            //          p8[4] - p8[5] - p8[6] - p8[7];
            // ++num_average;

            // Check only the neighbors of the expected symbol frequency- and time-wise
            int sm = kFT8_Costas_pattern[k]; // Index of the expected bin
            if (sm > 0)
            {
                // look at one frequency bin lower
                score += p8[sm] - p8[sm - 1];
                ++num_average;
            }
            if (sm < 7)
            {
                // look at one frequency bin higher
                score += p8[sm] - p8[sm + 1];
                ++num_average;
            }
            if ((k > 0) && (block_abs > 0))
            {
                // look one symbol back in time
                score += p8[sm] - p8[sm - wf->block_stride];
                ++num_average;
            }
            if (((k + 1) < FT8_LENGTH_SYNC) && ((block_abs + 1) < wf->num_blocks))
            {
                // look one symbol forward in time
                score += p8[sm] - p8[sm + wf->block_stride];
                ++num_average;
            }
        }
    }

    if (num_average > 0)
        score /= num_average;

    return score;
}

static int ft4_sync_score(const waterfall_t* wf, const candidate_t* candidate)
{
    int score = 0;
    int num_average = 0;

    // Base pointer to block 0; symbols are reached via block_abs (see get_cand_mag_base).
    const uint8_t* mag_base = get_cand_mag_base(wf, candidate);

    // Compute average score over sync symbols (block = 1-4, 34-37, 67-70, 100-103)
    for (int m = 0; m < FT4_NUM_SYNC; ++m)
    {
        for (int k = 0; k < FT4_LENGTH_SYNC; ++k)
        {
            int block = 1 + (FT4_SYNC_OFFSET * m) + k;
            int block_abs = candidate->time_offset + block;
            // Check for time boundaries
            if (block_abs < 0)
                continue;
            if (block_abs >= wf->num_blocks)
                break;

            // Get the pointer to symbol 'block' of the candidate
            const uint8_t* p4 = mag_base + (block_abs * wf->block_stride);

            int sm = kFT4_Costas_pattern[m][k]; // Index of the expected bin

            // score += (4 * p4[sm]) - p4[0] - p4[1] - p4[2] - p4[3];
            // num_average += 4;

            // Check only the neighbors of the expected symbol frequency- and time-wise
            if (sm > 0)
            {
                // look at one frequency bin lower
                score += p4[sm] - p4[sm - 1];
                ++num_average;
            }
            if (sm < 3)
            {
                // look at one frequency bin higher
                score += p4[sm] - p4[sm + 1];
                ++num_average;
            }
            if ((k > 0) && (block_abs > 0))
            {
                // look one symbol back in time
                score += p4[sm] - p4[sm - wf->block_stride];
                ++num_average;
            }
            if (((k + 1) < FT4_LENGTH_SYNC) && ((block_abs + 1) < wf->num_blocks))
            {
                // look one symbol forward in time
                score += p4[sm] - p4[sm + wf->block_stride];
                ++num_average;
            }
        }
    }

    if (num_average > 0)
        score /= num_average;

    return score;
}

int ft8_find_sync(const waterfall_t* wf, int num_candidates, candidate_t heap[], int min_score)
{
    int heap_size = 0;
    candidate_t candidate;

    // Here we allow time offsets that exceed signal boundaries, as long as we still have all data bits.
    // I.e. we can afford to skip the first 7 or the last 7 Costas symbols, as long as we track how many
    // sync symbols we included in the score, so the score is averaged.
    for (candidate.time_sub = 0; candidate.time_sub < wf->time_osr; ++candidate.time_sub)
    {
        for (candidate.freq_sub = 0; candidate.freq_sub < wf->freq_osr; ++candidate.freq_sub)
        {
            for (candidate.time_offset = -12; candidate.time_offset < 24; ++candidate.time_offset)
            {
                for (candidate.freq_offset = 0; (candidate.freq_offset + 7) < wf->num_bins; ++candidate.freq_offset)
                {
                    if (wf->protocol != FTX_PROTOCOL_FT8) // FT4 and FT2 share the 4-Costas sync
                    {
                        candidate.score = ft4_sync_score(wf, &candidate);
                    }
                    else
                    {
                        candidate.score = ft8_sync_score(wf, &candidate);
                        // candidate.score = ft8_snr(wf, &candidate);
                    }

                    if (candidate.score < min_score)
                        continue;

                    // If the heap is full AND the current candidate is better than
                    // the worst in the heap, we remove the worst and make space
                    if ((heap_size == num_candidates) && (candidate.score > heap[0].score))
                    {
                        --heap_size;
                        heap[0] = heap[heap_size];
                        heapify_down(heap, heap_size);
                    }

                    // If there's free space in the heap, we add the current candidate
                    if (heap_size < num_candidates)
                    {
                        heap[heap_size] = candidate;
                        ++heap_size;
                        heapify_up(heap, heap_size);
                    }
                }
            }
        }
    }

    // Sort the candidates by sync strength - here we benefit from the heap structure
    int len_unsorted = heap_size;
    while (len_unsorted > 1)
    {
        // Take the top (index 0) element which is guaranteed to have the smallest score,
        // exchange it with the last element in the heap, and decrease the heap size.
        // Then restore the heap property in the new, smaller heap.
        // At the end the elements will be sorted in descending order.
        candidate_t tmp = heap[len_unsorted - 1];
        heap[len_unsorted - 1] = heap[0];
        heap[0] = tmp;
        len_unsorted--;
        heapify_down(heap, len_unsorted);
    }

    return heap_size;
}

static void ft4_extract_likelihood(const waterfall_t* wf, const candidate_t* cand, float* log174)
{
    const uint8_t* mag_base = get_cand_mag_base(wf, cand);

    // Go over FSK tones and skip Costas sync symbols
    for (int k = 0; k < FT4_ND; ++k)
    {
        // Skip either 5, 9 or 13 sync symbols
        // TODO: replace magic numbers with constants
        int sym_idx = k + ((k < 29) ? 5 : ((k < 58) ? 9 : 13));
        int bit_idx = 2 * k;

        // Check for time boundaries
        int block = cand->time_offset + sym_idx;
        if ((block < 0) || (block >= wf->num_blocks))
        {
            log174[bit_idx + 0] = 0;
            log174[bit_idx + 1] = 0;
        }
        else
        {
            // Pointer to 4 bins of the current symbol (block == block_abs here)
            const uint8_t* ps = mag_base + (block * wf->block_stride);

            ft4_extract_symbol(ps, log174 + bit_idx);
        }
    }
}

static void ft8_extract_likelihood(const waterfall_t* wf, const candidate_t* cand, float* log174)
{
    const uint8_t* mag_base = get_cand_mag_base(wf, cand);

    // Go over FSK tones and skip Costas sync symbols
    for (int k = 0; k < FT8_ND; ++k)
    {
        // Skip either 7 or 14 sync symbols
        // TODO: replace magic numbers with constants
        int sym_idx = k + ((k < 29) ? 7 : 14);
        int bit_idx = 3 * k;

        // Check for time boundaries
        int block = cand->time_offset + sym_idx;
        if ((block < 0) || (block >= wf->num_blocks))
        {
            log174[bit_idx + 0] = 0;
            log174[bit_idx + 1] = 0;
            log174[bit_idx + 2] = 0;
        }
        else
        {
            // Pointer to 8 bins of the current symbol (block == block_abs here)
            const uint8_t* ps = mag_base + (block * wf->block_stride);

            ft8_extract_symbol(ps, log174 + bit_idx);
        }
    }
}

static void ftx_normalize_logl(float* log174)
{
    // Compute the variance of log174
    float sum = 0;
    float sum2 = 0;
    for (int i = 0; i < FTX_LDPC_N; ++i)
    {
        sum += log174[i];
        sum2 += log174[i] * log174[i];
    }
    float inv_n = 1.0f / FTX_LDPC_N;
    float variance = (sum2 - (sum * sum * inv_n)) * inv_n;

    // Normalize log174 distribution and scale it with experimentally found coefficient
    float norm_factor = sqrtf(24.0f / variance);
    for (int i = 0; i < FTX_LDPC_N; ++i)
    {
        log174[i] *= norm_factor;
    }
}

bool ftx_decode_llrs(ftx_protocol_t protocol, const float* llrs, int max_iterations,
                     int osd_depth, int osd_err_gate, ftx_message_t* message, decode_status_t* status)
{
    // Work on a normalized copy: callers hand in raw log-likelihoods on
    // whatever scale their demodulator produced.
    float log174[FTX_LDPC_N];
    for (int i = 0; i < FTX_LDPC_N; ++i)
        log174[i] = llrs[i];
    ftx_normalize_logl(log174);

    uint8_t plain174[FTX_LDPC_N]; // message bits (0/1)
    bp_decode(log174, max_iterations, plain174, &status->ldpc_errors);
    // ldpc_decode(log174, max_iterations, plain174, &status->ldpc_errors);

    if (status->ldpc_errors > 0)
    {
        // OSD backstop: belief propagation failed, but a near-miss (few
        // unsatisfied parity checks) is often recoverable by ordered
        // statistics. The gate skips hopeless candidates (junk typically
        // fails 40-80 checks) — cheap insurance for both CPU and precision.
        if (osd_depth <= 0 || status->ldpc_errors > osd_err_gate)
            return false;
        if (!osd_decode(log174, osd_depth, plain174, NULL))
            return false;
        // plain174 now holds a valid codeword (zero unsatisfied parity checks
        // by construction) that already passed OSD's CRC and plausibility
        // gates: clear the BP error count so the success contract
        // (ldpc_errors == 0) holds for callers, and fall through to the
        // standard CRC/unpack path.
        status->ldpc_errors = 0;
    }

    // Extract payload + CRC (first FTX_LDPC_K bits) packed into a byte array
    uint8_t a91[FTX_LDPC_K_BYTES];
    pack_bits(plain174, FTX_LDPC_K, a91);

    // Extract CRC and check it
    status->crc_extracted = ftx_extract_crc(a91);
    // [1]: 'The CRC is calculated on the source-encoded message, zero-extended from 77 to 82 bits.'
    a91[9] &= 0xF8;
    a91[10] &= 0x00;
    status->crc_calculated = ftx_compute_crc(a91, 96 - 14);

    if (status->crc_extracted != status->crc_calculated)
    {
        return false;
    }

    // Reuse CRC value as a hash for the message (TODO: 14 bits only, should perhaps use full 16 or 32 bits?)
    message->hash = status->crc_calculated;

    if (protocol != FTX_PROTOCOL_FT8) // FT4 and FT2 both XOR the payload (FT8 does not)
    {
        // '[..] for FT4 only, in order to avoid transmitting a long string of zeros when sending CQ messages,
        // the assembled 77-bit message is bitwise exclusive-OR’ed with [a] pseudorandom sequence before computing the CRC and FEC parity bits'
        // FT2 inherits FT4's framing, so the same XOR de-scramble applies.
        for (int i = 0; i < 10; ++i)
        {
            message->payload[i] = a91[i] ^ kFT4_XOR_sequence[i];
        }
    }
    else
    {
        for (int i = 0; i < 10; ++i)
        {
            message->payload[i] = a91[i];
        }
    }

    // LOG(LOG_DEBUG, "Decoded message (CRC %04x), trying to unpack...\n", status->crc_extracted);
    return true;
}

bool ft8_decode_osd(const waterfall_t* wf, const candidate_t* cand, int max_iterations,
                    int osd_depth, int osd_err_gate, ftx_message_t* message, decode_status_t* status)
{
    float log174[FTX_LDPC_N]; // message bits encoded as likelihood
    if (wf->protocol != FTX_PROTOCOL_FT8) // FT4 and FT2 share the 2-bit/symbol layout
    {
        ft4_extract_likelihood(wf, cand, log174);
    }
    else
    {
        ft8_extract_likelihood(wf, cand, log174);
    }

    return ftx_decode_llrs(wf->protocol, log174, max_iterations, osd_depth, osd_err_gate, message, status);
}

bool ft8_decode(const waterfall_t* wf, const candidate_t* cand, int max_iterations, ftx_message_t* message, decode_status_t* status)
{
    return ft8_decode_osd(wf, cand, max_iterations, 0, 0, message, status);
}

static float max2(float a, float b)
{
    return (a >= b) ? a : b;
}

static float max4(float a, float b, float c, float d)
{
    return max2(max2(a, b), max2(c, d));
}

static void heapify_down(candidate_t heap[], int heap_size)
{
    // heapify from the root down
    int current = 0; // root node
    while (true)
    {
        int left = 2 * current + 1;
        int right = left + 1;

        // Find the smallest value of (parent, left child, right child)
        int smallest = current;
        if ((left < heap_size) && (heap[left].score < heap[smallest].score))
        {
            smallest = left;
        }
        if ((right < heap_size) && (heap[right].score < heap[smallest].score))
        {
            smallest = right;
        }

        if (smallest == current)
        {
            break;
        }

        // Exchange the current node with the smallest child and move down to it
        candidate_t tmp = heap[smallest];
        heap[smallest] = heap[current];
        heap[current] = tmp;
        current = smallest;
    }
}

static void heapify_up(candidate_t heap[], int heap_size)
{
    // heapify from the last node up
    int current = heap_size - 1;
    while (current > 0)
    {
        int parent = (current - 1) / 2;
        if (!(heap[current].score < heap[parent].score))
        {
            break;
        }

        // Exchange the current node with its parent and move up
        candidate_t tmp = heap[parent];
        heap[parent] = heap[current];
        heap[current] = tmp;
        current = parent;
    }
}

// Compute unnormalized log likelihood log(p(1) / p(0)) of 2 message bits (1 FSK symbol)
static void ft4_extract_symbol(const uint8_t* wf, float* logl)
{
    // Cleaned up code for the simple case of n_syms==1
    float s2[4];

    for (int j = 0; j < 4; ++j)
    {
        s2[j] = (float)wf[kFT4_Gray_map[j]];
    }

    logl[0] = max2(s2[2], s2[3]) - max2(s2[0], s2[1]);
    logl[1] = max2(s2[1], s2[3]) - max2(s2[0], s2[2]);
}

// Compute unnormalized log likelihood log(p(1) / p(0)) of 3 message bits (1 FSK symbol)
static void ft8_extract_symbol(const uint8_t* wf, float* logl)
{
    // Cleaned up code for the simple case of n_syms==1
    float s2[8];

    for (int j = 0; j < 8; ++j)
    {
        s2[j] = (float)wf[kFT8_Gray_map[j]];
    }

    logl[0] = max4(s2[4], s2[5], s2[6], s2[7]) - max4(s2[0], s2[1], s2[2], s2[3]);
    logl[1] = max4(s2[2], s2[3], s2[6], s2[7]) - max4(s2[0], s2[1], s2[4], s2[5]);
    logl[2] = max4(s2[1], s2[3], s2[5], s2[7]) - max4(s2[0], s2[2], s2[4], s2[6]);
}

// Compute unnormalized log likelihood log(p(1) / p(0)) of bits corresponding to several FSK symbols at once
static void ft8_decode_multi_symbols(const uint8_t* wf, int num_bins, int n_syms, int bit_idx, float* log174)
{
    const int n_bits = 3 * n_syms;
    const int n_tones = (1 << n_bits);

    float s2[n_tones];

    for (int j = 0; j < n_tones; ++j)
    {
        int j1 = j & 0x07;
        if (n_syms == 1)
        {
            s2[j] = (float)wf[kFT8_Gray_map[j1]];
            continue;
        }
        int j2 = (j >> 3) & 0x07;
        if (n_syms == 2)
        {
            s2[j] = (float)wf[kFT8_Gray_map[j2]];
            s2[j] += (float)wf[kFT8_Gray_map[j1] + 4 * num_bins];
            continue;
        }
        int j3 = (j >> 6) & 0x07;
        s2[j] = (float)wf[kFT8_Gray_map[j3]];
        s2[j] += (float)wf[kFT8_Gray_map[j2] + 4 * num_bins];
        s2[j] += (float)wf[kFT8_Gray_map[j1] + 8 * num_bins];
    }

    // Extract bit significance (and convert them to float)
    // 8 FSK tones = 3 bits
    for (int i = 0; i < n_bits; ++i)
    {
        if (bit_idx + i >= FTX_LDPC_N)
        {
            // Respect array size
            break;
        }

        uint16_t mask = (n_tones >> (i + 1));
        float max_zero = -1000, max_one = -1000;
        for (int n = 0; n < n_tones; ++n)
        {
            if (n & mask)
            {
                max_one = max2(max_one, s2[n]);
            }
            else
            {
                max_zero = max2(max_zero, s2[n]);
            }
        }

        log174[bit_idx + i] = max_one - max_zero;
    }
}

// Packs a string of bits each represented as a zero/non-zero byte in plain[],
// as a string of packed bits starting from the MSB of the first byte of packed[]
static void pack_bits(const uint8_t bit_array[], int num_bits, uint8_t packed[])
{
    int num_bytes = (num_bits + 7) / 8;
    for (int i = 0; i < num_bytes; ++i)
    {
        packed[i] = 0;
    }

    uint8_t mask = 0x80;
    int byte_idx = 0;
    for (int i = 0; i < num_bits; ++i)
    {
        if (bit_array[i])
        {
            packed[byte_idx] |= mask;
        }
        mask >>= 1;
        if (!mask)
        {
            mask = 0x80;
            ++byte_idx;
        }
    }
}