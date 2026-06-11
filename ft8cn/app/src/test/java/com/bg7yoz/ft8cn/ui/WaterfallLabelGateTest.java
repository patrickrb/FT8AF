package com.bg7yoz.ft8cn.ui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link WaterfallLabelGate}: decoded labels stamp once per 15s cycle even
 * though FT8's normal + deep decode passes each re-arm the stamp. Pure JUnit, no Android.
 */
public class WaterfallLabelGateTest {

    @Test
    public void firstStampOfACycleIsAllowed() {
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(100)).isTrue();
    }

    @Test
    public void repeatedArmingWithinSameCycleIsSuppressed() {
        // This is the bug: the deep pass re-arms the stamp in the same cycle. The normal
        // pass stamps; every later pass in the same period must be ignored.
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(100)).isTrue();   // normal pass
        assertThat(gate.shouldStamp(100)).isFalse();  // deep pass, same cycle
        assertThat(gate.shouldStamp(100)).isFalse();  // any further re-arm
    }

    @Test
    public void eachNewCycleStampsExactlyOnce() {
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(100)).isTrue();
        assertThat(gate.shouldStamp(100)).isFalse();
        assertThat(gate.shouldStamp(101)).isTrue();   // next cycle
        assertThat(gate.shouldStamp(101)).isFalse();
        assertThat(gate.shouldStamp(102)).isTrue();
    }

    @Test
    public void periodZeroIsHandled() {
        // The sentinel is Long.MIN_VALUE, so a real period of 0 must still stamp once.
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(0)).isTrue();
        assertThat(gate.shouldStamp(0)).isFalse();
    }

    @Test
    public void resetAllowsTheSameCycleToStampAgain() {
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(100)).isTrue();
        assertThat(gate.shouldStamp(100)).isFalse();
        gate.reset();
        assertThat(gate.shouldStamp(100)).isTrue();   // after a bitmap recreate
    }

    @Test
    public void modeChangeStampsEvenWhenSlotIndexCollides() {
        // The view (and its single gate) is reused across mode changes. FT8 (15s) and
        // FT4 (7.5s) divide utcMs differently, so the new mode's slot index can equal one
        // already stamped under the old mode. Keying on slotMillis too means the first
        // slot after the switch still stamps once instead of being suppressed.
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(15_000, 5)).isTrue();   // FT8 slot 5
        assertThat(gate.shouldStamp(15_000, 5)).isFalse();  // deep pass, same slot
        assertThat(gate.shouldStamp(7_500, 5)).isTrue();    // FT4 slot 5 — same index, new mode
        assertThat(gate.shouldStamp(7_500, 5)).isFalse();   // deep pass after the switch
    }

    @Test
    public void sameModeKeyBehavesLikeTheSingleArgGate() {
        // With a fixed slotMillis the two-arg gate is once-per-slot, just like the
        // slot-index-only overload.
        WaterfallLabelGate gate = new WaterfallLabelGate();
        assertThat(gate.shouldStamp(7_500, 100)).isTrue();
        assertThat(gate.shouldStamp(7_500, 100)).isFalse();
        assertThat(gate.shouldStamp(7_500, 101)).isTrue();
    }
}
