// ftx_feed.h — the per-slot audio-feed step shared by the FT8 and FT4/FT2
// decoders.
//
// WHY THIS EXISTS
// ------------------------------------------------------------------
// Each decoder pushes one slot of PCM audio through its monitor (building the
// waterfall) and keeps a copy of the samples for the deep-decode subtraction
// pass. ft8_decode_jni.cpp (ft8_feed) and ft2_decode_jni.cpp (ft2_feed) kept a
// byte-for-byte identical copy of that inner step: an optional bounded memcpy
// into the retained buffer, then monitor_reset() and a monitor_process() loop
// over the buffer.
//
// Both copies dereferenced the incoming `data` pointer WITHOUT a null check.
// The float feed entry points (DecoderMonitorPressFloat / DecoderFt2Monitor-
// PressFloat) hand this the raw result of JNIEnv::GetFloatArrayElements, which
// is allowed to return NULL when the JVM cannot pin the array (out-of-memory /
// heap pressure). The int16 sibling DecoderMonitorPress already guards its pin
// with `if (!data) return;`, but the float paths did not — so a failed pin fed
// a NULL pointer straight into memcpy()/monitor_process(), an uncatchable
// native SIGSEGV on the decode thread.
//
// This header collapses the two copies into one null-safe, host-tested
// implementation (test_feed.c). The behaviour is identical to the old inline
// copies for every valid buffer; the only change is that a NULL `data` (or a
// negative length) is now a no-op instead of a crash.

#ifndef FT8AF_FTX_FEED_H
#define FT8AF_FTX_FEED_H

#include <stddef.h>
#include <string.h>

#include "common/monitor.h"

// Feed one slot of `n` audio samples through `mon`, first keeping a copy of up
// to `num_samples` of them in `samples` (when `samples` is non-NULL and the
// slot fits) for the later subtraction pass.
//
// Returns the number of samples copied into `samples` (0 when nothing was kept,
// including the null-guarded no-op case). A NULL `mon` or `data`, or a negative
// `n`, is a no-op that returns 0 — never a dereference.
static inline int ftx_feed_monitor(monitor_t* mon, float* samples,
                                   int num_samples, const float* data, int n)
{
    if (mon == NULL || data == NULL || n < 0)
        return 0;

    int copied = 0;
    if (samples != NULL && n <= num_samples)
    {
        memcpy(samples, data, sizeof(float) * (size_t)n);
        copied = n;
    }

    monitor_reset(mon);
    for (int pos = 0; pos + mon->block_size <= n; pos += mon->block_size)
        monitor_process(mon, data + pos);

    return copied;
}

#endif // FT8AF_FTX_FEED_H
