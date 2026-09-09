// Host unit tests for ftx_feed.h — the per-slot audio-feed step shared by the
// FT8 and FT4/FT2 decoders. Run by run_host_tests.sh / .ps1.
//
// Covered:
//   1. A valid buffer that fits is copied bit-exact into the retained samples
//      buffer and the copied count is returned (the deep-decode subtraction
//      pass reads this copy).
//   2. A buffer larger than the retained capacity is NOT copied (copied==0) but
//      is still fed through the monitor without overrunning `samples`.
//   3. A NULL retained buffer (samples==NULL) still feeds the monitor and
//      returns 0 — the int16 path used to feed with no kept copy.
//   4. REGRESSION (the reason this file exists): a NULL `data` pointer — the
//      value JNIEnv::GetFloatArrayElements returns when the JVM cannot pin the
//      array under memory pressure — must be a no-op that returns 0, NOT a
//      native SIGSEGV. The previous inline copies in ft8_decode_jni.cpp /
//      ft2_decode_jni.cpp dereferenced `data` in memcpy()/monitor_process()
//      with no null check; the int16 sibling DecoderMonitorPress guarded its
//      pin but the two float paths did not. This test feeds NULL and can only
//      pass if the dereference is guarded.
//   5. A negative length is likewise a no-op returning 0.

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include "common/monitor.h"
#include "decode_params.h"
#include "ftx_feed.h"

#define RATE 12000
#define NSAMPLES (RATE * 15)

static int g_failures = 0;

static void check(bool ok, const char* what)
{
    printf("  %s %s\n", ok ? "ok:  " : "FAIL:", what);
    if (!ok)
        g_failures++;
}

static void init_monitor(monitor_t* mon)
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

int main(void)
{
    monitor_t mon;
    init_monitor(&mon);

    // A deterministic ramp of audio; a fraction of a slot is plenty to exercise
    // several monitor blocks.
    const int n = RATE * 2;
    float* data = (float*)malloc(sizeof(float) * (size_t)NSAMPLES);
    for (int i = 0; i < NSAMPLES; ++i)
        data[i] = (float)((i % 4096) - 2048) / 2048.0f;

    // Retained buffer, sized like the decoder's `samples` (one full slot).
    const int cap = NSAMPLES;
    float* samples = (float*)malloc(sizeof(float) * (size_t)cap);
    for (int i = 0; i < cap; ++i)
        samples[i] = -999.0f; // sentinel

    // 1. Valid buffer that fits: copied bit-exact, count returned.
    int copied = ftx_feed_monitor(&mon, samples, cap, data, n);
    check(copied == n, "valid feed returns the copied sample count");
    bool exact = true;
    for (int i = 0; i < n; ++i)
        if (samples[i] != data[i])
        {
            exact = false;
            break;
        }
    check(exact, "valid feed copies the samples bit-exact");
    check(samples[n] == -999.0f, "valid feed copies exactly n samples (no overrun)");

    // 2. Buffer larger than the retained capacity: not copied, but still fed.
    monitor_t mon2;
    init_monitor(&mon2);
    int copied_big = ftx_feed_monitor(&mon2, samples, /*num_samples=*/n - 1, data, n);
    check(copied_big == 0, "over-capacity slot is not copied (copied==0)");

    // 3. NULL retained buffer: monitor still fed, nothing kept.
    monitor_t mon3;
    init_monitor(&mon3);
    int copied_null_samples = ftx_feed_monitor(&mon3, NULL, cap, data, n);
    check(copied_null_samples == 0, "NULL samples buffer keeps nothing");

    // 4. REGRESSION: NULL data must be a guarded no-op, not a SIGSEGV.
    monitor_t mon4;
    init_monitor(&mon4);
    int copied_null_data = ftx_feed_monitor(&mon4, samples, cap, NULL, n);
    check(copied_null_data == 0, "NULL data is a no-op (no crash)");

    // 5. Negative length: guarded no-op.
    int copied_neg = ftx_feed_monitor(&mon, samples, cap, data, -1);
    check(copied_neg == 0, "negative length is a no-op");

    monitor_free(&mon);
    monitor_free(&mon2);
    monitor_free(&mon3);
    monitor_free(&mon4);
    free(data);
    free(samples);

    if (g_failures == 0)
    {
        printf("test_feed: ALL PASS\n");
        return 0;
    }
    printf("test_feed: %d FAILURE(S)\n", g_failures);
    return 1;
}
