package com.bg7yoz.ft8cn.timer;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Static time-formatting helpers on UtcTimer. Inputs are epoch milliseconds and
 * every formatter is anchored to GMT (the SimpleDateFormat-based ones set the
 * timezone explicitly, the hand-rolled ones do pure modular arithmetic), so the
 * expected strings are independent of the host machine's locale/timezone.
 *
 * Two fixed instants are used:
 *   0L              -> 1970-01-01 00:00:00 UTC
 *   1_700_000_000_000L -> 2023-11-14 22:13:20 UTC
 */
public class UtcTimerTest {

    private static final long EPOCH = 0L;
    private static final long T_2023 = 1_700_000_000_000L;

    @Test
    public void getTimeStr_formatsUtcHms() {
        assertThat(UtcTimer.getTimeStr(EPOCH)).isEqualTo("UTC : 00:00:00");
        assertThat(UtcTimer.getTimeStr(T_2023)).isEqualTo("UTC : 22:13:20");
    }

    @Test
    public void getTimeHHMMSS_packsHmsWithoutSeparators() {
        assertThat(UtcTimer.getTimeHHMMSS(EPOCH)).isEqualTo("000000");
        assertThat(UtcTimer.getTimeHHMMSS(T_2023)).isEqualTo("221320");
    }

    @Test
    public void getYYYYMMDD_formatsGmtDate() {
        assertThat(UtcTimer.getYYYYMMDD(EPOCH)).isEqualTo("19700101");
        assertThat(UtcTimer.getYYYYMMDD(T_2023)).isEqualTo("20231114");
    }

    @Test
    public void getDatetimeStr_formatsGmtDateTime() {
        assertThat(UtcTimer.getDatetimeStr(EPOCH)).isEqualTo("1970-01-01 00:00:00");
        assertThat(UtcTimer.getDatetimeStr(T_2023)).isEqualTo("2023-11-14 22:13:20");
    }

    @Test
    public void getDatetimeYYYYMMDD_HHMMSS_formatsCompactGmtStamp() {
        assertThat(UtcTimer.getDatetimeYYYYMMDD_HHMMSS(EPOCH)).isEqualTo("19700101-000000");
        assertThat(UtcTimer.getDatetimeYYYYMMDD_HHMMSS(T_2023)).isEqualTo("20231114-221320");
    }

    @Test
    public void sequential_alternatesEvery15Seconds() {
        assertThat(UtcTimer.sequential(0L)).isEqualTo(0);
        assertThat(UtcTimer.sequential(15_000L)).isEqualTo(1);
        assertThat(UtcTimer.sequential(30_000L)).isEqualTo(0);
        assertThat(UtcTimer.sequential(45_000L)).isEqualTo(1);
        assertThat(UtcTimer.sequential(T_2023)).isEqualTo(1);
    }
}
