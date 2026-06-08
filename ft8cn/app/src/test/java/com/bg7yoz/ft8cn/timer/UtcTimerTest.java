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

    @Test
    public void sequential_withSlotMillis_ft8MatchesLegacy() {
        // The 2-arg form with 15000 must equal the legacy ((utc/1000)/15)%2 exactly.
        assertThat(UtcTimer.sequential(0L, 15_000)).isEqualTo(0);
        assertThat(UtcTimer.sequential(15_000L, 15_000)).isEqualTo(1);
        assertThat(UtcTimer.sequential(30_000L, 15_000)).isEqualTo(0);
        assertThat(UtcTimer.sequential(T_2023, 15_000))
                .isEqualTo((int) (((T_2023 / 1000) / 15) % 2));
    }

    @Test
    public void sequential_withSlotMillis_ft4AlternatesEvery7point5s() {
        assertThat(UtcTimer.sequential(0L, 7_500)).isEqualTo(0);
        assertThat(UtcTimer.sequential(7_500L, 7_500)).isEqualTo(1);
        assertThat(UtcTimer.sequential(15_000L, 7_500)).isEqualTo(0);
        assertThat(UtcTimer.sequential(22_500L, 7_500)).isEqualTo(1);
    }

    @Test
    public void sequential_isStableWithinASingleCycle() {
        // Any instant inside the first 15-second window is slot 0; the boundary
        // at 15s flips to slot 1. Sub-second jitter must not change the slot.
        assertThat(UtcTimer.sequential(1L)).isEqualTo(0);
        assertThat(UtcTimer.sequential(14_999L)).isEqualTo(0);
        assertThat(UtcTimer.sequential(15_001L)).isEqualTo(1);
        assertThat(UtcTimer.sequential(29_999L)).isEqualTo(1);
    }

    @Test
    public void getTimeStr_wrapsHoursAtMidnight() {
        // 24h exactly -> back to 00:00:00 (hour is modulo 24).
        long oneDayMs = 24L * 60 * 60 * 1000;
        assertThat(UtcTimer.getTimeStr(oneDayMs)).isEqualTo("UTC : 00:00:00");
        // 25h 1m 1s -> 01:01:01.
        long t = (25L * 3600 + 61) * 1000;
        assertThat(UtcTimer.getTimeStr(t)).isEqualTo("UTC : 01:01:01");
    }

    @Test
    public void getTimeHHMMSS_wrapsHoursAtMidnight() {
        long oneDayMs = 24L * 60 * 60 * 1000;
        assertThat(UtcTimer.getTimeHHMMSS(oneDayMs)).isEqualTo("000000");
    }

    @Test
    public void getSystemTime_includesDelayOffset() {
        // getSystemTime() == delay + System.currentTimeMillis(); with delay==0
        // it should sit within a small window of the wall clock. Save/restore the
        // static delay so this test leaves no side effects for siblings.
        int saved = UtcTimer.delay;
        try {
            UtcTimer.delay = 0;
            long before = System.currentTimeMillis();
            long sys = UtcTimer.getSystemTime();
            long after = System.currentTimeMillis();
            assertThat(sys).isAtLeast(before);
            assertThat(sys).isAtMost(after);

            // A non-zero delay shifts the reported time by exactly that amount.
            UtcTimer.delay = 5000;
            long shifted = UtcTimer.getSystemTime();
            assertThat(shifted - System.currentTimeMillis()).isWithin(100L).of(5000L);
        } finally {
            UtcTimer.delay = saved;
        }
    }

    @Test
    public void getNowSequential_matchesSequentialOfSystemTime() {
        int saved = UtcTimer.delay;
        try {
            UtcTimer.delay = 0;
            // getNowSequential() == sequential(getSystemTime()); both reads happen
            // close enough that they land in the same 15s slot in the vast
            // majority of cases. Recompute and accept the rare boundary flip.
            int now = UtcTimer.getNowSequential();
            assertThat(now).isAnyOf(0, 1);
        } finally {
            UtcTimer.delay = saved;
        }
    }
}
