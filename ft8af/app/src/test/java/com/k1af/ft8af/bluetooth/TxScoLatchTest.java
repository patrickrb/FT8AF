package com.k1af.ft8af.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import android.media.AudioManager;

import org.junit.Test;

/**
 * Ordering regression for the A2DP TX steering (#790): the keying path stops
 * SCO before the TX worker decides where Default output should go, so the
 * decision must be made from what the link looked like at keying time, not
 * from a live tracker query after the stop. Drives the real
 * {@link ScoLinkTracker} through the same sequence {@code MainViewModel}
 * uses so the test fails if that ordering ever changes underneath the latch.
 */
public class TxScoLatchTest {

    /** {@code ScoLinkCoordinator.isLinkUpOrPending()} without the handler. */
    private static boolean upOrPending(ScoLinkTracker t) {
        int s = t.linkState();
        return s == AudioManager.SCO_AUDIO_STATE_CONNECTING
                || s == AudioManager.SCO_AUDIO_STATE_CONNECTED;
    }

    private static ScoLinkTracker connectedTracker() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        t.onStateUpdate(AudioManager.SCO_AUDIO_STATE_CONNECTED, 1_000L);
        assertThat(upOrPending(t)).isTrue();
        return t;
    }

    @Test
    public void stopBeforePlayback_latchStillSaysScoWasHeld() {
        ScoLinkTracker tracker = connectedTracker();
        TxScoLatch latch = new TxScoLatch();

        // beginKeying(): snapshot first, then stopSco() -> tracker.requestOff().
        latch.onKeying(upOrPending(tracker));
        tracker.requestOff();

        // PTT settle delay elapses; playFT8Signal() now asks the question. The
        // live query is what the first version of #790 used — and it is already
        // false, so the override never ran. The latch is the answer to use.
        assertThat(upOrPending(tracker)).isFalse();
        assertThat(latch.heldForTx()).isTrue();
    }

    @Test
    public void noScoAtKeying_latchStaysFalse() {
        // USB/network rig with no SCO of ours: nothing to steer around, and the
        // latch must not invent a session.
        ScoLinkTracker tracker = new ScoLinkTracker();
        TxScoLatch latch = new TxScoLatch();

        latch.onKeying(upOrPending(tracker));
        tracker.requestOff();

        assertThat(latch.heldForTx()).isFalse();
    }

    @Test
    public void pendingLinkCountsAsHeld() {
        // A link still CONNECTING when TX starts is ours too; the OS will have
        // the media route on SCO by the time audio plays.
        ScoLinkTracker tracker = new ScoLinkTracker();
        tracker.requestOn();
        assertThat(tracker.linkState()).isEqualTo(AudioManager.SCO_AUDIO_STATE_CONNECTING);
        TxScoLatch latch = new TxScoLatch();

        latch.onKeying(upOrPending(tracker));
        tracker.requestOff();

        assertThat(latch.heldForTx()).isTrue();
    }

    @Test
    public void unkeyClearsTheLatch_andTheNextOverReTakesIt() {
        ScoLinkTracker tracker = connectedTracker();
        TxScoLatch latch = new TxScoLatch();

        latch.onKeying(upOrPending(tracker));
        tracker.requestOff();
        // endKeying(): startSco() requested, then the latch is released.
        tracker.requestOn();
        latch.onUnkeyed();
        assertThat(latch.heldForTx()).isFalse();

        // Next over: the headset went away in between, so this time the answer
        // is genuinely no and must not be carried over from the last over.
        tracker.requestOff();
        latch.onKeying(upOrPending(tracker));
        assertThat(latch.heldForTx()).isFalse();

        // ...and comes back once the link is up again.
        tracker.requestOn();
        tracker.onStateUpdate(AudioManager.SCO_AUDIO_STATE_CONNECTED, 2_000L);
        latch.onKeying(upOrPending(tracker));
        tracker.requestOff();
        assertThat(latch.heldForTx()).isTrue();
    }
}
