package com.k1af.ft8af.bluetooth;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Remembers whether <em>this app</em> held a Bluetooth SCO session for the rig
 * at the moment an over (or the tune carrier) was keyed — and on which device —
 * so the TX audio path can still act on that after the keying sequence has
 * already asked for the link to drop.
 *
 * <p>Why a latch rather than a live query (Copilot review on #790): the keying
 * path calls {@code stopSco()} <em>before</em> PTT, then the TX worker sleeps
 * the PTT settle delay (100 ms by default) before it builds the
 * {@code AudioTrack} and asks whether to steer Default output to A2DP.
 * {@code stopSco()} posts {@code ScoLinkTracker.requestOff()} to the main
 * looper, which flips the tracked state to DISCONNECTED as soon as it runs — so
 * by the time the question is asked the honest answer from the tracker is
 * "no", the override never fires in exactly the CAT/RTS/DTR + Bluetooth case it
 * exists for, and if the main looper happens to be busy the answer becomes a
 * race. Meanwhile the OS is still tearing the SCO link down and keeps
 * {@code USAGE_MEDIA} on the hands-free route (issue #759 follow-up), which is
 * the failure the steering fixes.
 *
 * <p>Why the ordering lives here: {@link #keyDown} performs the snapshot and
 * the stop itself, in that order, so the sequence the TX path depends on is
 * a single tested call rather than two statements in
 * {@code MainViewModel.beginKeying()} that could be reordered without any test
 * noticing.
 *
 * <p>Why {@code controlsSco}: a USB or network rig with a Bluetooth headset
 * picked as its mic also holds a SCO link of ours
 * ({@code ScoPolicy.shouldEnterHeadsetMode}), but that TX path neither stops
 * SCO nor wants its audio moved off the rig onto the headset's A2DP. Only the
 * path that actually pauses SCO around PTT for a Bluetooth rig may latch.
 *
 * <p>A mid-slot message swap (#704) re-keys nothing, so the latch carries
 * across it. Pure Java, no Android types; the ordering is unit-tested against
 * the real {@link ScoLinkTracker}.
 */
public final class TxScoLatch {

    private volatile boolean heldForTx;
    private volatile String scoAddress;

    /**
     * The production keying order: snapshot the link, then stop it.
     *
     * @param controlsSco        whether this keying pauses SCO around PTT for a
     *                           Bluetooth rig ({@code needControlSco()} on a
     *                           control-path keying with a rig). When false
     *                           nothing is latched and {@code stopSco} is not run.
     * @param scoUpOrPending     {@code ScoLinkCoordinator.isLinkUpOrPending()},
     *                           read here <em>before</em> {@code stopSco}
     * @param scoDeviceAddress   Bluetooth address of the device the mic is
     *                           currently captured from over SCO, or null when
     *                           unknown; read only when the link is held
     * @param stopSco            the {@code stopSco()} request, run after the
     *                           snapshot when {@code controlsSco}
     */
    public void keyDown(boolean controlsSco,
                        BooleanSupplier scoUpOrPending,
                        Supplier<String> scoDeviceAddress,
                        Runnable stopSco) {
        boolean held = controlsSco && scoUpOrPending.getAsBoolean();
        heldForTx = held;
        scoAddress = held ? scoDeviceAddress.get() : null;
        if (controlsSco) {
            stopSco.run();
        }
    }

    /**
     * Whether our SCO link for the rig was up (or coming up) when the current
     * over was keyed. Stable for the whole over regardless of what the tracker
     * says now.
     */
    public boolean heldForTx() {
        return heldForTx;
    }

    /**
     * Address of the device that link was on at keying time, or null when the
     * platform withheld it (or nothing is held). Lets the routing policy pick
     * <em>that</em> device's A2DP endpoint rather than the first hands-free
     * device that happens to enumerate one.
     */
    public String scoAddress() {
        return scoAddress;
    }

    /** The over is done and the post-TX {@code startSco()} has been requested. */
    public void keyUp() {
        heldForTx = false;
        scoAddress = null;
    }
}
