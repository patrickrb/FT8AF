package com.k1af.ft8af.timer;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link DecodeDtDisplay} — the robust, smoothed source for the DT figure shown on
 * the operating screen. Pure Java, no Robolectric needed.
 *
 * <p>The behaviour under test is the fix for "my DT indicator says 2.5 s off while the rig works
 * stations perfectly": one station's DT, or one marginal deep-pass decode, must not be allowed to
 * move the displayed number, while a real, sustained clock error still must.
 */
public class DecodeDtDisplayTest {

    private static final int DELAY = 0;

    /** Slot boundaries only have to be increasing; real ones are UTC ms. */
    private static long slot(int n) {
        return 1_700_000_000_000L + n * 15_000L;
    }

    @Test
    public void firstSlotPublishesImmediately() {
        DecodeDtDisplay dt = new DecodeDtDisplay();
        Float shown = dt.onDecodes(slot(1), new float[]{0.4f, 0.5f, 0.6f}, DELAY);
        assertThat(shown).isNotNull();
        assertThat(shown).isWithin(1e-4f).of(0.5f);
    }

    @Test
    public void oneLateStationCannotDragTheReading() {
        // The exact shape of the complaint: three in-time decodes and one station 7 s late.
        // The old arithmetic mean of these is +1.85 s (a red "clock off" verdict).
        DecodeDtDisplay dt = new DecodeDtDisplay();
        Float shown = dt.onDecodes(slot(1), new float[]{0.1f, 0.0f, 0.2f, 7.0f}, DELAY);
        assertThat(shown).isNotNull();
        assertThat(Math.abs(shown)).isLessThan(0.3f);
    }

    @Test
    public void deepPassRefinesTheSlotInsteadOfReplacingIt() {
        DecodeDtDisplay dt = new DecodeDtDisplay();
        Float fast = dt.onDecodes(slot(1), new float[]{0.1f, 0.1f, 0.2f}, DELAY);
        assertThat(fast).isNotNull();
        // A deep pass for the SAME slot digs out one marginal decode with a wild DT. It joins the
        // slot's samples (where it is rejected) rather than becoming the new reading.
        Float deep = dt.onDecodes(slot(1), new float[]{2.4f}, DELAY);
        assertThat(deep).isNull();
        assertThat(dt.lastPublished()).isWithin(1e-4f).of(fast);
    }

    @Test
    public void oneFreakSlotIsOutvotedByTheWindow() {
        DecodeDtDisplay dt = new DecodeDtDisplay();
        dt.onDecodes(slot(1), new float[]{0.1f}, DELAY);
        dt.onDecodes(slot(2), new float[]{0.1f}, DELAY);
        // A whole slot whose only decode is a late station: the median of the last three slots
        // still reads the healthy clock.
        Float shown = dt.onDecodes(slot(3), new float[]{3.0f}, DELAY);
        assertThat(shown).isNull();
        assertThat(dt.lastPublished()).isWithin(1e-4f).of(0.1f);
    }

    @Test
    public void sustainedErrorIsStillFollowed() {
        DecodeDtDisplay dt = new DecodeDtDisplay();
        dt.onDecodes(slot(1), new float[]{0.0f, 0.0f}, DELAY);
        dt.onDecodes(slot(2), new float[]{0.0f, 0.0f}, DELAY);
        // The clock genuinely drifts: every station now lands a second late.
        dt.onDecodes(slot(3), new float[]{1.0f, 1.0f}, DELAY);
        dt.onDecodes(slot(4), new float[]{1.0f, 1.0f}, DELAY);
        assertThat(dt.lastPublished()).isWithin(1e-4f).of(1.0f);
    }

