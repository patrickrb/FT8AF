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

#ifdef __cplusplus
}
#endif

#endif // FT8AF_FT8_SUBTRACT_H
