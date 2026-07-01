// decode_bench.c — FT8 decode benchmark / regression harness (host-only).
//
// Replicates the app's FT8 RX decode pipeline — the same monitor config,
// candidate search, LDPC settings, dedup, and subtract-and-redecode loop that
// FT8SignalListener.decodeFt8 drives through ft8_decode_jni.cpp — against WAV
// recordings on the host, and scores the result against WSJT-X `jt9` truth
// sidecars (<name>.txt next to <name>.wav, jt9 output format).
//
// This is the measurement instrument for the decode-rate work: every decoder
// change gets a before/after recall number here before it ships. The committed
// floors file (testdata/ft8/floors.txt) is a ratchet: --assert-floor fails the
// run if any corpus file decodes fewer matched messages than its floor.
//
// Usage:
//   decode_bench [flags] <file.wav> [more.wav ...]
//     --fast              disable the deep + subtract passes (app default is deep)
//     --window-ms N       audio window fed to the decoder (default 13500, the
//                         app's earlyDecode window; use 15000 for the full slot)
//     --candidates N      sync candidate cap        (default: app value)
//     --min-score N       sync score threshold, fast pass (default: app value)
//     --min-score-deep N  sync score threshold, deep passes (default: app value)
//     --time-osr N        time oversampling         (default: app value)
//     --freq-osr N        frequency oversampling    (default: app value)
//     --ldpc-fast N       LDPC iterations, fast pass (default 20)
//     --ldpc-deep N       LDPC iterations, deep pass (default 30)
//     --max-loops N       subtract-and-redecode loop cap (default 16; the app
//                         caps by wall-clock budget instead)
//     --assert-floor F    read "name min_matched" lines from F; exit 1 if any
//                         listed file matches fewer truth messages than its floor
//     --self-test         synthesize a clean FT8 signal, decode it, expect 1/1
//     -v                  print each decoded / missed message
//
// Output per file (machine-parsable, one line):
//   RESULT <name> decoded=<n> matched=<n> truth=<n> recall=<pct> extra=<n> passes=<n> ms=<n>
// and an aggregate TOTAL line at the end.

#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <ft8/constants.h>
#include <ft8/decode.h>
#include <ft8/encode.h>
#include <ft8/message.h>

#include "common/monitor.h"
#include "common/wave.h"

#include "decode_params.h"

// From gfsk.c (glue): GFSK synth used by the --self-test signal generator.
void synth_gfsk_offset(const uint8_t* symbols, int n_sym, float f0,
                       float symbol_bt, float symbol_period,
                       int signal_rate, float* signal, int offset);

// ---------------------------------------------------------------------------
// Tunables (defaults == the app's values, via the shared decode_params.h).
// ---------------------------------------------------------------------------
static int g_max_candidates = FT8AF_MAX_CANDIDATES;
static int g_min_score_fast = FT8AF_MIN_SCORE_FAST;
static int g_min_score_deep = FT8AF_MIN_SCORE_DEEP;
static int g_time_osr = FT8AF_TIME_OSR;
static int g_freq_osr = FT8AF_FREQ_OSR;
static int g_ldpc_fast = FT8AF_LDPC_ITERS_FAST;
static int g_ldpc_deep = FT8AF_LDPC_ITERS_DEEP;
static int g_window_ms = 13500;
static int g_max_loops = 16;
static bool g_deep = true;
static bool g_verbose = false;

#define BENCH_MAX_CAND_CAP 1024
#define BENCH_MAX_MSGS 256
#define BENCH_MAX_TRUTH 64
#define BENCH_SAMPLE_RATE 12000
#define BENCH_MAX_SAMPLES (16 * 48000)

// ---------------------------------------------------------------------------
// Callsign hash table — same shape as the app's (ft8_decode_jni.cpp): 256
// entries keyed by the 22-bit hash, so <...> hashed calls resolve within a
// slot once the full call has been seen.
// ---------------------------------------------------------------------------
#define BENCH_HASHTABLE_SIZE 256
typedef struct
{
    char callsign[12];
    uint32_t hash; // 22-bit
} bench_hash_entry_t;

static bench_hash_entry_t g_hashtable[BENCH_HASHTABLE_SIZE];

