package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link ClearFrequencyFinder} — the clear-CQ-slot occupancy and
 * scoring logic behind issue #418 (see docs/clear-cq-slot-selection.md). Plain
 * JUnit: pure arithmetic, deterministic, no Android types.
 */
public class ClearFrequencyFinderTest {

    private static final long FT8_SLOT_MS = 15_000L;
    private static final long T0 = 1_000_000L;

    /** A finder with the shipping defaults. */
    private static ClearFrequencyFinder finder() {
        return new ClearFrequencyFinder();
    }

    private static void occupy(ClearFrequencyFinder f, float freq, long utc) {
        f.record(freq, utc, FT8_SLOT_MS);
    }

    // ---- occupancy intervals + guard spacing ---------------------------------

    @Test
    public void signalBlocksItsBandwidthPlusGuard() {
        ClearFrequencyFinder f = finder();
        occupy(f, 1500f, T0);

        // The signal itself: occupied.
        assertThat(f.isOccupied(1500f)).isTrue();
        // Candidate whose 50 Hz interval touches [1490, 1560] (10 Hz guard): occupied.
        assertThat(f.isOccupied(1440f)).isTrue();  // [1440,1490] touches 1490
        assertThat(f.isOccupied(1560f)).isTrue();  // [1560,1610] touches 1560
        // Just beyond the guard on either side: clear.
        assertThat(f.isOccupied(1439f)).isFalse();
        assertThat(f.isOccupied(1561f)).isFalse();
    }

    @Test
    public void emptyHistory_isNeverOccupied() {
        // No information is not "occupied" — and selection must not move either.
        ClearFrequencyFinder f = finder();
        assertThat(f.isOccupied(1500f)).isFalse();
        assertThat(f.selectClearOffset(1500f)).isNull();
    }

    // ---- multi-cycle window ---------------------------------------------------

    @Test
    public void observationsExpireAfterTheHistoryWindow() {
        ClearFrequencyFinder f = finder();
        occupy(f, 1500f, T0);
        assertThat(f.isOccupied(1500f)).isTrue();

        // 4-cycle window (default): a new observation 5 slots later evicts it.
        occupy(f, 300f, T0 + 5 * FT8_SLOT_MS);
        assertThat(f.isOccupied(1500f)).isFalse();
        assertThat(f.observationCount()).isEqualTo(1);
    }

    @Test
    public void windowFollowsTheModeSlotLength() {
        // Same timestamps, FT4 slot length (7.5 s): 4 cycles is only 30 s, so an
        // observation 40 s old is gone where FT8's 60 s window would keep it.
        ClearFrequencyFinder f = finder();
        f.record(1500f, T0, 7_500L);
        f.record(300f, T0 + 40_000L, 7_500L);
        assertThat(f.isOccupied(1500f)).isFalse();
    }

    @Test
    public void clearDropsEverything() {
        ClearFrequencyFinder f = finder();
        occupy(f, 1500f, T0);
        f.clear();
        assertThat(f.observationCount()).isEqualTo(0);
        assertThat(f.selectClearOffset(1500f)).isNull(); // back to no-information
    }

    // ---- selection ------------------------------------------------------------

    @Test
    public void keepsTheCurrentOffsetWhenItIsClear() {
        ClearFrequencyFinder f = finder();
        occupy(f, 500f, T0);
        // 1500 is nowhere near 500: no move suggested (a gratuitous QSY loses
        // callers that had already spotted us).
        assertThat(f.selectClearOffset(1500f)).isNull();
    }

    @Test
    public void movesOffAnOccupiedOffsetIntoAClearSpot() {
        ClearFrequencyFinder f = finder();
        occupy(f, 1500f, T0);
        Float pick = f.selectClearOffset(1510f); // we sit inside the occupied interval
        assertThat(pick).isNotNull();
        assertThat(f.isOccupied(pick)).isFalse();
        // The pick's own interval fits the candidate range.
        assertThat(pick).isAtLeast(ClearFrequencyFinder.DEFAULT_MIN_OFFSET_HZ);
        assertThat(pick + ClearFrequencyFinder.SIGNAL_BANDWIDTH_HZ)
                .isAtMost(ClearFrequencyFinder.DEFAULT_MAX_OFFSET_HZ);
    }

