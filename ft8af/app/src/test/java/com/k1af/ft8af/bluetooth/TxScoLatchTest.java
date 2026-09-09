package com.k1af.ft8af.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import android.media.AudioManager;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ordering regression for the A2DP TX steering (#790). The keying path stops
 * SCO before the TX worker decides where Default output should go, so the
 * decision must come from what the link looked like at keying time. That
 * order is {@link TxScoLatch#keyDown}'s own — these tests hand it the real
 * {@link ScoLinkTracker} as both the thing to snapshot and the thing to stop,
 * so if the snapshot ever moved after the stop the latch would read the
 * already-dropped link and the first test would fail.
 */
public class TxScoLatchTest {

    private static final String RIG = "AA:BB:CC:DD:EE:FF";

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
    public void keyDown_snapshotsBeforeItStopsTheLink() {
        ScoLinkTracker tracker = connectedTracker();
        TxScoLatch latch = new TxScoLatch();

        // beginKeying() for a Bluetooth rig keyed via CAT/RTS/DTR.
        latch.keyDown(true, true, () -> upOrPending(tracker), () -> RIG, tracker::requestOff);

        // The stop really ran (this is what playFT8Signal sees after the PTT
        // settle delay, and what the first version of #790 queried)...
        assertThat(upOrPending(tracker)).isFalse();
        // ...but the latch answers for keying time.
        assertThat(latch.heldForTx()).isTrue();
        assertThat(latch.scoAddress()).isEqualTo(RIG);
    }

    @Test
    public void bluetoothRigOnVox_latchesWithoutStopping() {
        // Bluetooth rig keyed by VOX: needControlSco() is true (the audio must
        // not ride SCO) but no control path pauses SCO around PTT. The steering
        // is still needed — the media stream is on the SCO route — so the latch
        // must engage while stopSco() stays untouched (Copilot review on #790).
        ScoLinkTracker tracker = connectedTracker();
        TxScoLatch latch = new TxScoLatch();
        AtomicInteger stops = new AtomicInteger();

        latch.keyDown(true, false, () -> upOrPending(tracker), () -> RIG, stops::incrementAndGet);

        assertThat(latch.heldForTx()).isTrue();
        assertThat(latch.scoAddress()).isEqualTo(RIG);
        assertThat(stops.get()).isEqualTo(0);
        assertThat(upOrPending(tracker)).isTrue();
    }

    @Test
    public void nonBluetoothRig_latchesNothingAndDoesNotStop() {
        // USB/network rig with a Bluetooth headset selected as the mic: our SCO
        // link is up, but this TX is not to a Bluetooth rig and its audio
        // belongs on the rig, not the headset's A2DP.
        ScoLinkTracker tracker = connectedTracker();
        TxScoLatch latch = new TxScoLatch();
        AtomicInteger stops = new AtomicInteger();

        latch.keyDown(false, false, () -> upOrPending(tracker), () -> RIG, stops::incrementAndGet);

        assertThat(latch.heldForTx()).isFalse();
        assertThat(latch.scoAddress()).isNull();
        assertThat(stops.get()).isEqualTo(0);
        assertThat(upOrPending(tracker)).isTrue();
    }

    @Test
    public void keyDown_withNoLink_stillStopsButLatchesFalse() {
        // Bluetooth rig, link never came up: stopSco() is still requested (the
        // tracker answers NONE), and nothing is latched.
        ScoLinkTracker tracker = new ScoLinkTracker();
        TxScoLatch latch = new TxScoLatch();
        AtomicInteger stops = new AtomicInteger();

        latch.keyDown(true, true, () -> upOrPending(tracker), () -> RIG, stops::incrementAndGet);

        assertThat(latch.heldForTx()).isFalse();
        assertThat(latch.scoAddress()).isNull();
        assertThat(stops.get()).isEqualTo(1);
    }

    @Test
    public void pendingLinkCountsAsHeld() {
        // A link still CONNECTING when TX starts is ours too; the OS will have
        // the media route on SCO by the time audio plays.
        ScoLinkTracker tracker = new ScoLinkTracker();
        tracker.requestOn();
        assertThat(tracker.linkState()).isEqualTo(AudioManager.SCO_AUDIO_STATE_CONNECTING);
        TxScoLatch latch = new TxScoLatch();

        latch.keyDown(true, true, () -> upOrPending(tracker), () -> null, tracker::requestOff);

        assertThat(latch.heldForTx()).isTrue();
        assertThat(latch.scoAddress()).isNull();
    }

    @Test
    public void keyUpClearsTheLatch_andTheNextOverReTakesIt() {
        ScoLinkTracker tracker = connectedTracker();
        TxScoLatch latch = new TxScoLatch();

        latch.keyDown(true, true, () -> upOrPending(tracker), () -> RIG, tracker::requestOff);
        // endKeying(): startSco() requested, then the latch is released.
        tracker.requestOn();
        latch.keyUp();
        assertThat(latch.heldForTx()).isFalse();
        assertThat(latch.scoAddress()).isNull();

        // Next over: the headset went away in between, so this time the answer
        // is genuinely no and must not be carried over from the last over.
        tracker.requestOff();
        latch.keyDown(true, true, () -> upOrPending(tracker), () -> RIG, tracker::requestOff);
        assertThat(latch.heldForTx()).isFalse();

        // ...and comes back once the link is up again.
        tracker.requestOn();
        tracker.onStateUpdate(AudioManager.SCO_AUDIO_STATE_CONNECTED, 2_000L);
        latch.keyDown(true, true, () -> upOrPending(tracker), () -> RIG, tracker::requestOff);
        assertThat(latch.heldForTx()).isTrue();
        assertThat(latch.scoAddress()).isEqualTo(RIG);
    }
}
