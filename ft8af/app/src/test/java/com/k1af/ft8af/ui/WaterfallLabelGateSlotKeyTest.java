package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.FT8Common;
import com.k1af.ft8af.Ft8Message;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

/**
 * Unit tests for {@link WaterfallLabelGate#shouldStamp(long, java.util.List)}: the label
 * stamp is keyed on the slot the messages were <em>decoded from</em> (their utcTime), not
 * on the wall clock at stamp time.
 *
 * <p>Regression guard for the doubled/garbled waterfall labels that appeared with the
 * deep-decode speedups (#367&ndash;#370): the subtraction/cross-slot loop runs on a budget of
 * ~0.75 of a slot starting near the slot's end, so late passes finish <em>after</em> the slot
 * boundary. Each pass re-arms the stamp; keyed on wall clock the late ones landed in a fresh
 * slot index, so the gate let the same merged label list stamp again a few scrolled rows
 * lower and every callsign on the waterfall was written more than once.
 */
@RunWith(RobolectricTestRunner.class)
public class WaterfallLabelGateSlotKeyTest {

    private static final long FT8_SLOT_MS = 15_000;
    private static final long FT4_SLOT_MS = 7_500;

    /** A decode result whose messages all carry the given decoded-slot start UTC. */
    private ArrayList<Ft8Message> decodedAt(long slotStartUtcMs, int n) {
        ArrayList<Ft8Message> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
            msg.utcTime = slotStartUtcMs;
            list.add(msg);
        }
        return list;
    }

    @Test
    public void firstStampOfADecodedSlotIsAllowed() {
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(150_000, 3))).isTrue();
    }

    @Test
    public void latePassAfterTheSlotBoundaryDoesNotRestamp() {
        // The bug: the normal pass stamps ~13s into slot N; subtraction/cross-slot passes
        // keep re-arming into slot N+1 wall time with the same decoded slot's (merged,
        // grown) message list. All of them must be suppressed — the wall clock at stamp
        // time plays no part in the key.
        WaterfallLabelGate gate = new WaterfallLabelGate();
        long slotStart = 150_000;
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(slotStart, 3))).isTrue();
        // deep pass, still inside the slot
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(slotStart, 5))).isFalse();
        // subtraction passes finishing 1..9s into the NEXT slot
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(slotStart, 7))).isFalse();
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(slotStart, 8))).isFalse();
    }

    @Test
    public void theNextSlotsDecodeStampsOnce() {
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(150_000, 3))).isTrue();
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(150_000, 6))).isFalse();
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(165_000, 2))).isTrue();
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(165_000, 4))).isFalse();
    }

    @Test
    public void emptyListNeverStampsAndDoesNotConsumeTheGate() {
        // A pass with nothing to draw must not mark the slot as stamped: a later pass of
        // the same slot that does find messages still gets its one stamp.
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(FT8_SLOT_MS, new ArrayList<>())).isFalse();
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(150_000, 1))).isTrue();
    }

    @Test
    public void nullListNeverStamps() {
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(FT8_SLOT_MS, (ArrayList<Ft8Message>) null)).isFalse();
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(150_000, 1))).isTrue();
    }

    @Test
    public void modeChangeStampsEvenWhenTheSlotIndexCollides() {
        // FT8 slot index 150000/15000 == 10; FT4 index 75000/7500 == 10. The mode's slot
        // length stays part of the key, so the first FT4 slot after a switch still stamps.
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(150_000, 2))).isTrue();
        assertThat(gate.shouldStamp(FT4_SLOT_MS, decodedAt(75_000, 2))).isTrue();
        assertThat(gate.shouldStamp(FT4_SLOT_MS, decodedAt(75_000, 3))).isFalse();
    }

    @Test
    public void nonPositiveSlotMillisFallsBackToRawUtcKey() {
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(0, decodedAt(150_000, 1))).isTrue();
        assertThat(gate.shouldStamp(0, decodedAt(150_000, 2))).isFalse();
        assertThat(gate.shouldStamp(0, decodedAt(165_000, 1))).isTrue();
    }

    @Test
    public void resetAllowsTheSameDecodedSlotToStampAgain() {
        // A bitmap recreate wipes the stamped labels; the current slot must re-stamp.
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(150_000, 2))).isTrue();
        gate.reset();
        assertThat(gate.shouldStamp(FT8_SLOT_MS, decodedAt(150_000, 2))).isTrue();
    }
}
