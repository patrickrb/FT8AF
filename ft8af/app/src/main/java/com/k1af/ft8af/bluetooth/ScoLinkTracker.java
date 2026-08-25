package com.k1af.ft8af.bluetooth;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;

/**
 * Tracks the phone's Bluetooth SCO (hands-free audio) link on behalf of the
 * app and decides what to do with {@code AudioManager} at each step. Pure
 * logic — no Android calls — so the whole state machine is unit-testable.
 *
 * <p>Why this exists (issue #759, Android 8.x Bluetooth RX dead): the app used
 * to fire {@code stopBluetoothSco(); startBluetoothSco();} blindly on every
 * "turn Bluetooth on" event, and several of those fire back-to-back at launch
 * (headset-mode gate, rig connect, profile-connected broadcast). Stopping a
 * request that is still CONNECTING abandons the half-built link in the
 * Bluetooth stack — on Oreo the stale virtual call then makes every later
 * start a silent no-op ("Call in progress"). The
 * {@code ACTION_SCO_AUDIO_STATE_UPDATED} broadcast was registered but never
 * handled, so a failed or dropped link was never retried, nothing was logged,
 * and the {@code AudioRecord} created before SCO came up was never re-created
 * once it did (Android's documented recipe is "start recording after
 * SCO_AUDIO_STATE_CONNECTED").
 *
 * <p>Rules encoded here:
 * <ul>
 *   <li>A request to turn SCO on while it is already CONNECTING/CONNECTED is a
 *       no-op — never restart a link that is being built.</li>
 *   <li>{@code startBluetoothSco()} is reference-counted per client in
 *       AudioService, and a start that ends in DISCONNECTED/ERROR can leave the
 *       count at 1, where a second bare start does nothing. Every retry is
 *       therefore a stop+start ({@link Action#RESTART}).</li>
 *   <li>Retries are bounded ({@link #MAX_ATTEMPTS}) and spaced
 *       ({@link #retryDelayMs}); a link that stayed up for
 *       {@link #STABLE_LINK_MS} earns a fresh budget when it drops.</li>
 *   <li>A start that never reports CONNECTED within
 *       {@link #CONNECT_TIMEOUT_MS} counts as failed.</li>
 *   <li>Once CONNECTED, the mic must actually be capturing from the SCO device;
 *       if the OS did not re-route the existing AudioRecord, re-create it
 *       ({@link #needsMicReinit}).</li>
 * </ul>
 */
public final class ScoLinkTracker {

    /** What the caller should do to {@code AudioManager} right now. */
    public enum Action {
        NONE,
        /** {@code startBluetoothSco()}. */
        START,
        /** {@code stopBluetoothSco()} then {@code startBluetoothSco()}. */
        RESTART,
        /** {@code stopBluetoothSco()}. */
        STOP
    }

    /** Outcome of a state broadcast. */
    public static final class Update {
        /** Immediate action, if any. */
        public final Action action;
        /** {@code > 0}: schedule {@link #onRetryDue()} after this many ms. */
        public final long retryDelayMs;
        /** The link is up and wanted — verify the mic is routed to it. */
        public final boolean checkMicRouting;
        /** Retries exhausted; the caller should say so in the log. */
        public final boolean gaveUp;

        Update(Action action, long retryDelayMs, boolean checkMicRouting, boolean gaveUp) {
            this.action = action;
            this.retryDelayMs = retryDelayMs;
            this.checkMicRouting = checkMicRouting;
            this.gaveUp = gaveUp;
        }

        static final Update NONE = new Update(Action.NONE, 0, false, false);
    }

