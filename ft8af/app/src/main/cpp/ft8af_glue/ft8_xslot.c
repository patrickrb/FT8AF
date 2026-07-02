// See ft8_xslot.h.

#include "ft8_xslot.h"

#include <math.h>
#include <string.h>

#include "ft8/crc.h"

#include "decode_params.h"

#define XSLOT_SLOT_MS 15000

// Mechanism A entries: one candidate's failed LLRs. ~64 is far beyond the
// number of undecoded-but-real candidates in one slot; a busy band's junk
// candidates never get stored (the caller gates on fine sync quality).
#define XSLOT_MAX_ENTRIES 64

// Mechanism B entries: recently decoded messages. A busy slot decodes ~30
// messages and CQers repeat for minutes; 96 covers two full slots of
// distinct traffic.
#define XSLOT_MAX_HISTORY 96

typedef struct
{
    bool used;
    float freq_hz;
    int64_t utc_ms;
    float llrs[FTX_LDPC_N];
} xslot_entry_t;

typedef struct
{
    bool used;
    uint8_t payload[10];
    float freq_hz;
    int64_t utc_ms;
    // Codeword signs (+1 bit 1 / -1 bit 0), precomputed at remember() time:
    // the history test runs per failed candidate in the decode hot path and
    // must not regenerate the 83 LDPC parity bits per entry per call.
    float sign[FTX_LDPC_N];
} xslot_hist_t;

static xslot_entry_t g_entries[XSLOT_MAX_ENTRIES];
static xslot_hist_t g_history[XSLOT_MAX_HISTORY];

static void codeword_signs(const uint8_t payload[10], float sign[FTX_LDPC_N]);

static int slot_parity(int64_t utc_ms)
{
    return (int)((utc_ms / XSLOT_SLOT_MS) & 1);
}

// Entry lifetime check that also treats timestamps from the "future" as
// expired: a backwards clock step (NTP correction, user adjustment) must
// not pin entries in the cache until array churn evicts them.
static bool expired(int64_t now_ms, int64_t entry_ms, int64_t max_age_ms)
{
    int64_t age = now_ms - entry_ms;
    return age < 0 || age > max_age_ms;
}

void ft8_xslot_clear(void)
{
    memset(g_entries, 0, sizeof(g_entries));
    memset(g_history, 0, sizeof(g_history));
}

void ft8_xslot_store(float freq_hz, int64_t utc_ms, const float llrs[FTX_LDPC_N])
{
    if (!llrs)
        return;
    int parity = slot_parity(utc_ms);
    int slot = -1;
    // Reuse a matching same-parity entry (newest attempt wins), else an
    // expired/free one, else the oldest.
    int64_t oldest = INT64_MAX;
    int oldest_i = 0;
    for (int i = 0; i < XSLOT_MAX_ENTRIES; ++i)
    {
        if (g_entries[i].used)
        {
            if (slot_parity(g_entries[i].utc_ms) == parity &&
                fabsf(g_entries[i].freq_hz - freq_hz) <= FT8AF_XSLOT_FREQ_TOL)
            {
                slot = i;
                break;
            }
            if (expired(utc_ms, g_entries[i].utc_ms, FT8AF_XSLOT_MAX_AGE_MS))
            {
                g_entries[i].used = false;
                if (slot < 0)
                    slot = i;
            }
            else if (g_entries[i].utc_ms < oldest)
            {
                oldest = g_entries[i].utc_ms;
                oldest_i = i;
            }
        }
        else if (slot < 0)
        {
            slot = i;
        }
    }
    if (slot < 0)
        slot = oldest_i;

    g_entries[slot].used = true;
    g_entries[slot].freq_hz = freq_hz;
    g_entries[slot].utc_ms = utc_ms;
    memcpy(g_entries[slot].llrs, llrs, sizeof(g_entries[slot].llrs));
}

bool ft8_xslot_try_accumulate(float freq_hz, int64_t utc_ms, const float llrs[FTX_LDPC_N],
                              int max_iterations, int osd_depth, int osd_err_gate,
                              ftx_message_t* message, decode_status_t* status)
{
    if (!llrs || !message || !status)
        return false;
    int parity = slot_parity(utc_ms);
    for (int i = 0; i < XSLOT_MAX_ENTRIES; ++i)
    {
        xslot_entry_t* e = &g_entries[i];
        if (!e->used)
            continue;
        int64_t age = utc_ms - e->utc_ms;
        if (age <= 0 || age > FT8AF_XSLOT_MAX_AGE_MS)
            continue; // same slot, from the future, or stale
        if (slot_parity(e->utc_ms) != parity)
            continue;
        if (fabsf(e->freq_hz - freq_hz) > FT8AF_XSLOT_FREQ_TOL)
            continue;

        // Sum of independent LLRs for the same repeated message is the
        // optimal non-coherent combine (~3 dB for two repeats). A different
        // message underneath just fails the CRC.
        float combined[FTX_LDPC_N];
        for (int k = 0; k < FTX_LDPC_N; ++k)
            combined[k] = e->llrs[k] + llrs[k];

        if (ftx_decode_llrs(FTX_PROTOCOL_FT8, combined, max_iterations,
                            osd_depth, osd_err_gate, message, status))
        {
            e->used = false; // consumed — don't decode it twice
            return true;
        }
    }
    return false;
}

