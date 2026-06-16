package com.bg7yoz.ft8cn.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests for {@link FT8TransmitSignal#shouldSuppressIdleHunt} — the gate
 * that decides whether Hunt should stay silent (normal Hunt) or fall through
 * to CQ (Hunt+CQ hybrid mode).
 */
public class HuntCqHybridTest {

    private static TransmitCallsign noTarget() {
        // null callsign → haveTargetCallsign() == false
        return new TransmitCallsign(1, 0, null, 0);
    }

    private static TransmitCallsign withTarget(String call) {
        return new TransmitCallsign(1, 0, call, 0);
    }

    // ---- Normal Hunt (huntCallsCQ = false) ----

    @Test
    public void normalHunt_idleState_suppressesTransmit() {
        // Hunt on, order 6 (CQ baseline), no target → should suppress
        assertThat(FT8TransmitSignal.shouldSuppressIdleHunt(
                false, true, 6, noTarget())).isTrue();
    }

    @Test
    public void normalHunt_idleWithNullCallsign_suppressesTransmit() {
        assertThat(FT8TransmitSignal.shouldSuppressIdleHunt(
                false, true, 6, null)).isTrue();
    }

    @Test
    public void normalHunt_activeQso_doesNotSuppress() {
        // Hunt on, order 2 (sending RPT), has target → active QSO, don't suppress
        assertThat(FT8TransmitSignal.shouldSuppressIdleHunt(
                false, true, 2, withTarget("W1AW"))).isFalse();
    }

    @Test
    public void normalHunt_huntOff_doesNotSuppress() {
        // Hunt off → not in hunt mode at all
        assertThat(FT8TransmitSignal.shouldSuppressIdleHunt(
                false, false, 6, noTarget())).isFalse();
    }

    // ---- Hunt+CQ mode (huntCallsCQ = true) ----

    @Test
    public void huntCq_idleState_doesNotSuppress() {
        // Hunt+CQ on, idle → should NOT suppress, let CQ transmit
        assertThat(FT8TransmitSignal.shouldSuppressIdleHunt(
                true, true, 6, noTarget())).isFalse();
    }

    @Test
    public void huntCq_idleWithNullCallsign_doesNotSuppress() {
        assertThat(FT8TransmitSignal.shouldSuppressIdleHunt(
                true, true, 6, null)).isFalse();
    }

    @Test
    public void huntCq_activeQso_doesNotSuppress() {
        // Active QSO in Hunt+CQ → don't suppress either
        assertThat(FT8TransmitSignal.shouldSuppressIdleHunt(
                true, true, 2, withTarget("W1AW"))).isFalse();
    }

    @Test
    public void huntCq_huntOff_doesNotSuppress() {
        // huntCallsCQ true but Hunt itself off → never suppresses
        assertThat(FT8TransmitSignal.shouldSuppressIdleHunt(
                true, false, 6, noTarget())).isFalse();
    }
}
