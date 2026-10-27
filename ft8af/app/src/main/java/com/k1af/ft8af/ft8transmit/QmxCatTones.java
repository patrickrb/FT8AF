package com.k1af.ft8af.ft8transmit;

import java.util.Locale;

/**
 * Transmit on a QRP Labs QMX by sending each FT8/FT4 tone as a CAT command
 * instead of audio.
 *
 * <p>In Digi mode the QMX accepts {@code TAnnnn.nn;} (firmware 1.04 CAT
 * manual): the radio transmits a single carrier at the USB dial frequency plus
 * that audio frequency. The first TA after {@code TX;} keys down with a shaped
 * envelope; {@code TA0;} (any value under 10 Hz) keys up the same way. Sending
 * one TA per symbol at the symbol rate reproduces the mode's FSK exactly, with
 * no audio path involved.
 *
 * <p>Why: on a motorola edge 2022 the phone's audio stack (an OEM Dolby effect
 * plus a -6 dB track gain, neither switchable) left the QMX's input meter at
 * 1-2 bars, below the 3 it needs to transmit, even with the waveform at full
 * scale. Another FT8 app drives the same radio at full power this way.
 *
 * <p>Pure: command formatting and tone math only, so it is unit-testable.
 */
public final class QmxCatTones {
    /** Config-table key the setting is persisted under. */
    public static final String CONFIG_KEY = "qmxCatTones";

    /** Key-up: the QMX treats any TA below 10 Hz as "stop transmitting". */
    public static final String KEY_UP = "TA0;";

    private QmxCatTones() {
    }

    /** The TA command for an audio tone of {@code hz}, to 0.01 Hz. */
    public static String taCommand(double hz) {
        return String.format(Locale.ROOT, "TA%.2f;", hz);
    }

    /**
     * Audio frequency of channel tone {@code tone} above {@code baseHz}. The
     * tone spacing of FT8/FT4 equals the symbol rate, 1 / symbol period
     * (6.25 Hz for FT8, 20.833 Hz for FT4).
     */
    public static double toneHz(double baseHz, int tone, float symbolPeriodSec) {
        return baseHz + tone / (double) symbolPeriodSec;
    }

    /**
     * Whether the tone loop ends with {@link #KEY_UP}. Not on a mid-over message
     * swap (a restart is pending and the over is still transmitting): PTT stays
     * keyed across a swap, as on the audio path, and the replay's first TA only
     * changes the tone. Keying up there would rely on the replay's TA re-keying
     * the radio without a fresh {@code TX;}, which the CAT manual doesn't
     * promise. A stop clears {@code transmitting}, so it still keys up here, and
     * the end-of-over {@code RX;} unkeys the radio either way.
     */
    public static boolean keyUpAfterTones(boolean restartPending, boolean transmitting) {
        return !(restartPending && transmitting);
    }

    /**
     * Symbols to skip when the over starts {@code lateMs} into its slot, so the
     * transmission still ends on the slot boundary (the audio path drops the
     * same span of samples).
     */
    public static int symbolsToSkip(int lateMs, float symbolPeriodSec, int numTones) {
        if (lateMs <= 0) return 0;
        int skip = (int) (lateMs / (symbolPeriodSec * 1000f));
        return Math.min(skip, numTones);
    }
}
