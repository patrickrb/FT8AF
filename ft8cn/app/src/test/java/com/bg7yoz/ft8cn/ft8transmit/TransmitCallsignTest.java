package com.bg7yoz.ft8cn.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for the pure logic on the {@link TransmitCallsign} data holder:
 *   - {@link TransmitCallsign#haveTargetCallsign()} (null/"CQ" => no target)
 *   - {@link TransmitCallsign#getSnr()} (signed-decimal formatting; '+' only when > 0)
 *
 * Plain JUnit: TransmitCallsign has no Android runtime dependency.
 */
public class TransmitCallsignTest {

    private static TransmitCallsign withCallsign(String call) {
        // 4-arg ctor: (i3, n3, callsign, sequential)
        return new TransmitCallsign(1, 0, call, 0);
    }

    private static TransmitCallsign withSnr(int snr) {
        // 6-arg ctor: (i3, n3, callsign, frequency, sequential, snr)
        return new TransmitCallsign(1, 0, "K1ABC", 1500f, 0, snr);
    }

    @Test
    public void haveTargetCallsign_nullCallsign_isFalse() {
        assertThat(withCallsign(null).haveTargetCallsign()).isFalse();
    }

    @Test
    public void haveTargetCallsign_cqCallsign_isFalse() {
        // Exactly "CQ" means we are calling generally, not a specific station.
        assertThat(withCallsign("CQ").haveTargetCallsign()).isFalse();
    }

    @Test
    public void haveTargetCallsign_realCallsign_isTrue() {
        assertThat(withCallsign("K1ABC").haveTargetCallsign()).isTrue();
    }

    @Test
    public void getSnr_positive_hasPlusSign() {
        assertThat(withSnr(7).getSnr()).isEqualTo("+7");
    }

    @Test
    public void getSnr_zero_hasNoPlusSign() {
        // The branch is snr>0, so 0 falls to the plain "%d" form.
        assertThat(withSnr(0).getSnr()).isEqualTo("0");
    }

    @Test
    public void getSnr_negative_keepsMinusSign() {
        assertThat(withSnr(-12).getSnr()).isEqualTo("-12");
    }

    @Test
    public void sixArgConstructor_storesAllFields() {
        TransmitCallsign tc = new TransmitCallsign(2, 3, "VE3XY", 2500f, 1, 5);
        assertThat(tc.i3).isEqualTo(2);
        assertThat(tc.n3).isEqualTo(3);
        assertThat(tc.callsign).isEqualTo("VE3XY");
        assertThat(tc.frequency).isEqualTo(2500f);
        assertThat(tc.sequential).isEqualTo(1);
        assertThat(tc.snr).isEqualTo(5);
    }
}
