package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link MicRecorder#formatSinceReinit(long, long)} — the "time since
 * last reinit" token in the capture-STOPPED debug line.
 *
 * <p>Regression: {@code lastReinitMs} starts at 0, so a capture session that
 * stops before any reinit happened printed {@code now - 0}, i.e. the raw
 * wall-clock epoch (~1.78e12 ms ≈ 56,000 years), which showed up as nonsense
 * like {@code sinceLastReinit=1781908820999ms} in real logs. The helper must
 * substitute a sentinel in that case while still reporting a real elapsed
 * duration once a reinit has occurred.
 */
public class MicRecorderSinceReinitTest {

    @Test
    public void noReinitYet_showsSentinelNotEpoch() {
        // lastReinitMs == 0 is the uninitialised case.
        assertThat(MicRecorder.formatSinceReinit(1_781_908_820_999L, 0L))
                .isEqualTo("n/a (no reinit yet)");
    }

    @Test
    public void afterReinit_showsElapsedMillis() {
        assertThat(MicRecorder.formatSinceReinit(12_345L, 12_000L))
                .isEqualTo("345ms");
    }

    @Test
    public void zeroElapsed_showsZeroMillis() {
        // A reinit that just happened (lastReinitMs != 0) reads as 0ms, not the
        // sentinel — the sentinel is reserved for "never reinit'd".
        assertThat(MicRecorder.formatSinceReinit(5_000L, 5_000L))
                .isEqualTo("0ms");
    }
}
