// Host unit tests for ft8_subtract.c — the tone-accurate, noise-floor-
// referenced deep-decode subtraction. Run by run_host_tests.sh / .ps1.
//
// Properties verified (each was violated by the old ±4-bin zeroing, except 1):
//   1. Subtracting a decoded signal prevents its re-decode on the next pass.
//   2. A neighbor signal outside the subtracted signal's tone track survives.
//   3. Co-channel recovery: a weak signal masked by a strong one 25 Hz away
//      decodes after the strong one is subtracted — and does NOT with the
//      legacy zeroing (the regression the rewrite exists to fix).
//   4. Subtracting with a wrong payload leaves an actual signal decodable
//      (damage is bounded to the wrong tone track).

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
#include "ft8_subtract.h"

void synth_gfsk_offset(const uint8_t* symbols, int n_sym, float f0,
                       float symbol_bt, float symbol_period,
                       int signal_rate, float* signal, int offset);

#define RATE 12000
#define NSAMPLES (RATE * 15)

static int g_failures = 0;

static void check(bool ok, const char* what)
{
    printf("  %s %s\n", ok ? "ok:  " : "FAIL:", what);
    if (!ok)
        g_failures++;
}

// Deterministic uniform noise in [-amp, amp] (LCG; no libc rand variance).
static void add_noise(float* signal, int n, float amp, uint32_t seed)
{
    uint32_t s = seed;
    for (int i = 0; i < n; ++i)
    {
        s = s * 1664525u + 1013904223u;
        signal[i] += amp * (2.0f * ((s >> 8) & 0xFFFF) / 65535.0f - 1.0f);
    }
}

// Encode `text` and mix its GFSK waveform into signal at (f0, dt, amp).
// Returns the 10-byte payload in payload_out (for subtraction calls).
static bool add_message(float* signal, const char* text, float f0, float dt,
                        float amp, uint8_t payload_out[10])
{
    ftx_message_t msg;
    ftx_message_init(&msg);
    if (ftx_message_encode(&msg, NULL, text) != FTX_MESSAGE_RC_OK)
        return false;
    memcpy(payload_out, msg.payload, 10);

    uint8_t tones[FT8_NN];
    ft8_encode(msg.payload, tones);

    int n_wave = FT8_NN * (RATE * 160 / 1000);
    float* wave = (float*)calloc(n_wave, sizeof(float));
    if (!wave)
        return false;
    synth_gfsk_offset(tones, FT8_NN, f0, 2.0f, FT8_SYMBOL_PERIOD, RATE, wave, 0);

    int off = (int)(dt * RATE);
    for (int i = 0; i < n_wave; ++i)
    {
        int j = off + i;
        if (j >= 0 && j < NSAMPLES)
            signal[j] += amp * wave[i];
    }
    free(wave);
    return true;
}

