package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link GenerateFT8#waveformSampleCount(int, float, int)} — the TX audio
 * buffer sizing that MUST equal the number of samples the native GFSK synthesiser
 * ({@code synth_gfsk}, gfsk.c) writes.
 *
 * <p>Native writes {@code numTones * n_spsym} with {@code n_spsym = (int)(0.5f +
 * signal_rate * symbol_period)} (rounded per symbol). The old Java code sized the buffer
 * as {@code (int)(0.5f + numTones * symbolPeriod * sampleRate)} (rounded once over the
 * total). Those agree only when {@code sampleRate * symbolPeriod} is integral — true for
 * every UI-selectable rate (12/24/48 kHz) but NOT for an arbitrary rate restored from a
 * settings backup (e.g. 44100 Hz), where the total-rounded count can be smaller than what
 * native writes → a heap out-of-bounds write on the TX thread.
 *
 * <p>Plain JUnit: {@code waveformSampleCount} is a pure static helper with no JNI/global
 * state (GenerateFT8's static initialiser swallows the {@code loadLibrary} error, so the
 * class loads on the bare JVM).
 */
public class GenerateFT8WaveformSizeTest {

    // Mode parameters from ModeProfile (kept literal here so the test pins the exact
    // sample counts rather than re-deriving them from the same source under test).
    private static final int FT8_TONES = 79;
    private static final float FT8_PERIOD = 0.160f;
    private static final int FT4_TONES = 105;
    private static final float FT4_PERIOD = 0.048f;
    private static final int FT2_TONES = 105;
    private static final float FT2_PERIOD = 0.024f;

    /** The previous (total-rounded) sizing, reproduced to demonstrate the divergence. */
    private static int legacySize(int numTones, float symbolPeriod, int sampleRate) {
        return (int) (0.5f + numTones * symbolPeriod * sampleRate);
    }

    @Test
    public void supportedRates_ft8_matchExpectedSampleCounts() {
        assertThat(GenerateFT8.waveformSampleCount(FT8_TONES, FT8_PERIOD, 12000)).isEqualTo(151680);
        assertThat(GenerateFT8.waveformSampleCount(FT8_TONES, FT8_PERIOD, 24000)).isEqualTo(303360);
        assertThat(GenerateFT8.waveformSampleCount(FT8_TONES, FT8_PERIOD, 48000)).isEqualTo(606720);
    }

    @Test
    public void supportedRates_ft4_matchExpectedSampleCounts() {
        assertThat(GenerateFT8.waveformSampleCount(FT4_TONES, FT4_PERIOD, 12000)).isEqualTo(60480);
        assertThat(GenerateFT8.waveformSampleCount(FT4_TONES, FT4_PERIOD, 24000)).isEqualTo(120960);
        assertThat(GenerateFT8.waveformSampleCount(FT4_TONES, FT4_PERIOD, 48000)).isEqualTo(241920);
    }

    @Test
    public void supportedRates_ft2_matchExpectedSampleCounts() {
        assertThat(GenerateFT8.waveformSampleCount(FT2_TONES, FT2_PERIOD, 12000)).isEqualTo(30240);
        assertThat(GenerateFT8.waveformSampleCount(FT2_TONES, FT2_PERIOD, 48000)).isEqualTo(120960);
    }

    /**
     * At every UI-selectable rate the new per-symbol sizing is byte-identical to the old
     * total-rounded sizing, so this change cannot alter TX for any supported configuration.
     */
    @Test
    public void supportedRates_areByteIdenticalToLegacySizing() {
        int[] rates = {12000, 24000, 48000};
        for (int r : rates) {
            assertThat(GenerateFT8.waveformSampleCount(FT8_TONES, FT8_PERIOD, r))
                    .isEqualTo(legacySize(FT8_TONES, FT8_PERIOD, r));
            assertThat(GenerateFT8.waveformSampleCount(FT4_TONES, FT4_PERIOD, r))
                    .isEqualTo(legacySize(FT4_TONES, FT4_PERIOD, r));
            assertThat(GenerateFT8.waveformSampleCount(FT2_TONES, FT2_PERIOD, r))
                    .isEqualTo(legacySize(FT2_TONES, FT2_PERIOD, r));
        }
    }

    /**
     * The bug: at a non-integral rate (44100 Hz FT4, a plausible backup-restored audioRate)
     * the native writer emits {@code 105 * round(0.048*44100) = 105 * 2117 = 222285}
     * samples, but the old sizing allocated only 222264 — 21 samples short, so native ran
     * past the end of the buffer. The new sizing returns exactly what native writes.
     */
    @Test
    public void nonStandardRate_matchesNativeWriteCount_notLegacyUnderAllocation() {
        int nativeWrites = FT4_TONES * Math.round(FT4_PERIOD * 44100); // 105 * 2117
        assertThat(nativeWrites).isEqualTo(222285);

        int fixed = GenerateFT8.waveformSampleCount(FT4_TONES, FT4_PERIOD, 44100);
        assertThat(fixed).isEqualTo(nativeWrites);

        // The old total-rounded sizing under-allocated, which is the OOB source.
        assertThat(legacySize(FT4_TONES, FT4_PERIOD, 44100)).isLessThan(nativeWrites);
        assertThat(fixed).isGreaterThan(legacySize(FT4_TONES, FT4_PERIOD, 44100));
    }

    @Test
    public void degenerateInput_returnsZero_ratherThanNegativeOrThrowing() {
        assertThat(GenerateFT8.waveformSampleCount(FT8_TONES, FT8_PERIOD, 0)).isEqualTo(0);
        assertThat(GenerateFT8.waveformSampleCount(FT8_TONES, FT8_PERIOD, -12000)).isEqualTo(0);
        assertThat(GenerateFT8.waveformSampleCount(0, FT8_PERIOD, 12000)).isEqualTo(0);
        assertThat(GenerateFT8.waveformSampleCount(FT8_TONES, 0f, 12000)).isEqualTo(0);
        assertThat(GenerateFT8.waveformSampleCount(FT8_TONES, -0.16f, 12000)).isEqualTo(0);
    }

    /**
     * A corrupted/hand-edited backup rate large enough that {@code numTones * samplesPerSymbol}
     * overflows a 32-bit int must return 0, not a wrapped negative/too-small length (which
     * would drive a {@code new float[negative]} NegativeArraySizeException or, worse, an
     * under-sized buffer handed to native). At 20 MHz FT8, {@code round(0.160*20e6) = 3_200_000}
     * per symbol, {@code * 79 ≈ 2.53e8} still fits; push the rate high enough to overflow.
     */
    @Test
    public void overflowingRate_returnsZero_ratherThanWrappedLength() {
        // samplesPerSymbol ≈ round(0.160 * 200_000_000) = 32_000_000; * 79 ≈ 2.53e9 > INT_MAX.
        int result = GenerateFT8.waveformSampleCount(FT8_TONES, FT8_PERIOD, 200_000_000);
        assertThat(result).isEqualTo(0);
    }
}
