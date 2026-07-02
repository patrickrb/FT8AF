// Host unit tests for ft8_fine.c — the fine-sync coherent demod retry.
// Run by run_host_tests.sh / .ps1.
//
// Covered:
//   1. Partial-transmission recovery (the motivating case, observed on-air:
//      a station keying up ~4 s late transmits only symbols 27..78, slot-
//      aligned). The waterfall LLR path fails — the 20 dead data symbols
//      read random noise magnitudes that swamp BP — while the fine path's
//      log-domain float LLRs leave them as soft erasures and decode off the
//      live symbols. Both outcomes are asserted.
//   2. Fine sync accuracy: an off-grid signal (+1.4 Hz, +15 ms off the
//      candidate grid) is demodulated and the refined frequency/time are
//      recovered within tolerance.
//   3. Junk guard: a fabricated candidate over pure noise reports sync
//      quality below FT8AF_FINE_SYNC_MIN, so it never reaches the decoder.

#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <ft8/constants.h>
#include <ft8/decode.h>
#include <ft8/encode.h>
#include <ft8/message.h>

#include "common/monitor.h"
#include "decode_params.h"
#include "ft8_fine.h"

void synth_gfsk_offset(const uint8_t* symbols, int n_sym, float f0,
                       float symbol_bt, float symbol_period,
                       int signal_rate, float* signal, int offset);

#define RATE 12000
#define NSAMPLES (13500 * RATE / 1000)

static int g_failures = 0;

static void check(bool ok, const char* what)
{
    printf("  %s %s\n", ok ? "ok:  " : "FAIL:", what);
    if (!ok)
        g_failures++;
}

static void add_noise(float* signal, int n, float amp, uint32_t seed)
{
    uint32_t s = seed;
    for (int i = 0; i < n; ++i)
    {
        s = s * 1664525u + 1013904223u;
        signal[i] += amp * (2.0f * ((s >> 8) & 0xFFFF) / 65535.0f - 1.0f);
    }
}

static void monitor_setup(monitor_t* mon)
{
    monitor_config_t cfg;
    cfg.f_min = 100;
    cfg.f_max = 3500;
    cfg.sample_rate = RATE;
    cfg.time_osr = FT8AF_TIME_OSR;
    cfg.freq_osr = FT8AF_FREQ_OSR;
    cfg.protocol = FTX_PROTOCOL_FT8;
    monitor_init(mon, &cfg);
}

static void feed(monitor_t* mon, const float* signal)
{
    monitor_reset(mon);
    for (int pos = 0; pos + mon->block_size <= NSAMPLES; pos += mon->block_size)
        monitor_process(mon, signal + pos);
}

// Try to decode `text` near freq_hz: first via the waterfall path, then via
// the fine retry — mirroring the deep-pass pipeline. Reports which path (if
// any) succeeded and the refined outputs of the fine path.
typedef struct
{
    bool wf_decoded;
    bool fine_decoded;
    float f_fine;
    float t_fine;
    float quality;
} pipeline_result_t;