static void feed(monitor_t* mon, const float* signal)
{
    monitor_reset(mon);
    for (int pos = 0; pos + mon->block_size <= NSAMPLES; pos += mon->block_size)
        monitor_process(mon, signal + pos);
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

// Decode every candidate; append unique decoded texts (and their geometry)
// to out[]. Returns count.
#define MAX_OUT 16
typedef struct
{
    char text[64];
    float freq_hz;
    float time_sec;
    uint8_t payload[10];
} decoded_t;

static int decode_all(monitor_t* mon, decoded_t out[MAX_OUT])
{
    static candidate_t cands[FT8AF_MAX_CANDIDATES];
    int n = ft8_find_sync(&mon->wf, FT8AF_MAX_CANDIDATES, cands, FT8AF_MIN_SCORE_DEEP);
    int count = 0;
    for (int i = 0; i < n && count < MAX_OUT; ++i)
    {
        ftx_message_t message;
        decode_status_t status;
        if (!ft8_decode(&mon->wf, &cands[i], FT8AF_LDPC_ITERS_DEEP, &message, &status))
            continue;
        char text[64] = { 0 };
        if (ftx_message_decode(&message, NULL, text) != FTX_MESSAGE_RC_OK)
            continue;
        bool dup = false;
        for (int j = 0; j < count && !dup; ++j)
            dup = (strcmp(out[j].text, text) == 0);
        if (dup)
            continue;
        snprintf(out[count].text, sizeof(out[0].text), "%s", text);
        out[count].freq_hz = (mon->min_bin + cands[i].freq_offset +
                              (float)cands[i].freq_sub / mon->wf.freq_osr) / mon->symbol_period;
        out[count].time_sec = (cands[i].time_offset +
                               (float)cands[i].time_sub / mon->wf.time_osr) * mon->symbol_period;
        memcpy(out[count].payload, message.payload, 10);
        count++;
    }
    return count;
}

static const decoded_t* find_text(const decoded_t* list, int n, const char* text)
{
    for (int i = 0; i < n; ++i)
        if (strcmp(list[i].text, text) == 0)
            return &list[i];
    return NULL;
}

// The legacy zeroing, for the contrast assertion in test 3.
static void legacy_zeroing(monitor_t* mon, float freq_hz, float time_sec)
{
    waterfall_t* wf = &mon->wf;
    int freq_offset = (int)lrintf(freq_hz * mon->symbol_period) - mon->min_bin;
    int time_offset = (int)lrintf(time_sec / mon->symbol_period);
    for (int blk = time_offset; blk < time_offset + FT8_NN && blk < wf->num_blocks; ++blk)
    {
        if (blk < 0)
            continue;
        uint8_t* block_base = wf->mag + (size_t)blk * wf->block_stride;
        for (int ts = 0; ts < wf->time_osr; ++ts)
            for (int fs = 0; fs < wf->freq_osr; ++fs)
            {
                uint8_t* row = block_base + (ts * wf->freq_osr + fs) * wf->num_bins;
                for (int b = freq_offset - 4; b <= freq_offset + 4; ++b)
                    if (b >= 0 && b < wf->num_bins)
                        row[b] = 0;
            }
    }
}

static const char* MSG_A = "CQ K1ABC FN42";
static const char* MSG_B = "CQ W9XYZ EN37";

int main(void)
{
    static float signal[NSAMPLES];
    monitor_t mon;
    monitor_setup(&mon);
    decoded_t out[MAX_OUT];
    uint8_t pay_a[10], pay_b[10];

    // ---- 1. subtraction removes the decoded signal --------------------------
    printf("test_subtract_removes_signal:\n");
    memset(signal, 0, sizeof(signal));
    add_noise(signal, NSAMPLES, 0.02f, 42);
    add_message(signal, MSG_A, 1500.0f, 0.5f, 1.0f, pay_a);
    feed(&mon, signal);
    int n = decode_all(&mon, out);
    const decoded_t* a = find_text(out, n, MSG_A);
    check(a != NULL, "signal decodes before subtraction");
    if (a)
    {
        ft8_subtract_signal(&mon, a->payload, a->freq_hz, a->time_sec);
        n = decode_all(&mon, out);
        check(find_text(out, n, MSG_A) == NULL, "signal gone after subtraction");
    }

    // ---- 2. neighbor outside the tone track survives ------------------------
    printf("test_neighbor_survives:\n");
    memset(signal, 0, sizeof(signal));
    add_noise(signal, NSAMPLES, 0.02f, 43);
    add_message(signal, MSG_A, 1500.0f, 0.5f, 1.0f, pay_a);
    add_message(signal, MSG_B, 1600.0f, 0.5f, 0.5f, pay_b);
    feed(&mon, signal);
    n = decode_all(&mon, out);
    a = find_text(out, n, MSG_A);
    check(a != NULL && find_text(out, n, MSG_B) != NULL, "both decode initially");
    if (a)
    {
        ft8_subtract_signal(&mon, a->payload, a->freq_hz, a->time_sec);
        n = decode_all(&mon, out);
        check(find_text(out, n, MSG_B) != NULL, "neighbor still decodes after subtracting A");
        check(find_text(out, n, MSG_A) == NULL, "A itself is gone");
    }

    // ---- 3. masked co-channel recovery (the money test) ---------------------
    // B sits 25 Hz above A (inside A's ~50 Hz tone span AND the legacy ±25 Hz
    // zeroing zone), 10 dB weaker: masked in pass 1. Subtract-and-redecode
    // must recover it — and must NOT with the legacy zeroing. This is the
    // exact mechanism behind the corpus recall gain.
    printf("test_masked_cochannel_recovery:\n");
    memset(signal, 0, sizeof(signal));
    add_noise(signal, NSAMPLES, 0.02f, 44);
    add_message(signal, MSG_A, 1500.0f, 0.2f, 1.0f, pay_a);
    add_message(signal, MSG_B, 1525.0f, 0.2f, 0.3f, pay_b);
    feed(&mon, signal);
    n = decode_all(&mon, out);
    a = find_text(out, n, MSG_A);
    check(a != NULL, "strong signal decodes in pass 1");
    check(find_text(out, n, MSG_B) == NULL, "weak co-channel signal is masked in pass 1");
    if (a)
    {
        // Contrast: legacy zeroing of A does NOT uncover B (it wipes B too).
        legacy_zeroing(&mon, a->freq_hz, a->time_sec);
        n = decode_all(&mon, out);
        check(find_text(out, n, MSG_B) == NULL, "legacy zeroing fails to recover the masked signal");

        // Re-feed (zeroing damaged the waterfall) and subtract properly.
        feed(&mon, signal);
        n = decode_all(&mon, out);
        a = find_text(out, n, MSG_A);
        check(a != NULL, "strong signal decodes again on fresh waterfall");
        if (a)
        {
            ft8_subtract_signal(&mon, a->payload, a->freq_hz, a->time_sec);
            n = decode_all(&mon, out);
            check(find_text(out, n, MSG_B) != NULL, "masked signal RECOVERED after tone-accurate subtraction");
            check(find_text(out, n, MSG_A) == NULL, "A itself is gone");
        }
    }

    // ---- 4. wrong subtraction elsewhere leaves a real signal decodable ------
    // (At a signal's own freq/time, ANY payload's subtraction flattens its
    // Costas sync track — all FT8 messages share it — so same-position damage
    // is inherent. The guarantee worth having: a bogus subtraction elsewhere
    // in the band cannot destroy an unrelated signal.)
    printf("test_wrong_subtraction_bounded:\n");
    memset(signal, 0, sizeof(signal));
    add_noise(signal, NSAMPLES, 0.02f, 45);
    add_message(signal, MSG_A, 1500.0f, 0.5f, 0.5f, pay_a);
    feed(&mon, signal);
    n = decode_all(&mon, out);
    a = find_text(out, n, MSG_A);
    check(a != NULL, "signal decodes before wrong subtraction");
    if (a)
    {
        ftx_message_t wrong;
        ftx_message_init(&wrong);
        ftx_message_encode(&wrong, NULL, MSG_B);
        // Phantom subtraction 300 Hz above the real signal.
        ft8_subtract_signal(&mon, wrong.payload, a->freq_hz + 300.0f, a->time_sec);
        n = decode_all(&mon, out);
        check(find_text(out, n, MSG_A) != NULL, "signal survives a phantom subtraction 300 Hz away");
    }

    monitor_free(&mon);

    if (g_failures == 0)
    {
        printf("all subtract tests passed\n");
        return 0;
    }
    printf("%d subtract test(s) FAILED\n", g_failures);
    return 1;
}