    /** Total start attempts (initial + retries) per want-session. */
    static final int MAX_ATTEMPTS = 5;
    /** No CONNECTED within this many ms of a start = failed attempt. */
    public static final long CONNECT_TIMEOUT_MS = 6000;
    /** A link that held this long resets the retry budget when it drops. */
    static final long STABLE_LINK_MS = 5000;
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 3000, 4000};

    private boolean wanted;
    /** A startBluetoothSco() has been issued and not yet balanced by a stop. */
    private boolean requested;
    private int linkState = AudioManager.SCO_AUDIO_STATE_DISCONNECTED;
    private int attempts;
    private long connectedAtMs = -1;
    /** A retry was handed out by {@link #onStateUpdate} and not yet consumed. */
    private boolean retryPending;

    public synchronized boolean isWanted() {
        return wanted;
    }

    public synchronized boolean isRequested() {
        return requested;
    }

    public synchronized int linkState() {
        return linkState;
    }

    /** Attempts made in the current want-session (1 = the initial start). */
    public synchronized int attempts() {
        return attempts;
    }

    /** Delay before retry number {@code attempt} (2 = first retry). */
    static long retryDelayMs(int attempt) {
        int idx = Math.max(0, Math.min(RETRY_DELAYS_MS.length - 1, attempt - 2));
        return RETRY_DELAYS_MS[idx];
    }

    private boolean linkUpOrPending() {
        return linkState == AudioManager.SCO_AUDIO_STATE_CONNECTING
                || linkState == AudioManager.SCO_AUDIO_STATE_CONNECTED;
    }

    /** The app wants SCO up (headset mode / after TX). */
    public synchronized Action requestOn() {
        wanted = true;
        if (requested && linkUpOrPending()) {
            return Action.NONE;
        }
        attempts = 1;
        retryPending = false;
        Action action = requested ? Action.RESTART : Action.START;
        markStarted();
        return action;
    }

    /** The app wants SCO down (before TX / leaving headset mode). */
    public synchronized Action requestOff() {
        wanted = false;
        attempts = 0;
        connectedAtMs = -1;
        retryPending = false;
        if (!requested) {
            return Action.NONE;
        }
        requested = false;
        linkState = AudioManager.SCO_AUDIO_STATE_DISCONNECTED;
        return Action.STOP;
    }

    /**
     * An {@code ACTION_SCO_AUDIO_STATE_UPDATED} broadcast arrived.
     *
     * @param state {@code AudioManager.SCO_AUDIO_STATE_*}
     * @param nowMs monotonic clock, for the stable-link check
     */
    public synchronized Update onStateUpdate(int state, long nowMs) {
        int previous = linkState;
        linkState = state;
        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
            connectedAtMs = nowMs;
            return wanted ? new Update(Action.NONE, 0, true, false) : Update.NONE;
        }
        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTING) {
            return Update.NONE;
        }
        // DISCONNECTED or ERROR. Our own STOP clears `wanted` first, so a
        // broadcast that lands here while wanted means the link failed or
        // dropped underneath us. It may also be the *stale* DISCONNECTED from a
        // stop we issued right before a fresh start — that is why the retry is
        // deferred and re-checked in onRetryDue() rather than fired now.
        if (!wanted) {
            return Update.NONE;
        }
        if (previous == AudioManager.SCO_AUDIO_STATE_CONNECTED
                && connectedAtMs >= 0 && nowMs - connectedAtMs >= STABLE_LINK_MS) {
            attempts = 0; // the link held; a drop after that gets a fresh budget
        }
        connectedAtMs = -1;
        if (attempts >= MAX_ATTEMPTS) {
            return new Update(Action.NONE, 0, false, true);
        }
        attempts++;
        retryPending = true;
        return new Update(Action.NONE, retryDelayMs(attempts), false, false);
    }

    /** A retry scheduled by {@link #onStateUpdate} is due. */
    public synchronized Action onRetryDue() {
        if (!retryPending) {
            return Action.NONE; // nothing scheduled (e.g. after a give-up)
        }
        retryPending = false;
        if (!wanted || linkUpOrPending()) {
            return Action.NONE; // a later broadcast overtook the retry
        }
        Action action = requested ? Action.RESTART : Action.START;
        markStarted();
        return action;
    }

    /**
     * {@link #CONNECT_TIMEOUT_MS} elapsed since the last start with no
     * CONNECTED. Returns the action to take; {@code gaveUp} in the update is
     * set when the budget is spent.
     */
    public synchronized Update onConnectTimeout() {
        if (!wanted || linkState != AudioManager.SCO_AUDIO_STATE_CONNECTING) {
            return Update.NONE;
        }
        if (attempts >= MAX_ATTEMPTS) {
            linkState = AudioManager.SCO_AUDIO_STATE_DISCONNECTED;
            return new Update(Action.NONE, 0, false, true);
        }
        attempts++;
        retryPending = false;
        markStarted();
        return new Update(Action.RESTART, 0, false, false);
    }

    private void markStarted() {
        requested = true;
        // Optimistic: the CONNECTING broadcast is asynchronous, and a second
        // requestOn() a few ms later must already see the link as pending.
        linkState = AudioManager.SCO_AUDIO_STATE_CONNECTING;
        connectedAtMs = -1;
    }

    /**
     * Whether the capture path must be rebuilt now that SCO is CONNECTED.
     *
     * @param routedDeviceType {@code AudioDeviceInfo.TYPE_*} the running
     *        AudioRecord is actually capturing from; {@code TYPE_UNKNOWN} if the
     *        OS reports none yet; {@code -1} if there is no AudioRecord at all
     *        (USB-direct capture or nothing open).
     * @param chosenDeviceType type of the input the user pinned in Settings, or
     *        {@code -1} for "system default".
     */
    public static boolean needsMicReinit(int routedDeviceType, int chosenDeviceType) {
        if (routedDeviceType < 0) {
            return false; // nothing to rebuild (USB direct / no AudioRecord)
        }
        if (routedDeviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return false; // already capturing from the headset
        }
        // The user explicitly pinned a non-Bluetooth input: respect it.
        return chosenDeviceType < 0 || chosenDeviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
    }

    /** Debug-log name for an {@code AudioManager.SCO_AUDIO_STATE_*} value. */
    public static String stateName(int state) {
        switch (state) {
            case AudioManager.SCO_AUDIO_STATE_DISCONNECTED:
                return "DISCONNECTED";
            case AudioManager.SCO_AUDIO_STATE_CONNECTED:
                return "CONNECTED";
            case AudioManager.SCO_AUDIO_STATE_CONNECTING:
                return "CONNECTING";
            case AudioManager.SCO_AUDIO_STATE_ERROR:
                return "ERROR";
            default:
                return "UNKNOWN(" + state + ")";
        }
    }
}