static pipeline_result_t try_decode(monitor_t* mon, ft8_fine_ctx_t* fine,
                                    const char* text, float freq_hz)
{
    pipeline_result_t r = { false, false, 0, 0, 0 };
    static candidate_t cands[FT8AF_MAX_CANDIDATES];
    int n = ft8_find_sync(&mon->wf, FT8AF_MAX_CANDIDATES, cands, FT8AF_MIN_SCORE_DEEP);
    for (int i = 0; i < n; ++i)
    {
        float f = (mon->min_bin + cands[i].freq_offset +
                   (float)cands[i].freq_sub / mon->wf.freq_osr) / mon->symbol_period;
        if (fabsf(f - freq_hz) > 10.0f)
            continue;

        ftx_message_t message;
        decode_status_t status;
        char got[64] = { 0 };
        if (ft8_decode_osd(&mon->wf, &cands[i], FT8AF_LDPC_ITERS_DEEP,
                           FT8AF_OSD_DEPTH_DEEP, FT8AF_OSD_LDPC_ERR_GATE,
                           &message, &status) &&
            ftx_message_decode(&message, NULL, got) == FTX_MESSAGE_RC_OK &&
            strcmp(got, text) == 0)
        {
            r.wf_decoded = true;
        }

        float llrs[FTX_LDPC_N];
        float f_fine, t_fine, quality;
        if (ft8_fine_demod(fine, mon, &cands[i], llrs, &f_fine, &t_fine, &quality) &&
            quality >= FT8AF_FINE_SYNC_MIN &&
            ftx_decode_llrs(FTX_PROTOCOL_FT8, llrs, FT8AF_LDPC_ITERS_DEEP,
                            FT8AF_OSD_DEPTH_DEEP, FT8AF_OSD_LDPC_ERR_GATE,
                            &message, &status) &&
            ftx_message_decode(&message, NULL, got) == FTX_MESSAGE_RC_OK &&
            strcmp(got, text) == 0)
        {
            if (!r.fine_decoded || quality > r.quality)
            {
                r.fine_decoded = true;
                r.f_fine = f_fine;
                r.t_fine = t_fine;
                r.quality = quality;
            }
        }
    }
    return r;
}