    @Test
    public void prefersTheWidestGap_thenTheBandCenter() {
        // Occupy everything except two gaps: a narrow one near 600 and a wide
        // one around 2000. The pick must land in the wide gap.
        ClearFrequencyFinder f = finder();
        for (float freq = 200f; freq <= 2800f; freq += 60f) {
            boolean inNarrowGap = freq > 550f && freq < 700f;
            boolean inWideGap = freq > 1700f && freq < 2300f;
            if (!inNarrowGap && !inWideGap) {
                occupy(f, freq, T0);
            }
        }
        Float pick = f.selectClearOffset(300f);
        assertThat(pick).isNotNull();
        assertThat(pick).isGreaterThan(1700f);
        assertThat(pick).isLessThan(2300f);
    }

    @Test
    public void centerTieBreak_picksMidBandOnAnEmptyishBand() {
        // One lone signal at 400: huge clear space everywhere, so many candidates
        // hit the clearance cap. The tie-break must pull the pick toward 1500,
        // not the band edges.
        ClearFrequencyFinder f = finder();
        occupy(f, 400f, T0);
        Float pick = f.selectClearOffset(410f); // current is occupied
        assertThat(pick).isNotNull();
        assertThat(Math.abs(pick - ClearFrequencyFinder.PREFERRED_CENTER_HZ))
                .isLessThan(300f);
    }

    @Test
    public void fullBand_staysPutWhenNothingIsBetter() {
        // Signals every 40 Hz across the whole candidate range: every candidate
        // interval intersects something, so clearance is 0 everywhere — no move
        // is suggested (hopping between equally-bad spots helps nobody).
        ClearFrequencyFinder f = finder();
        for (float freq = 150f; freq <= 2850f; freq += 40f) {
            occupy(f, freq, T0);
        }
        assertThat(f.selectClearOffset(1500f)).isNull();
    }

    // ---- retry / hold-down ------------------------------------------------------

    @Test
    public void relocatesOnlyWhenOccupied() {
        ClearFrequencyFinder f = finder();
        occupy(f, 500f, T0);
        assertThat(f.shouldRelocate(1500f, T0)).isFalse(); // clear: stay
        assertThat(f.shouldRelocate(510f, T0)).isTrue();   // occupied: go
    }

    @Test
    public void holdDownPacesRepeatedMoves() {
        ClearFrequencyFinder f = finder();
        occupy(f, 1500f, T0);
        assertThat(f.shouldRelocate(1500f, T0)).isTrue();
        f.noteMoved(T0);

        // Still occupied one cycle later: hold-down (45 s default) blocks the hop.
        occupy(f, 1500f, T0 + FT8_SLOT_MS);
        assertThat(f.shouldRelocate(1500f, T0 + FT8_SLOT_MS)).isFalse();

        // Past the hold-down: allowed again.
        occupy(f, 1500f, T0 + ClearFrequencyFinder.DEFAULT_MOVE_HOLDDOWN_MS);
        assertThat(f.shouldRelocate(1500f,
                T0 + ClearFrequencyFinder.DEFAULT_MOVE_HOLDDOWN_MS)).isTrue();
    }

    // ---- the CQ-idle gate (wiring predicate on FT8TransmitSignal) --------------

    @Test
    public void autoPickGate_requiresEnabledActivatedIdleCq() {
        TransmitCallsign cq = new TransmitCallsign(1, 0, "CQ", 0);
        TransmitCallsign station = new TransmitCallsign(1, 0, "K1ABC", 0);

        // The happy path: enabled, armed, not transmitting, CQ baseline, no target.
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                true, true, false, 6, cq)).isTrue();
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                true, true, false, 6, null)).isTrue();

        // Each precondition individually kills it.
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                false, true, false, 6, cq)).isFalse();   // setting off
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                true, false, false, 6, cq)).isFalse();   // not armed
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                true, true, true, 6, cq)).isFalse();     // mid-transmission
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                true, true, false, 2, station)).isFalse(); // mid-QSO: never QSY
        assertThat(FT8TransmitSignal.shouldAutoPickCqOffset(
                true, true, false, 6, station)).isFalse(); // caller locked
    }
}
