// Host test for the GFSK output-length helper and the no-overrun property of
// synth_gfsk_offset (gfsk.c).
//
// The Java TX path (GenerateFT8) and the native synthesiser must agree on the
// output sample count. Native writes synth_gfsk_output_len(n_sym, period, rate)
// = n_sym * (int)(0.5f + rate*period) samples. If a caller sizes the buffer with
// a different rounding — the old Java bug used round(n_sym*period*rate) — and
// that total rounds down while the native per-symbol product rounds up, the
// synthesiser writes past the end of the buffer (heap OOB). These tests pin the
// helper's counts (including a non-integral rate where it diverges from the old
// sizing) and verify synth_gfsk_offset writes EXACTLY output_len samples, leaving
// a trailing canary region untouched.
//
// Built/run by run_host_tests.sh / run_host_tests.ps1. No ft8_lib deps.

#include <math.h>
#include <stdio.h>
#include <stdlib.h>

int synth_gfsk_output_len(int n_sym, float symbol_period, int signal_rate);
void synth_gfsk_offset(const unsigned char *symbols, int n_sym, float f0,
                       float symbol_bt, float symbol_period,
                       int signal_rate, float *signal, int offset);

static int failures = 0;

static void check(int cond, const char *msg) {
    if (cond) {
        printf("  ok: %s\n", msg);
    } else {
        printf("  FAIL: %s\n", msg);
        failures++;
    }
}

// The previous (buggy) Java buffer sizing: round the total once.
static int legacy_java_len(int n_sym, float period, int rate) {
    return (int)(0.5f + n_sym * period * rate);
}

int main(void) {
    printf("test_gfsk_len:\n");

    // 1. Supported rates: helper matches n_sym * round(rate*period).
    check(synth_gfsk_output_len(79, 0.160f, 12000) == 151680, "FT8@12000 -> 151680");
    check(synth_gfsk_output_len(79, 0.160f, 48000) == 606720, "FT8@48000 -> 606720");
    check(synth_gfsk_output_len(105, 0.048f, 12000) == 60480, "FT4@12000 -> 60480");
    check(synth_gfsk_output_len(105, 0.024f, 48000) == 120960, "FT2@48000 -> 120960");

    // 2. Degenerate input -> 0 (no synthesis), never negative.
    check(synth_gfsk_output_len(0, 0.16f, 12000) == 0, "zero tones -> 0");
    check(synth_gfsk_output_len(79, 0.16f, 0) == 0, "zero rate -> 0");
    check(synth_gfsk_output_len(79, -1.0f, 12000) == 0, "negative period -> 0");
    check(synth_gfsk_output_len(79, 0.0f, 12000) == 0, "zero period -> 0");

    // 3. The divergence at a non-integral rate (44100 Hz FT4): native writes MORE
    //    than the old Java sizing allocated — the source of the OOB write.
    int native_len = synth_gfsk_output_len(105, 0.048f, 44100);
    check(native_len == 222285, "FT4@44100 -> 222285 (105 * round(0.048*44100))");
    check(native_len > legacy_java_len(105, 0.048f, 44100),
          "FT4@44100 native len exceeds old Java alloc (OOB source)");

    // 4. synth_gfsk_offset writes EXACTLY output_len samples: a canary past the
    //    end stays intact. Run at the divergent rate that triggered the bug.
    {
        const int n_sym = 105, rate = 44100;
        const float period = 0.048f;
        int len = synth_gfsk_output_len(n_sym, period, rate);
        const int canary = 32;
        float *buf = (float *)malloc(sizeof(float) * (size_t)(len + canary));
        if (!buf) {
            printf("  FAIL: malloc\n");
            return 1;
        }
        for (int i = 0; i < len + canary; ++i) buf[i] = -999.0f;

        unsigned char syms[105];
        for (int i = 0; i < n_sym; ++i) syms[i] = (unsigned char)(i % 4);

        synth_gfsk_offset(syms, n_sym, 1000.0f, 1.0f, period, rate, buf, 0);

        int canary_intact = 1;
        for (int i = len; i < len + canary; ++i) {
            if (buf[i] != -999.0f) { canary_intact = 0; break; }
        }
        check(canary_intact,
              "synth_gfsk_offset writes exactly output_len samples (no overrun)");

        // And it did write something into the buffer (not a no-op).
        int wrote = 0;
        for (int i = 0; i < len; ++i) {
            if (buf[i] != -999.0f) { wrote = 1; break; }
        }
        check(wrote, "synth_gfsk_offset populated the output buffer");
        free(buf);
    }

    if (failures) {
        printf("test_gfsk_len: %d FAILURE(S)\n", failures);
        return 1;
    }
    printf("test_gfsk_len: all passed\n");
    return 0;
}
