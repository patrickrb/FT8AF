// GFSK waveform synthesis for FT8/FT4 tone sequences.
//
// This is the "glue" that the prebuilt libft8cn.so provided via JNI
// (GenerateFT8.synth_gfsk). The algorithm is the standard GFSK modulator from
// kgoba/ft8_lib (demo/gen_ft8.c, commit post-6f528128), adapted to expose the
// `offset` parameter the desktop Rust FFI expects (write into signal[offset..]).
//
// The pinned ft8_lib (6f528128) shipped only decode + tone-encode; it did not
// include synth_gfsk. The closed-source .so contained this function, so we
// reconstruct it here from the canonical kgoba reference so the desktop port
// can generate TX audio without the .so.

#include <math.h>
#include <stdint.h>
#include <stdlib.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// pi * sqrt(2 / ln(2)) — normalisation constant for the Gaussian pulse.
#define GFSK_CONST_K 5.336446f

// Compute a GFSK smoothing pulse spanning 3 symbol periods.
// pulse[] must have space for 3 * n_spsym elements.
static void gfsk_pulse(int n_spsym, float symbol_bt, float *pulse)
{
    for (int i = 0; i < 3 * n_spsym; ++i)
    {
        float t = i / (float)n_spsym - 1.5f;
        float arg1 = GFSK_CONST_K * symbol_bt * (t + 0.5f);
        float arg2 = GFSK_CONST_K * symbol_bt * (t - 0.5f);
        pulse[i] = (erff(arg1) - erff(arg2)) / 2;
    }
}

// Synthesise a GFSK waveform from a tone sequence, writing into
// signal[offset .. offset + n_sym*n_spsym).
//
// Parameters match the JNI signature of GenerateFT8.synth_gfsk:
//   symbols      — tone values (0..7 for FT8, 0..3 for FT4)
//   n_sym        — number of symbols (79 for FT8, 105 for FT4)
//   f0           — base audio frequency in Hz
//   symbol_bt    — Gaussian BT product (2.0 for FT8, 1.0 for FT4)
//   symbol_period— symbol duration in seconds (0.160 for FT8)
//   signal_rate  — output sample rate in Hz (e.g. 12000)
//   signal       — output buffer (caller-allocated)
//   offset       — sample index at which to start writing
void synth_gfsk_offset(const uint8_t *symbols, int n_sym, float f0,
                       float symbol_bt, float symbol_period,
                       int signal_rate, float *signal, int offset)
{
    // Guard: reject degenerate inputs that would cause division by zero or
    // zero-length allocations (e.g. signal_rate == 0 producing n_spsym == 0).
    if (symbols == NULL || signal == NULL || n_sym <= 0 ||
        signal_rate <= 0 || symbol_period <= 0)
        return;

    int n_spsym = (int)(0.5f + signal_rate * symbol_period); // samples per symbol
    if (n_spsym <= 0) return; // would cause division by zero in dphi_peak
    int n_wave  = n_sym * n_spsym;                           // total output samples
    float hmod  = 1.0f;

    float dphi_peak = 2 * (float)M_PI * hmod / n_spsym;

    // Heap-allocate the extended dphi array (n_wave + 2 guard symbols).
    // At 12 kHz this is ~607 KiB — too large for default stack sizes.
    int dphi_len = n_wave + 2 * n_spsym;
    float *dphi = (float *)malloc(dphi_len * sizeof(float));
    float *pulse = (float *)malloc(3 * n_spsym * sizeof(float));

    if (dphi == NULL || pulse == NULL) {
        free(dphi);  // free(NULL) is safe per C standard
        free(pulse);
        return;
    }

    // Initialise every sample to the base-frequency phase increment.
    for (int i = 0; i < dphi_len; ++i)
        dphi[i] = 2 * (float)M_PI * f0 / signal_rate;

    // Compute the Gaussian pulse shape (3 symbol periods wide).
    gfsk_pulse(n_spsym, symbol_bt, pulse);

    // Accumulate each symbol's frequency deviation through the pulse.
    for (int i = 0; i < n_sym; ++i)
    {
        int ib = i * n_spsym;
        for (int j = 0; j < 3 * n_spsym; ++j)
            dphi[j + ib] += dphi_peak * symbols[i] * pulse[j];
    }

    // Extend first and last symbol tones into the guard regions so the
    // Gaussian tails don't see a discontinuity.
    for (int j = 0; j < 2 * n_spsym; ++j)
    {
        dphi[j]                    += dphi_peak * pulse[j + n_spsym] * symbols[0];
        dphi[j + n_sym * n_spsym] += dphi_peak * pulse[j]            * symbols[n_sym - 1];
    }

    // Integrate phase and generate the sinusoidal waveform.
    float phi = 0;
    for (int k = 0; k < n_wave; ++k)
    {
        signal[offset + k] = sinf(phi);
        phi = fmodf(phi + dphi[k + n_spsym], 2 * (float)M_PI);
    }

    // Raised-cosine envelope ramp on the first and last 1/8 symbol.
    int n_ramp = n_spsym / 8;
    for (int i = 0; i < n_ramp; ++i)
    {
        float env = (1 - cosf(2 * (float)M_PI * i / (2 * n_ramp))) / 2;
        signal[offset + i]              *= env;
        signal[offset + n_wave - 1 - i] *= env;
    }

    free(dphi);
    free(pulse);
}
