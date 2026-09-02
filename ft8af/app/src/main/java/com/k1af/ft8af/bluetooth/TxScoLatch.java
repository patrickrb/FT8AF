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
 * <p>Two separate questions go in, because they have different answers:
 * <ul>
 *   <li>{@code bluetoothRigTx} — is this a Bluetooth <em>rig</em> whose TX
 *       audio must not go over SCO ({@code ScoPolicy.needControlSco()})? That
 *       is what decides whether Default output gets steered to A2DP. A USB or
 *       network rig with a Bluetooth headset picked as its mic also holds a SCO
 *       link of ours ({@code ScoPolicy.shouldEnterHeadsetMode}), but its TX
 *       audio belongs on the rig, so it answers no.</li>
 *   <li>{@code stopsSco} — does this keying path actually pause SCO around
 *       PTT? Only a control-path keying (CAT/RTS/DTR) with a rig does; a
 *       Bluetooth rig on VOX leaves SCO up and lets the audio key the rig. It
 *       still needs the steering — its media stream is on the SCO route too —
 *       so it must latch even though it stops nothing.</li>
 * </ul>
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
     * @param bluetoothRigTx     whether this TX goes to a Bluetooth rig whose
     *                           audio must not ride SCO ({@code needControlSco()}).
     *                           When false nothing is latched.
     * @param stopsSco           whether this keying pauses SCO around PTT (a
     *                           control-path keying with a rig). When false
     *                           {@code stopSco} is not run; VOX keeps SCO up.
     * @param scoUpOrPending     {@code ScoLinkCoordinator.isLinkUpOrPending()},
     *                           read here <em>before</em> {@code stopSco}
     * @param scoDeviceAddress   Bluetooth address of the device the mic is
     *                           currently captured from over SCO, or null when
     *                           unknown; read only when the link is held
     * @param stopSco            the {@code stopSco()} request, run after the
     *                           snapshot when {@code stopsSco}
     */
    public void keyDown(boolean bluetoothRigTx,
                        boolean stopsSco,
                        BooleanSupplier scoUpOrPending,
                        Supplier<String> scoDeviceAddress,
                        Runnable stopSco) {
        boolean held = bluetoothRigTx && scoUpOrPending.getAsBoolean();
        heldForTx = held;
        scoAddress = held ? scoDeviceAddress.get() : null;
        if (stopsSco) {
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