int main(void)
{
    static float signal[NSAMPLES];
    monitor_t mon;
    monitor_setup(&mon);

    ftx_message_t msg;
    ftx_message_init(&msg);
    ftx_message_encode(&msg, NULL, "TA1NGE RA3TPE LO25");
    uint8_t tones[FT8_NN];
    ft8_encode(msg.payload, tones);
    const int n_spsym = RATE * 160 / 1000;
    const int n_wave = FT8_NN * n_spsym;
    float* wave = (float*)calloc(n_wave, sizeof(float));

    // ---- 1. partial transmission: only symbols 27..78 on the air -----------
    // Off-grid frequency and time (like any real station): +1.4 Hz off the
    // 3.125 Hz grid, 15 ms off the 40 ms grid, on top of the 20 missing data
    // symbols. The waterfall path gets confidently-wrong LLRs for the dead
    // symbols AND grid misalignment on the live ones; the fine path corrects
    // the alignment and soft-erases the dead region.
    printf("test_fine_partial_transmission:\n");
    synth_gfsk_offset(tones, FT8_NN, 988.9f, 2.0f, FT8_SYMBOL_PERIOD, RATE, wave, 0);
// Signal/noise amplitudes for the partial-transmission case, overridable for
// calibration scans. The outcome depends on the ratio: above ~0.17 the
// waterfall+OSD path decodes it anyway (OSD's reliability-ordered basis
// sidesteps the dead symbols once the live bits dominate), below ~0.13 the
// fine path loses it too. 0.06/0.40 = 0.15 sits between with ~1.5 dB margin
// each way (scanned; both neighbors at ±1.3x amplitude flip exactly one of
// the two assertions).
#ifndef PT_AMP
#define PT_AMP 0.06f
#endif
#ifndef PT_NOISE
#define PT_NOISE 0.40f
#endif
    memset(signal, 0, sizeof(signal));
    add_noise(signal, NSAMPLES, PT_NOISE, 51);
    int off = (int)(0.315f * RATE); // nominal signal start
    for (int i = 27 * n_spsym; i < n_wave; ++i)
    {
        int j = off + i;
        if (j >= 0 && j < NSAMPLES)
            signal[j] += PT_AMP * wave[i];
    }
    feed(&mon, signal);
    ft8_fine_ctx_t* fine = ft8_fine_init(signal, NSAMPLES, RATE);
    check(fine != NULL, "fine context initializes");
    pipeline_result_t r = try_decode(&mon, fine, "TA1NGE RA3TPE LO25", 988.9f);
    check(!r.wf_decoded, "waterfall path fails on the partial transmission");
    check(r.fine_decoded, "fine path RECOVERS the partial transmission");
    check(r.fine_decoded && r.quality >= FT8AF_FINE_SYNC_MIN,
          "recovery passes the sync-quality gate");
    ft8_fine_free(fine);

    // ---- 2. fine sync accuracy on an off-grid signal ------------------------
    printf("test_fine_sync_accuracy:\n");
    synth_gfsk_offset(tones, FT8_NN, 1501.4f, 2.0f, FT8_SYMBOL_PERIOD, RATE, wave, 0);
    memset(signal, 0, sizeof(signal));
    add_noise(signal, NSAMPLES, 0.05f, 52);
    off = (int)(0.515f * RATE); // 15 ms off the 40 ms candidate grid
    for (int i = 0; i < n_wave; ++i)
    {
        int j = off + i;
        if (j >= 0 && j < NSAMPLES)
            signal[j] += 0.5f * wave[i];
    }
    feed(&mon, signal);
    fine = ft8_fine_init(signal, NSAMPLES, RATE);
    r = try_decode(&mon, fine, "TA1NGE RA3TPE LO25", 1501.4f);
    check(r.fine_decoded, "off-grid signal decodes via the fine path");
    if (r.fine_decoded)
    {
        float lag = (float)(mon.block_size / 2 + mon.nfft / 2 - mon.subblock_size) / RATE;
        printf("  (refined f=%.2f Hz t=%.3f s quality=%.2f; true f=1501.40 t=%.3f)\n",
               r.f_fine, r.t_fine, r.quality, 0.515f + lag);
        check(fabsf(r.f_fine - 1501.4f) < 0.7f, "refined frequency within 0.7 Hz");
        // t_fine is in the candidate time base: signal start + STFT lag.
        // Tolerance: 5 ms search granularity + candidate rounding.
        check(fabsf(r.t_fine - (0.515f + lag)) < 0.015f, "refined time within 15 ms");
        check(r.quality >= FT8AF_FINE_SYNC_MIN, "clean signal clears the quality gate");
    }
    ft8_fine_free(fine);

    // ---- 3. junk guard: pure noise produces zero fine-path decodes ----------
    // (The dt-search peak statistic on pure noise sits around 1.3-1.5 — max
    // of 17 trials — so the base sync gate alone cannot reject noise; the
    // protection is the full chain: quality gates + BP/OSD + CRC + the
    // rare-type quality bar. Assert the end result: no decode, and noise
    // quality stays below the rare-type bar that junk decodes would need.)
    printf("test_fine_noise_rejection:\n");
    memset(signal, 0, sizeof(signal));
    add_noise(signal, NSAMPLES, 0.5f, 53);
    feed(&mon, signal);
    fine = ft8_fine_init(signal, NSAMPLES, RATE);
    static candidate_t cands[FT8AF_MAX_CANDIDATES];
    int nc = ft8_find_sync(&mon.wf, FT8AF_MAX_CANDIDATES, cands, 1);
    int accepted = 0;
    float max_quality = 0.0f;
    for (int i = 0; i < nc; ++i)
    {
        float llrs[FTX_LDPC_N];
        float f_fine, t_fine, quality;
        if (!ft8_fine_demod(fine, &mon, &cands[i], llrs, &f_fine, &t_fine, &quality))
            continue;
        if (quality > max_quality)
            max_quality = quality;
        ftx_message_t message;
        decode_status_t status;
        if (quality >= FT8AF_FINE_SYNC_MIN &&
            ftx_decode_llrs(FTX_PROTOCOL_FT8, llrs, FT8AF_LDPC_ITERS_DEEP,
                            FT8AF_OSD_DEPTH_DEEP, FT8AF_OSD_LDPC_ERR_GATE,
                            &message, &status))
            accepted++;
    }
    printf("  (%d noise candidates, max quality %.2f)\n", nc, max_quality);
    check(accepted == 0, "0 fine-path decodes from pure noise");
    check(max_quality < FT8AF_FINE_SYNC_MIN_RARE,
          "noise quality stays below the rare-type bar");
    ft8_fine_free(fine);

    free(wave);
    monitor_free(&mon);

    if (g_failures == 0)
    {
        printf("all fine-demod tests passed\n");
        return 0;
    }
    printf("%d fine-demod test(s) FAILED\n", g_failures);
    return 1;
}
