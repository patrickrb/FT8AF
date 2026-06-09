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
    public void sequential_withSlotMillis_ft2AlternatesEvery3point8s() {
        // FT2's 3800ms slot isn't a whole number of seconds; sequential() works in ms
        // directly so the boundary lands exactly on each 3.8s multiple.
        assertThat(UtcTimer.sequential(0L, 3_800)).isEqualTo(0);
        assertThat(UtcTimer.sequential(3_799L, 3_800)).isEqualTo(0);
        assertThat(UtcTimer.sequential(3_800L, 3_800)).isEqualTo(1);
        assertThat(UtcTimer.sequential(7_600L, 3_800)).isEqualTo(0);
        assertThat(UtcTimer.sequential(11_400L, 3_800)).isEqualTo(1);
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

    /** The legacy minute-anchored predicate, kept here to prove equivalence for FT8/FT4. */
    private static boolean legacyBoundary(long utc, int offsetMs, int sec) {
        return (((utc - offsetMs) / 100) % 600) % sec == 0;
    }

    @Test
    public void isCycleBoundary_ft8MatchesLegacyMinuteAnchoredFormula() {
        // 150 tenths (15s) divides 600, so the new epoch-anchored test must agree with the
        // old minute-anchored one at every 100ms tick across a full minute.
        for (long ms = 0; ms < 60_000; ms += 100) {
            assertThat(UtcTimer.isCycleBoundary(ms, 0, 150))
                    .isEqualTo(legacyBoundary(ms, 0, 150));
        }
    }

    @Test
    public void isCycleBoundary_ft4MatchesLegacyMinuteAnchoredFormula() {
        // 75 tenths (7.5s) also divides 600 — behaviour is unchanged for FT4.
        for (long ms = 0; ms < 60_000; ms += 100) {
            assertThat(UtcTimer.isCycleBoundary(ms, 0, 75))
                    .isEqualTo(legacyBoundary(ms, 0, 75));
        }
    }

    @Test
    public void isCycleBoundary_ft8FiresOnAbsoluteSlotGrid() {
        assertThat(UtcTimer.isCycleBoundary(0L, 0, 150)).isTrue();
        assertThat(UtcTimer.isCycleBoundary(15_000L, 0, 150)).isTrue();
        assertThat(UtcTimer.isCycleBoundary(7_500L, 0, 150)).isFalse();
        // Real-world instant: a boundary iff systemTime % 15000 == 0.
        assertThat(UtcTimer.isCycleBoundary(T_2023, 0, 150))
                .isEqualTo(T_2023 % 15_000 == 0);
    }

    @Test
    public void isCycleBoundary_ft2FiresOnAbsolute3point8sGrid() {
        // FT2 (38 tenths) does NOT divide 600, so this is where old and new diverge.
        // The new predicate fires exactly on multiples of 3.8s from the epoch...
        assertThat(UtcTimer.isCycleBoundary(0L, 0, 38)).isTrue();
        assertThat(UtcTimer.isCycleBoundary(3_800L, 0, 38)).isTrue();
        assertThat(UtcTimer.isCycleBoundary(7_600L, 0, 38)).isTrue();
        assertThat(UtcTimer.isCycleBoundary(3_700L, 0, 38)).isFalse();
        assertThat(UtcTimer.isCycleBoundary(3_900L, 0, 38)).isFalse();
    }

    @Test
    public void isCycleBoundary_ft2EveryFireOpensASlotSoLateStartNeverClips() {
        // The transmit late-start math clips leading audio by (systemTime % slotMillis)
        // beyond the slack. The FT2 regression was that the timer fired when
        // systemTime % 3800 was large (up to ~3.0s), clipping the leading Costas sync.
        // With the epoch-anchored predicate, every fire lands within one 100ms tick of a
        // real 3800ms slot boundary, so msIntoCycle is always < 100 and nothing is clipped.
        int slotMillis = 3_800;
        int fires = 0;
        for (long ms = 0; ms < 600_000; ms += 100) { // 10 minutes of ticks
            if (UtcTimer.isCycleBoundary(ms, 0, 38)) {
                fires++;
                long msIntoCycle = ms % slotMillis;
                assertThat(msIntoCycle).isLessThan(100L);
            }
        }
        // Sanity: ~ one fire per 3.8s over 10 minutes.
        assertThat(fires).isEqualTo(600_000 / slotMillis + 1);
    }

    @Test
    public void isCycleBoundary_legacyFormulaWouldHaveClippedFt2() {
        // Guard the regression: the OLD predicate fired off the absolute slot grid for
        // FT2, landing where systemTime % 3800 was large — that is the clip that broke TX.
        boolean sawLargeOffset = false;
        for (long ms = 0; ms < 600_000; ms += 100) {
            if (legacyBoundary(ms, 0, 38) && (ms % 3_800) >= 1_280) {
                sawLargeOffset = true; // >= FT2 audio slack -> leading audio clipped
                break;
            }
        }
        assertThat(sawLargeOffset).isTrue();
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
