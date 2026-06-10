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
}
