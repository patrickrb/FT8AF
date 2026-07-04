package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link FT8TransmitSignal#shouldWarnTxDropped(boolean, boolean)}.
 *
 * <p>A dropped USB TX write is invisible at the rig (it keys and transmits dead
 * air), so the operator gets an on-screen warning — but only for real failures,
 * not for their own STOP press.
 *
 * <p>Plain JUnit: the helper is pure logic. Kept in its own file (rather than
 * appended to {@code FT8TransmitSignalTest}) so this PR doesn't collide with
 * concurrent test additions to that class.
 */
public class TxDropWarningTest {

    @Test
    public void warnTxDropped_realFailure_warns() {
        assertThat(FT8TransmitSignal.shouldWarnTxDropped(
                /*success*/ false, /*cancelled*/ false)).isTrue();
    }

    @Test
    public void warnTxDropped_userStop_doesNotWarn() {
        assertThat(FT8TransmitSignal.shouldWarnTxDropped(false, true)).isFalse();
    }

    @Test
    public void warnTxDropped_success_doesNotWarn() {
        assertThat(FT8TransmitSignal.shouldWarnTxDropped(true, false)).isFalse();
        assertThat(FT8TransmitSignal.shouldWarnTxDropped(true, true)).isFalse();
    }
}