static void bench_hash_reset(void)
{
    memset(g_hashtable, 0, sizeof(g_hashtable));
}

static bool bench_hash_lookup(ftx_callsign_hash_type_e hash_type, uint32_t hash, char* callsign)
{
    for (int i = 0; i < BENCH_HASHTABLE_SIZE; ++i)
    {
        if (g_hashtable[i].callsign[0] == '\0')
            continue;
        uint32_t h22 = g_hashtable[i].hash;
        uint32_t cmp = (hash_type == FTX_CALLSIGN_HASH_22_BITS) ? h22
                     : (hash_type == FTX_CALLSIGN_HASH_12_BITS) ? (h22 >> 10)
                                                                : (h22 >> 12);
        if (cmp == hash)
        {
            strcpy(callsign, g_hashtable[i].callsign);
            return true;
        }
    }
    return false;
}

static void bench_hash_save(const char* callsign, uint32_t n22)
{
    bench_hash_entry_t* e = &g_hashtable[n22 % BENCH_HASHTABLE_SIZE];
    snprintf(e->callsign, sizeof(e->callsign), "%s", callsign);
    e->hash = n22;
}

static ftx_callsign_hash_interface_t g_hash_if = { bench_hash_lookup, bench_hash_save };

// ---------------------------------------------------------------------------
// Message text handling.
// ---------------------------------------------------------------------------

// Collapse runs of whitespace to single spaces and trim; uppercase for
// comparison stability (jt9 and ft8_lib both emit uppercase already).
static void normalize_text(char* s)
{
    char out[64];
    int o = 0;
    bool in_space = true; // trims leading space
    for (const char* p = s; *p && o < (int)sizeof(out) - 1; ++p)
    {
        if (*p == ' ' || *p == '\t')
        {
            if (!in_space && o > 0)
                out[o++] = ' ';
            in_space = true;
        }
        else
        {
            out[o++] = *p;
            in_space = false;
        }
    }
    while (o > 0 && out[o - 1] == ' ')
        --o;
    out[o] = '\0';
    strcpy(s, out);
}

typedef struct
{
    char text[64];
} bench_msg_t;

typedef struct
{
    bench_msg_t items[BENCH_MAX_MSGS];
    int count;
} bench_msglist_t;

static bool msglist_contains(const bench_msglist_t* list, const char* text)
{
    for (int i = 0; i < list->count; ++i)
        if (strcmp(list->items[i].text, text) == 0)
            return true;
    return false;
}

static void msglist_add(bench_msglist_t* list, const char* text)
{
    if (list->count < BENCH_MAX_MSGS)
    {
        snprintf(list->items[list->count].text, sizeof(list->items[0].text), "%s", text);
        list->count++;
    }
}

// Per-pass record of every successful decode (pre-dedup), mirroring the app's
// a91List: each entry is subtracted from the waterfall before the next pass.
typedef struct
{
    float freq_hz;
    float time_sec;
    uint8_t payload[FTX_PAYLOAD_LENGTH_BYTES];
} bench_decode_t;

typedef struct
{
    bench_decode_t items[BENCH_MAX_MSGS];
    int count;
} bench_declist_t;

