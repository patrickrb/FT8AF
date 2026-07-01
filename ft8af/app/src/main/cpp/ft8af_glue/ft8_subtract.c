// See ft8_subtract.h. Approach follows the published ft8_lib skimmer work
// (rx-888.com/web/design/digi.html): frequency-domain, per-symbol, replace
// the transmitted tone's magnitude with a local noise estimate. Compared to
// WSJT-X's time-domain waveform subtraction this is far cheaper (microseconds
// per signal, no re-FFT) and works within the 8-bit / 0.5 dB waterfall; the
// benchmark decides whether a time-domain pass is ever needed on top.

#include "ft8_subtract.h"

#include <math.h>
#include <string.h>

#include "ft8/constants.h"
#include "ft8/encode.h"

// Don't punch holes on marginal fits: only replace a bin that actually sits
// above the local noise estimate by at least this much (units of 0.5 dB).
#define SUBTRACT_MIN_EXCESS 2

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