    @Test
    public void tinyChangesAreHeldSoTheLastDigitStopsTwitching() {
        DecodeDtDisplay dt = new DecodeDtDisplay();
        assertThat(dt.onDecodes(slot(1), new float[]{0.10f}, DELAY)).isNotNull();
        assertThat(dt.onDecodes(slot(2), new float[]{0.12f}, DELAY)).isNull();
        assertThat(dt.onDecodes(slot(3), new float[]{0.11f}, DELAY)).isNull();
    }

    @Test
    public void aClockCorrectionResetsTheWindow() {
        DecodeDtDisplay dt = new DecodeDtDisplay();
        dt.onDecodes(slot(1), new float[]{1.2f}, DELAY);
        dt.onDecodes(slot(2), new float[]{1.2f}, DELAY);
        assertThat(dt.lastPublished()).isWithin(1e-4f).of(1.2f);
        // Auto-sync (or the operator) trims the clock by 1.2 s: the stored samples describe the
        // old clock, so the next slot re-acquires on its own instead of being averaged with them.
        Float shown = dt.onDecodes(slot(3), new float[]{0.0f}, DELAY - 1200);
        assertThat(shown).isNotNull();
        assertThat(shown).isWithin(1e-4f).of(0.0f);
    }

    @Test
    public void stragglerForAnOlderSlotIsIgnored() {
        DecodeDtDisplay dt = new DecodeDtDisplay();
        dt.onDecodes(slot(2), new float[]{0.1f}, DELAY);
        // A late pass for the previous slot arriving after its successor: stale evidence.
        assertThat(dt.onDecodes(slot(1), new float[]{4.0f}, DELAY)).isNull();
        assertThat(dt.lastPublished()).isWithin(1e-4f).of(0.1f);
    }

    @Test
    public void emptyPassPublishesNothing() {
        DecodeDtDisplay dt = new DecodeDtDisplay();
        assertThat(dt.onDecodes(slot(1), new float[0], DELAY)).isNull();
        assertThat(dt.onDecodes(slot(2), null, DELAY)).isNull();
        assertThat(dt.lastPublished()).isNull();
    }

    @Test
    public void windowOnlyRemembersTheMostRecentSlots() {
        DecodeDtDisplay dt = new DecodeDtDisplay();
        // Three slots of a real +2 s error, then the clock is fixed and stays fixed. Once the
        // window has rolled past the bad slots the reading must be the new truth, not a blend.
        for (int i = 1; i <= 3; i++) {
            dt.onDecodes(slot(i), new float[]{2.0f}, DELAY);
        }
        assertThat(dt.lastPublished()).isWithin(1e-4f).of(2.0f);
        for (int i = 4; i <= 3 + DecodeDtDisplay.WINDOW_SLOTS; i++) {
            dt.onDecodes(slot(i), new float[]{0.0f}, DELAY);
        }
        assertThat(dt.lastPublished()).isWithin(1e-4f).of(0.0f);
    }

    @Test
    public void slotSamplesAreCappedAcrossPasses() {
        DecodeDtDisplay dt = new DecodeDtDisplay();
        float[] batch = new float[DecodeDtDisplay.MAX_SLOT_SAMPLES];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = 0.2f;
        }
        assertThat(dt.onDecodes(slot(1), batch, DELAY)).isNotNull();
        // Past the cap, further passes for the same slot are dropped rather than growing forever;
        // the reading stands on what it already has.
        assertThat(dt.onDecodes(slot(1), new float[]{9.0f, 9.0f, 9.0f}, DELAY)).isNull();
        assertThat(dt.lastPublished()).isWithin(1e-4f).of(0.2f);
    }

    @Test
    public void resetClearsEverything() {
        DecodeDtDisplay dt = new DecodeDtDisplay();
        dt.onDecodes(slot(1), new float[]{0.7f}, DELAY);
        dt.reset();
        assertThat(dt.lastPublished()).isNull();
        Float shown = dt.onDecodes(slot(2), new float[]{-0.4f}, DELAY);
        assertThat(shown).isWithin(1e-4f).of(-0.4f);
    }
}