// ---------------------------------------------------------------------------
// Baseline subtraction — byte-for-byte the app's current behavior
// (Java_..._ReBuildSignal_doSubtractSignal in ft8_decode_jni.cpp): zero the
// waterfall magnitudes ±4 bins around the signal for all 79 symbols, across
// every time/freq oversampling plane. The decoded payload is ignored. This is
// deliberately replicated here so the committed baseline matches the shipped
// app; PR "subtract-v2" swaps both sides to a shared implementation.
// ---------------------------------------------------------------------------
static void subtract_signal_baseline(monitor_t* mon, const bench_decode_t* dec)
{
    waterfall_t* wf = &mon->wf;
    if (!wf->mag || wf->num_blocks <= 0)
        return;

    int freq_offset = (int)lrintf(dec->freq_hz * mon->symbol_period) - mon->min_bin;
    int time_offset = (int)lrintf(dec->time_sec / mon->symbol_period);

    const int kFreqHalfWidth = 4;
    const int kNumSymbols = FT8_NN;

    for (int blk = time_offset; blk < time_offset + kNumSymbols && blk < wf->num_blocks; ++blk)
    {
        if (blk < 0)
            continue;
        uint8_t* block_base = wf->mag + (size_t)blk * wf->block_stride;
        for (int ts = 0; ts < wf->time_osr; ++ts)
        {
            for (int fs = 0; fs < wf->freq_osr; ++fs)
            {
                uint8_t* row = block_base + (ts * wf->freq_osr + fs) * wf->num_bins;
                for (int b = freq_offset - kFreqHalfWidth; b <= freq_offset + kFreqHalfWidth; ++b)
                {
                    if (b >= 0 && b < wf->num_bins)
                        row[b] = 0;
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// One decode pass: find_sync + per-candidate decode, mirroring runDecode() in
// FT8SignalListener.java. Every successful decode is recorded in pass_decs
// (for subtraction); only messages not already in seen are new.
// Returns the number of NEW messages.
// ---------------------------------------------------------------------------
static int run_pass(monitor_t* mon, int ldpc_iters, int min_score, bench_msglist_t* seen,
                    bench_declist_t* pass_decs)
{
    static candidate_t candidates[BENCH_MAX_CAND_CAP];
    pass_decs->count = 0;

    int num_candidates = ft8_find_sync(&mon->wf, g_max_candidates, candidates, min_score);
    int new_msgs = 0;

    for (int idx = 0; idx < num_candidates; ++idx)
    {
        const candidate_t* cand = &candidates[idx];
        ftx_message_t message;
        decode_status_t status;
        if (!ft8_decode(&mon->wf, cand, ldpc_iters, &message, &status))
            continue;

        float freq_hz = (mon->min_bin + cand->freq_offset +
                         (float)cand->freq_sub / mon->wf.freq_osr) / mon->symbol_period;
        float time_sec = (cand->time_offset +
                          (float)cand->time_sub / mon->wf.time_osr) * mon->symbol_period;

        // Decode to text the same way DecoderFt8Analysis assembles it.
        char text[64] = { 0 };
        ftx_message_type_t type = ftx_message_get_type(&message);
        bool ok = false;
        if (type == FTX_MESSAGE_TYPE_FREE_TEXT)
        {
            char free_text[FTX_MAX_MESSAGE_LENGTH] = { 0 };
            ftx_message_decode_free(&message, free_text);
            snprintf(text, sizeof(text), "%s", free_text);
            ok = true;
        }
        else if (type == FTX_MESSAGE_TYPE_STANDARD || type == FTX_MESSAGE_TYPE_NONSTD_CALL)
        {
            char call_to[20] = { 0 }, call_de[20] = { 0 }, extra[20] = { 0 };
            ftx_message_rc_t rc = (type == FTX_MESSAGE_TYPE_STANDARD)
                ? ftx_message_decode_std(&message, &g_hash_if, call_to, call_de, extra)
                : ftx_message_decode_nonstd(&message, &g_hash_if, call_to, call_de, extra);
            if (rc == FTX_MESSAGE_RC_OK)
            {
                snprintf(text, sizeof(text), "%s %s %s", call_to, call_de, extra);
                ok = true;
            }
        }
        else
        {
            char full[FTX_MAX_MESSAGE_LENGTH] = { 0 };
            if (ftx_message_decode(&message, &g_hash_if, full) == FTX_MESSAGE_RC_OK)
            {
                snprintf(text, sizeof(text), "%s", full);
                ok = true;
            }
        }
        if (!ok)
            continue;
        normalize_text(text);
        if (text[0] == '\0')
            continue;

        // a91List semantics: record EVERY successful decode for subtraction,
        // including duplicates of already-seen messages.
        if (pass_decs->count < BENCH_MAX_MSGS)
        {
            bench_decode_t* dec = &pass_decs->items[pass_decs->count++];
            dec->freq_hz = freq_hz;
            dec->time_sec = time_sec;
            memcpy(dec->payload, message.payload, FTX_PAYLOAD_LENGTH_BYTES);
        }

        if (msglist_contains(seen, text))
            continue;
        msglist_add(seen, text);
        new_msgs++;
        if (g_verbose)
            printf("  + %7.1f Hz %5.2f s  %s\n", freq_hz, time_sec, text);
    }
    return new_msgs;
}

// ---------------------------------------------------------------------------
// Full pipeline over one audio buffer, mirroring decodeFt8():
// fast pass -> deep pass -> (subtract, deep pass) until no new messages.
// Returns total pass count via *passes_out.
// ---------------------------------------------------------------------------
static void decode_buffer(const float* signal, int num_samples,
                          bench_msglist_t* seen, int* passes_out)
{
    monitor_config_t cfg;
    cfg.f_min = 100;
    cfg.f_max = 3500;
    cfg.sample_rate = BENCH_SAMPLE_RATE;
    cfg.time_osr = g_time_osr;
    cfg.freq_osr = g_freq_osr;
    cfg.protocol = FTX_PROTOCOL_FT8;

    monitor_t mon;
    monitor_init(&mon, &cfg);
    monitor_reset(&mon);
    for (int pos = 0; pos + mon.block_size <= num_samples; pos += mon.block_size)
        monitor_process(&mon, signal + pos);

    bench_hash_reset();
    static bench_declist_t pass_decs;
    int passes = 0;

    run_pass(&mon, g_ldpc_fast, g_min_score_fast, seen, &pass_decs);
    passes++;

    if (g_deep)
    {
        int new_msgs = run_pass(&mon, g_ldpc_deep, g_min_score_deep, seen, &pass_decs);
        passes++;
        for (int loop = 0; loop < g_max_loops; ++loop)
        {
            for (int i = 0; i < pass_decs.count; ++i)
                subtract_signal_baseline(&mon, &pass_decs.items[i]);
            new_msgs = run_pass(&mon, g_ldpc_deep, g_min_score_deep, seen, &pass_decs);
            passes++;
            if (new_msgs == 0)
                break;
        }
    }

    monitor_free(&mon);
    *passes_out = passes;
}

// ---------------------------------------------------------------------------
// Truth files: WSJT-X jt9 output. "HHMMSS SNR DT FREQ ~  MESSAGE   [country]".
// The message runs from after the mode marker to the first run of 3+ spaces
// (jt9 pads country annotations into a fixed column).
// ---------------------------------------------------------------------------
static int load_truth(const char* wav_path, bench_msg_t truth[], int max_truth)
{
    char path[1024];
    snprintf(path, sizeof(path), "%s", wav_path);
    char* dot = strrchr(path, '.');
    if (!dot)
        return -1;
    strcpy(dot, ".txt");

    FILE* f = fopen(path, "r");
    if (!f)
        return -1;

    int count = 0;
    char line[256];
    while (fgets(line, sizeof(line), f) && count < max_truth)
    {
        char* tilde = strchr(line, '~');
        if (!tilde)
            continue;
        char* msg = tilde + 1;
        while (*msg == ' ')
            ++msg;
        // Cut at country-annotation column (3+ consecutive spaces) or newline.
        char* cut = strstr(msg, "   ");
        if (cut)
            *cut = '\0';
        msg[strcspn(msg, "\r\n")] = '\0';
        normalize_text(msg);
        if (msg[0] == '\0')
            continue;
        // Dedupe: jt9 can report the same message text at two frequencies, but
        // the app (and this bench) dedup decodes by text, so truth must be a
        // set of unique texts or matched could exceed decoded.
        bool dup = false;
        for (int i = 0; i < count && !dup; ++i)
            dup = (strcmp(truth[i].text, msg) == 0);
        if (dup)
            continue;
        snprintf(truth[count].text, sizeof(truth[0].text), "%s", msg);
        count++;
    }
    fclose(f);
    return count;
}

static const char* base_name(const char* path)
{
    const char* b = path;
    for (const char* p = path; *p; ++p)
        if (*p == '/' || *p == '\\')
            b = p + 1;
    return b;
}

// ---------------------------------------------------------------------------
// Floors: "name min_matched" lines; '#' comments allowed.
// ---------------------------------------------------------------------------
typedef struct
{
    char name[128];
    int floor;
} bench_floor_t;

static bench_floor_t g_floors[256];
static int g_num_floors = 0;

static int load_floors(const char* path)
{
    FILE* f = fopen(path, "r");
    if (!f)
    {
        fprintf(stderr, "ERROR: cannot open floors file %s\n", path);
        return -1;
    }
    char line[256];
    while (fgets(line, sizeof(line), f) && g_num_floors < 256)
    {
        if (line[0] == '#' || line[0] == '\n' || line[0] == '\r')
            continue;
        bench_floor_t* fl = &g_floors[g_num_floors];
        if (sscanf(line, "%127s %d", fl->name, &fl->floor) == 2)
            g_num_floors++;
    }
    fclose(f);
    return 0;
}

static int floor_for(const char* name)
{
    for (int i = 0; i < g_num_floors; ++i)
        if (strcmp(g_floors[i].name, name) == 0)
            return g_floors[i].floor;
    return -1;
}

// ---------------------------------------------------------------------------
// Self-test: encode + synthesize a clean FT8 signal, decode it back.
// ---------------------------------------------------------------------------
static int self_test(void)
{
    const char* text = "CQ K1ABC FN42";
    ftx_message_t msg;
    ftx_message_init(&msg);
    if (ftx_message_encode(&msg, &g_hash_if, text) != FTX_MESSAGE_RC_OK)
    {
        fprintf(stderr, "SELF-TEST FAIL: encode of '%s' failed\n", text);
        return 1;
    }
    uint8_t tones[FT8_NN];
    ft8_encode(msg.payload, tones);

    int num_samples = g_window_ms * BENCH_SAMPLE_RATE / 1000;
    float* signal = (float*)calloc(num_samples, sizeof(float));
    if (!signal)
        return 1;
    // 0.5 s in, 1200 Hz — well inside the analysis band.
    synth_gfsk_offset(tones, FT8_NN, 1200.0f, 2.0f, FT8_SYMBOL_PERIOD,
                      BENCH_SAMPLE_RATE, signal, BENCH_SAMPLE_RATE / 2);

    bench_msglist_t seen;
    seen.count = 0;
    int passes = 0;
    decode_buffer(signal, num_samples, &seen, &passes);
    free(signal);

    if (seen.count == 1 && strcmp(seen.items[0].text, text) == 0)
    {
        printf("SELF-TEST OK: decoded '%s'\n", seen.items[0].text);
        return 0;
    }
    fprintf(stderr, "SELF-TEST FAIL: expected 1 decode '%s', got %d", text, seen.count);
    for (int i = 0; i < seen.count; ++i)
        fprintf(stderr, " '%s'", seen.items[i].text);
    fprintf(stderr, "\n");
    return 1;
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------
int main(int argc, char** argv)
{
    const char* floors_path = NULL;
    bool do_self_test = false;
    const char* wavs[512];
    int num_wavs = 0;

    for (int i = 1; i < argc; ++i)
    {
        const char* a = argv[i];
        if (strcmp(a, "--fast") == 0)
            g_deep = false;
        else if (strcmp(a, "-v") == 0)
            g_verbose = true;
        else if (strcmp(a, "--self-test") == 0)
            do_self_test = true;
        else if (strcmp(a, "--window-ms") == 0 && i + 1 < argc)
            g_window_ms = atoi(argv[++i]);
        else if (strcmp(a, "--candidates") == 0 && i + 1 < argc)
            g_max_candidates = atoi(argv[++i]);
        else if (strcmp(a, "--min-score") == 0 && i + 1 < argc)
            g_min_score_fast = atoi(argv[++i]);
        else if (strcmp(a, "--min-score-deep") == 0 && i + 1 < argc)
            g_min_score_deep = atoi(argv[++i]);
        else if (strcmp(a, "--time-osr") == 0 && i + 1 < argc)
            g_time_osr = atoi(argv[++i]);
        else if (strcmp(a, "--freq-osr") == 0 && i + 1 < argc)
            g_freq_osr = atoi(argv[++i]);
        else if (strcmp(a, "--ldpc-fast") == 0 && i + 1 < argc)
            g_ldpc_fast = atoi(argv[++i]);
        else if (strcmp(a, "--ldpc-deep") == 0 && i + 1 < argc)
            g_ldpc_deep = atoi(argv[++i]);
        else if (strcmp(a, "--max-loops") == 0 && i + 1 < argc)
            g_max_loops = atoi(argv[++i]);
        else if (strcmp(a, "--assert-floor") == 0 && i + 1 < argc)
            floors_path = argv[++i];
        else if (a[0] == '-')
        {
            fprintf(stderr, "ERROR: unknown flag %s\n", a);
            return 2;
        }
        else if (num_wavs < 512)
            wavs[num_wavs++] = a;
    }

    if (g_max_candidates > BENCH_MAX_CAND_CAP)
    {
        fprintf(stderr, "ERROR: --candidates capped at %d\n", BENCH_MAX_CAND_CAP);
        return 2;
    }

    if (do_self_test)
        return self_test();

    if (num_wavs == 0)
    {
        fprintf(stderr, "usage: decode_bench [flags] <file.wav> ...   (see header comment)\n");
        return 2;
    }
    if (floors_path && load_floors(floors_path) != 0)
        return 2;

    static float signal[BENCH_MAX_SAMPLES];
    int total_decoded = 0, total_matched = 0, total_truth = 0, total_extra = 0;
    int floor_failures = 0;

    for (int w = 0; w < num_wavs; ++w)
    {
        int num_samples = BENCH_MAX_SAMPLES; // in: capacity, out: samples read
        int sample_rate = 0;
        if (load_wav(signal, &num_samples, &sample_rate, wavs[w]) != 0)
        {
            fprintf(stderr, "ERROR: cannot read %s\n", wavs[w]);
            return 2;
        }
        if (sample_rate != BENCH_SAMPLE_RATE)
        {
            fprintf(stderr, "ERROR: %s is %d Hz; corpus must be %d Hz mono\n",
                    wavs[w], sample_rate, BENCH_SAMPLE_RATE);
            return 2;
        }
        int window_samples = (int)((int64_t)g_window_ms * sample_rate / 1000);
        if (window_samples < num_samples)
            num_samples = window_samples;

        bench_msglist_t seen;
        seen.count = 0;
        int passes = 0;
        clock_t t0 = clock();
        decode_buffer(signal, num_samples, &seen, &passes);
        long ms = (long)((clock() - t0) * 1000L / CLOCKS_PER_SEC);

        bench_msg_t truth[BENCH_MAX_TRUTH];
        int num_truth = load_truth(wavs[w], truth, BENCH_MAX_TRUTH);

        const char* name = base_name(wavs[w]);
        if (num_truth < 0)
        {
            printf("RESULT %s decoded=%d matched=- truth=- recall=- extra=- passes=%d ms=%ld\n",
                   name, seen.count, passes, ms);
            total_decoded += seen.count;
            continue;
        }

        int matched = 0;
        for (int t = 0; t < num_truth; ++t)
        {
            if (msglist_contains(&seen, truth[t].text))
                matched++;
            else if (g_verbose)
                printf("  - MISSED  %s\n", truth[t].text);
        }
        int extra = seen.count - matched;

        printf("RESULT %s decoded=%d matched=%d truth=%d recall=%.1f extra=%d passes=%d ms=%ld\n",
               name, seen.count, matched, num_truth,
               num_truth > 0 ? 100.0 * matched / num_truth : 0.0, extra, passes, ms);

        total_decoded += seen.count;
        total_matched += matched;
        total_truth += num_truth;
        total_extra += extra;

        if (floors_path)
        {
            int fl = floor_for(name);
            if (fl >= 0 && matched < fl)
            {
                fprintf(stderr, "FLOOR FAIL %s: matched %d < floor %d\n", name, matched, fl);
                floor_failures++;
            }
        }
    }

    printf("TOTAL decoded=%d matched=%d truth=%d recall=%.1f extra=%d\n",
           total_decoded, total_matched, total_truth,
           total_truth > 0 ? 100.0 * total_matched / total_truth : 0.0, total_extra);

    if (floor_failures > 0)
    {
        fprintf(stderr, "%d file(s) below floor\n", floor_failures);
        return 1;
    }
    return 0;
}