void ft8_xslot_remember(const uint8_t payload[10], float freq_hz, int64_t utc_ms)
{
    if (!payload)
        return;
    int slot = -1;
    int64_t oldest = INT64_MAX;
    int oldest_i = 0;
    for (int i = 0; i < XSLOT_MAX_HISTORY; ++i)
    {
        if (g_history[i].used)
        {
            if (memcmp(g_history[i].payload, payload, 10) == 0)
            {
                slot = i; // refresh
                break;
            }
            if (expired(utc_ms, g_history[i].utc_ms, FT8AF_XSLOT_HIST_MAX_AGE_MS))
            {
                g_history[i].used = false;
                if (slot < 0)
                    slot = i;
            }
            else if (g_history[i].utc_ms < oldest)
            {
                oldest = g_history[i].utc_ms;
                oldest_i = i;
            }
        }
        else if (slot < 0)
        {
            slot = i;
        }
    }
    if (slot < 0)
        slot = oldest_i;

    g_history[slot].used = true;
    memcpy(g_history[slot].payload, payload, 10);
    g_history[slot].freq_hz = freq_hz;
    g_history[slot].utc_ms = utc_ms;
    codeword_signs(payload, g_history[slot].sign);
}

// Build the 174-bit codeword signs (+1 for bit 1, -1 for bit 0) for a
// payload: a91 = payload+CRC (91 systematic bits), then 83 parity bits from
// kFTX_LDPC_generator — the same construction as osd.c / test_osd.c.
static void codeword_signs(const uint8_t payload[10], float sign[FTX_LDPC_N])
{
    uint8_t a91[FTX_LDPC_K_BYTES];
    ftx_add_crc(payload, a91);

    uint8_t bits[FTX_LDPC_N];
    for (int i = 0; i < FTX_LDPC_K; ++i)
        bits[i] = (a91[i / 8] >> (7 - (i % 8))) & 1u;
    for (int p = 0; p < FTX_LDPC_M; ++p)
    {
        int sum = 0;
        for (int j = 0; j < FTX_LDPC_K; ++j)
            if ((kFTX_LDPC_generator[p][j / 8] >> (7 - (j % 8))) & 1u)
                sum ^= bits[j];
        bits[FTX_LDPC_K + p] = (uint8_t)sum;
    }
    for (int i = 0; i < FTX_LDPC_N; ++i)
        sign[i] = bits[i] ? 1.0f : -1.0f;
}

bool ft8_xslot_try_history(float freq_hz, int64_t utc_ms, const float llrs[FTX_LDPC_N],
                           ftx_message_t* message, float* correlation_out)
{
    if (!llrs || !message)
        return false;

    float best_c = 0.0f;
    const xslot_hist_t* best = NULL;
    int parity = slot_parity(utc_ms);
    for (int i = 0; i < XSLOT_MAX_HISTORY; ++i)
    {
        const xslot_hist_t* h = &g_history[i];
        if (!h->used)
            continue;
        int64_t age = utc_ms - h->utc_ms;
        if (age <= 0 || age > FT8AF_XSLOT_HIST_MAX_AGE_MS)
            continue;
        if (slot_parity(h->utc_ms) != parity)
            continue; // stations repeat on their own slot parity
        if (fabsf(h->freq_hz - freq_hz) > FT8AF_XSLOT_FREQ_TOL)
            continue;

        // Normalized soft correlation: 1.0 = every bit agrees weighted by
        // confidence, ~N(0, 1/sqrt(174)) for an unrelated signal or noise.
        // The CRC proves nothing here (the payload is known), so this IS
        // the acceptance evidence — threshold swept on the bench. The sign
        // vector was precomputed when the message was remembered.
        float num = 0.0f, den = 0.0f;
        for (int k = 0; k < FTX_LDPC_N; ++k)
        {
            num += llrs[k] * h->sign[k];
            den += fabsf(llrs[k]);
        }
        float c = (den > 0.0f) ? num / den : 0.0f;
        if (c > best_c)
        {
            best_c = c;
            best = h;
        }
    }

    if (correlation_out)
        *correlation_out = best_c;
    if (!best || best_c < FT8AF_XSLOT_HIST_MIN)
        return false;

    ftx_message_init(message);
    memcpy(message->payload, best->payload, 10);
    // Same hash convention as ftx_decode_llrs: the message CRC.
    uint8_t a91[FTX_LDPC_K_BYTES];
    ftx_add_crc(best->payload, a91);
    a91[9] &= 0xF8;
    a91[10] = 0;
    message->hash = ftx_compute_crc(a91, 96 - 14);
    return true;
}
