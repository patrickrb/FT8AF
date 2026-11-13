package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.rigs.BaseRig;

import org.junit.Test;

/**
 * {@code MainViewModel.onCleared()} must run the rig's teardown hook: the
 * rig's poll timers are non-daemon and are cancelled nowhere else once the
 * ViewModel is gone (Copilot review on #789). The ViewModel itself cannot be
 * constructed here — its constructor starts the audio recorder and the FT8
 * listener — so the hook wiring is exercised through the static seam
 * {@code releaseRigOnClear} that {@code onCleared()} delegates to.
 */
public class MainViewModelRigCleanupTest {

    /** A rig that only records whether its teardown hook ran. */
    private static final class RecordingRig extends BaseRig {
        int disconnecting;

        @Override
        public void onDisconnecting() {
            disconnecting++;
        }

        @Override public boolean isConnected() { return false; }
        @Override public void setUsbModeToRig() { }
        @Override public void setFreqToRig() { }
        @Override public void onReceiveData(byte[] data) { }
        @Override public void readFreqFromRig() { }
        @Override public String getName() { return "recording"; }
    }

    @Test
    public void clearingTheViewModel_runsTheRigTeardownHook() {
        RecordingRig rig = new RecordingRig();
        MainViewModel.releaseRigOnClear(rig);
        assertThat(rig.disconnecting).isEqualTo(1);
    }

    @Test
    public void clearingWithNoRig_isANoOp() {
        MainViewModel.releaseRigOnClear(null);
    }
}
