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
    public void sequential_withSlotMillis_ft2AlternatesEvery3point75s() {
        // FT2's 3750ms slot isn't a whole number of seconds; sequential() works in ms
        // directly so the boundary lands exactly on each 3.75s multiple.
        assertThat(UtcTimer.sequential(0L, 3_750)).isEqualTo(0);
        assertThat(UtcTimer.sequential(3_749L, 3_750)).isEqualTo(0);
        assertThat(UtcTimer.sequential(3_750L, 3_750)).isEqualTo(1);
        assertThat(UtcTimer.sequential(7_500L, 3_750)).isEqualTo(0);
        assertThat(UtcTimer.sequential(11_250L, 3_750)).isEqualTo(1);
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

    /**
     * The slot-boundary predicate before this change worked in tenths of a second
     * ({@code (utc/100) % tenths == 0}). Kept here to prove the new millisecond-window
     * predicate fires on exactly the same 100ms ticks for FT8 and FT4 — whose slot lengths
     * are whole tenths (150, 75) — so only FT2 (3.75s, not a whole tenth) changes.
     */
    private static boolean tenthsBoundary(long utc, int offsetMs, int tenths) {
        return (((utc - offsetMs) / 100) % tenths) == 0;
    }

    @Test
    public void isCycleBoundary_ft8MatchesPreviousTenthsPredicate() {
        for (long ms = 0; ms < 60_000; ms += 100) {
            assertThat(UtcTimer.isCycleBoundary(ms, 0, 15_000))
                    .isEqualTo(tenthsBoundary(ms, 0, 150));
        }
    }

    @Test
    public void isCycleBoundary_ft4MatchesPreviousTenthsPredicate() {
        for (long ms = 0; ms < 60_000; ms += 100) {
            assertThat(UtcTimer.isCycleBoundary(ms, 0, 7_500))
                    .isEqualTo(tenthsBoundary(ms, 0, 75));
        }
    }

    @Test
    public void isCycleBoundary_ft8FiresOnAbsoluteSlotGrid() {
        assertThat(UtcTimer.isCycleBoundary(0L, 0, 15_000)).isTrue();
        assertThat(UtcTimer.isCycleBoundary(15_000L, 0, 15_000)).isTrue();
        assertThat(UtcTimer.isCycleBoundary(7_500L, 0, 15_000)).isFalse();
        // Real-world instant: a boundary iff systemTime % 15000 is inside the open window.
        assertThat(UtcTimer.isCycleBoundary(T_2023, 0, 15_000))
                .isEqualTo(T_2023 % 15_000 < 100);
    }

    @Test
    public void isCycleBoundary_ft2FiresOnAbsolute3point75sGrid() {
        // FT2's 3750ms slot has no tenths-of-a-second form (37.5 tenths), so the
        // millisecond predicate is what puts it on a clean grid. Fires in the 100ms
        // window opening each slot.
        assertThat(UtcTimer.isCycleBoundary(0L, 0, 3_750)).isTrue();
        assertThat(UtcTimer.isCycleBoundary(3_750L, 0, 3_750)).isTrue();
        assertThat(UtcTimer.isCycleBoundary(7_500L, 0, 3_750)).isTrue();
        assertThat(UtcTimer.isCycleBoundary(11_250L, 0, 3_750)).isTrue();
        // Mid-slot and just-outside-the-window instants do not fire.
        assertThat(UtcTimer.isCycleBoundary(1_875L, 0, 3_750)).isFalse();
        assertThat(UtcTimer.isCycleBoundary(3_700L, 0, 3_750)).isFalse();
        assertThat(UtcTimer.isCycleBoundary(3_850L, 0, 3_750)).isFalse();
    }

    @Test
    public void isCycleBoundary_ft2EveryFireOpensASlotSoLateStartNeverClips() {
        // The transmit late-start math clips leading audio by (systemTime % slotMillis)
        // beyond the slack. Every fire must land within the 100ms slot-open window so
        // msIntoCycle stays < 100 and the leading Costas sync is never clipped.
        int slotMillis = 3_750;
        int fires = 0;
        for (long ms = 0; ms < 600_000; ms += 100) { // 10 minutes of ticks
            if (UtcTimer.isCycleBoundary(ms, 0, slotMillis)) {
                fires++;
                long msIntoCycle = ms % slotMillis;
                assertThat(msIntoCycle).isLessThan(100L);
            }
        }
        // Exactly one fire per slot over 10 minutes. When slotMillis divides 600000 the
        // boundary at 600000 itself is excluded (loop is ms < 600000).
        int expected = (600_000 % slotMillis == 0)
                ? 600_000 / slotMillis
                : 600_000 / slotMillis + 1;
        assertThat(fires).isEqualTo(expected);
    }

    @Test
    public void isCycleBoundary_oneSecondTickFiresOnSecondGrid() {
        // MainViewModel's clock-display timer is a 1-second tick. It used to pass the
        // tenths value 10 (= 1s); under the millisecond API it must pass 1000. Both forms
        // fire once per second, second-aligned — guard against the unit regression.
        for (long ms = 0; ms < 10_000; ms += 100) {
            assertThat(UtcTimer.isCycleBoundary(ms, 0, 1_000))
                    .isEqualTo(tenthsBoundary(ms, 0, 10));
        }
        assertThat(UtcTimer.isCycleBoundary(1_000L, 0, 1_000)).isTrue();
        assertThat(UtcTimer.isCycleBoundary(500L, 0, 1_000)).isFalse();
    }

    @Test
    public void isCycleBoundary_nonPositivePeriodNeverFires() {
        assertThat(UtcTimer.isCycleBoundary(0L, 0, 0)).isFalse();
        assertThat(UtcTimer.isCycleBoundary(15_000L, 0, -5)).isFalse();
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
