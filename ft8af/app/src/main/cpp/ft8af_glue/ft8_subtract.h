// Tone-accurate, noise-floor-referenced subtraction of a decoded FT8 signal
// from the monitor's waterfall, so subtract-and-redecode passes can uncover
// weaker signals underneath — without the collateral damage of the previous
// approach (zeroing ±4 bins × all planes, which erased a ~56 Hz × 12.6 s
// swath including any co-channel weaker signal).
//
// Shared by the Android JNI glue (ft8_decode_jni.cpp), the host decode
// benchmark (decode_bench.c), and — via FFI — the desktop port.
#ifndef FT8AF_FT8_SUBTRACT_H
#define FT8AF_FT8_SUBTRACT_H

#include <stdbool.h>
#include <stdint.h>

#include "common/monitor.h"

#ifdef __cplusplus
extern "C" {
#endif

// Remove the decoded signal described by (a91, freq_hz, time_sec) from
// mon->wf. a91 must point to at least 10 readable bytes holding the 77-bit
// payload: either the decoder's 12-byte payload+CRC block (DecoderGetA91) or
// a bare 10-byte ftx_message_t payload — only the 77 payload bits are used
// (the CRC bits sharing byte 9 are cleared internally). The tone sequence is
// re-encoded with ft8_encode, and for each of the 79 symbols only the
// nearest bin to the transmitted tone is replaced with a local noise
// estimate (the minimum of the 8 tone bins at that symbol) — deliberately
// not the spill neighbor as well; see the measurement note in ft8_subtract.c.
// Bins that are not on the signal's tone track are never touched.
void ft8_subtract_signal(monitor_t* mon, const uint8_t* a91,
                         float freq_hz, float time_sec);

// Coherent time-domain subtraction of the decoded signal from the raw float
// sample buffer (WSJT-X subtractft8 approach): synthesize the exact GFSK
// waveform as a complex reference, estimate the received signal's complex
// gain over time with a moving-average low-pass of samples * conj(reference)
// — which absorbs the unknown amplitude, phase, and the residual frequency
// error of the coarse candidate grid — and subtract 2*Re(gain * reference)
// in place. Unlike the waterfall-domain ft8_subtract_signal above, this
// removes the signal's full spectral footprint (Hann spill, sidelobes, tone
// transients), so a weaker co-channel signal only a few Hz away becomes
// decodable after the waterfall is recomputed from the residual samples.
//
// samples is modified in place; the caller must re-run the monitor STFT over
// it before the next decode pass. mon is the monitor the candidate came from
// — only its STFT geometry is read, to convert time_sec (which is in the
// waterfall's time base, lagging the raw sample stream by a constant
// block_size/2 + nfft/2 - subblock_size samples) into the signal's start
// sample. a91 follows the same 77-bit-payload contract as
// ft8_subtract_signal. Returns true if a subtraction was applied, false on
// degenerate input or allocation failure (samples then unchanged — callers
// can fall back to the waterfall-domain subtraction).
bool ft8_subtract_signal_time(const monitor_t* mon, float* samples,
                              int num_samples, int sample_rate,
                              const uint8_t* a91, float freq_hz, float time_sec);

#ifdef __cplusplus
}
#endif

#endif // FT8AF_FT8_SUBTRACT_H
