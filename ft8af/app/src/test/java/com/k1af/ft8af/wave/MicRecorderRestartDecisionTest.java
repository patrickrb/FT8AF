package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link MicRecorder#shouldRestartAfterReinitialize} -- the restart
 * decision behind the {@code shouldBeRunning} fix (a transient
 * {@code AudioRecord} creation failure right after a replug/power-cycle used
 * to permanently wedge capture, because {@link MicRecorder#reinitialize()}
 * used to key its restart decision off {@code isRunning}, which that failure
 * also flips to false with no owner action).
 */
public class MicRecorderRestartDecisionTest {

    @Test
    public void restarts_afterTransientFailureLeftIsRunningFalse() {
        // start() set shouldBeRunning=true; a transient AudioRecord failure
        // (not stopRecord()) later set isRunning=false as a side effect, but
        // shouldBeRunning is untouched -- reinitialize() must still restart.
        assertThat(MicRecorder.shouldRestartAfterReinitialize(true)).isTrue();
    }

    @Test
    public void doesNotRestart_afterOwnerCalledStopRecord() {
        // stopRecord() clears shouldBeRunning -- reinitialize() (e.g. from a
        // later USB attach event) must not restart capture the operator stopped.
        assertThat(MicRecorder.shouldRestartAfterReinitialize(false)).isFalse();
    }
}
