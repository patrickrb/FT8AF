package com.k1af.ft8af.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import com.k1af.ft8af.bluetooth.ScoLinkTracker.Action;
import com.k1af.ft8af.bluetooth.ScoLinkTracker.Update;

import org.junit.Test;

public class ScoLinkTrackerTest {

    private static final int CONNECTED = AudioManager.SCO_AUDIO_STATE_CONNECTED;
    private static final int CONNECTING = AudioManager.SCO_AUDIO_STATE_CONNECTING;
    private static final int DISCONNECTED = AudioManager.SCO_AUDIO_STATE_DISCONNECTED;
    private static final int ERROR = AudioManager.SCO_AUDIO_STATE_ERROR;

    @Test
    public void firstRequestOn_starts() {
        ScoLinkTracker t = new ScoLinkTracker();
        assertThat(t.requestOn()).isEqualTo(Action.START);
        assertThat(t.isWanted()).isTrue();
        assertThat(t.isRequested()).isTrue();
        assertThat(t.attempts()).isEqualTo(1);
        // Optimistically pending until the broadcast confirms.
        assertThat(t.linkState()).isEqualTo(CONNECTING);
    }

    // Issue #759: at launch the headset-mode gate, the rig connect, and the
    // profile-connected broadcast each call setBlueToothOn() within a few ms.
    // The 2nd/3rd must NOT stop+start the link that the 1st is still building.
    @Test
    public void requestOn_whileConnecting_isNoOp() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        assertThat(t.requestOn()).isEqualTo(Action.NONE);
        t.onStateUpdate(CONNECTING, 0);
        assertThat(t.requestOn()).isEqualTo(Action.NONE);
        assertThat(t.attempts()).isEqualTo(1);
    }

    @Test
    public void requestOn_whileConnected_isNoOp() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        t.onStateUpdate(CONNECTED, 0);
        assertThat(t.requestOn()).isEqualTo(Action.NONE);
    }

    @Test
    public void requestOff_afterStart_stops_andIgnoresOwnDisconnect() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        t.onStateUpdate(CONNECTED, 0);
        assertThat(t.requestOff()).isEqualTo(Action.STOP);
        assertThat(t.isRequested()).isFalse();
        // The DISCONNECTED our own stop produces must not schedule a retry.
        Update u = t.onStateUpdate(DISCONNECTED, 100);
        assertThat(u.action).isEqualTo(Action.NONE);
        assertThat(u.retryDelayMs).isEqualTo(0);
        assertThat(u.gaveUp).isFalse();
    }

    @Test
    public void requestOff_withoutStart_isNoOp() {
        ScoLinkTracker t = new ScoLinkTracker();
        assertThat(t.requestOff()).isEqualTo(Action.NONE);
        // A sticky CONNECTED from some other app while we don't want SCO: ignore.
        assertThat(t.onStateUpdate(CONNECTED, 0).checkMicRouting).isFalse();
    }

    @Test
    public void connected_whileWanted_asksForMicRoutingCheck() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        Update u = t.onStateUpdate(CONNECTED, 0);
        assertThat(u.checkMicRouting).isTrue();
        assertThat(u.action).isEqualTo(Action.NONE);
    }

    @Test
    public void failedStart_schedulesDeferredRetry_thenRestarts() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        t.onStateUpdate(CONNECTING, 0);
        Update u = t.onStateUpdate(ERROR, 500);
        assertThat(u.action).isEqualTo(Action.NONE);
        assertThat(u.retryDelayMs).isEqualTo(1000);
        assertThat(t.attempts()).isEqualTo(2);
        // AudioService may have left our start count at 1: retry = stop+start.
        assertThat(t.onRetryDue()).isEqualTo(Action.RESTART);
        assertThat(t.linkState()).isEqualTo(CONNECTING);
    }

    @Test
    public void retryDelays_growThenCap() {
        assertThat(ScoLinkTracker.retryDelayMs(2)).isEqualTo(1000);
        assertThat(ScoLinkTracker.retryDelayMs(3)).isEqualTo(2000);
        assertThat(ScoLinkTracker.retryDelayMs(4)).isEqualTo(3000);
        assertThat(ScoLinkTracker.retryDelayMs(5)).isEqualTo(4000);
        assertThat(ScoLinkTracker.retryDelayMs(9)).isEqualTo(4000);
    }

    // stopSco() right before TX, startSco() right after: the DISCONNECTED from
    // the stop is delivered *after* the new start. It must not tear down the
    // link being built — the deferred retry sees CONNECTING and stands down.
    @Test
    public void staleDisconnectAfterRestart_retryStandsDownOnceConnecting() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        t.onStateUpdate(CONNECTED, 0);
        assertThat(t.requestOff()).isEqualTo(Action.STOP);
        assertThat(t.requestOn()).isEqualTo(Action.START);
        Update u = t.onStateUpdate(DISCONNECTED, 10); // stale, from the stop
        assertThat(u.retryDelayMs).isGreaterThan(0L);
        t.onStateUpdate(CONNECTING, 20); // the start's own broadcast
        assertThat(t.onRetryDue()).isEqualTo(Action.NONE);
        t.onStateUpdate(CONNECTED, 900);
        assertThat(t.onRetryDue()).isEqualTo(Action.NONE);
    }

    @Test
    public void retryDue_afterRequestOff_isNoOp() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        t.onStateUpdate(ERROR, 0);
        t.requestOff();
        assertThat(t.onRetryDue()).isEqualTo(Action.NONE);
    }

    @Test
    public void retries_areBounded_thenGiveUp() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn(); // attempt 1
        for (int attempt = 2; attempt <= ScoLinkTracker.MAX_ATTEMPTS; attempt++) {
            Update u = t.onStateUpdate(DISCONNECTED, attempt * 1000L);
            assertThat(u.gaveUp).isFalse();
            assertThat(u.retryDelayMs).isGreaterThan(0L);
            assertThat(t.onRetryDue()).isEqualTo(Action.RESTART);
            assertThat(t.attempts()).isEqualTo(attempt);
        }
        Update last = t.onStateUpdate(DISCONNECTED, 99_000);
        assertThat(last.gaveUp).isTrue();
        assertThat(last.retryDelayMs).isEqualTo(0);
        assertThat(t.onRetryDue()).isEqualTo(Action.NONE);
    }

    // An explicit new request (next TX cycle, rig reconnect) gets a fresh budget
    // even after a give-up, and restarts because our start count may be stale.
    @Test
    public void requestOn_afterGiveUp_restartsWithFreshBudget() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        for (int i = 0; i < ScoLinkTracker.MAX_ATTEMPTS; i++) {
            if (t.onStateUpdate(DISCONNECTED, i * 1000L).retryDelayMs > 0) {
                t.onRetryDue();
            }
        }
        assertThat(t.linkState()).isEqualTo(DISCONNECTED);
        assertThat(t.requestOn()).isEqualTo(Action.RESTART);
        assertThat(t.attempts()).isEqualTo(1);
    }

    @Test
    public void linkThatHeld_resetsBudgetWhenItDrops() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        t.onStateUpdate(DISCONNECTED, 0);   // attempt -> 2
        t.onRetryDue();
        t.onStateUpdate(DISCONNECTED, 1000); // attempt -> 3
        t.onRetryDue();
        t.onStateUpdate(CONNECTED, 2000);
        // Held for longer than STABLE_LINK_MS, then dropped: budget resets.
        Update u = t.onStateUpdate(DISCONNECTED, 2000 + ScoLinkTracker.STABLE_LINK_MS);
        assertThat(u.retryDelayMs).isEqualTo(1000);
        assertThat(t.attempts()).isEqualTo(1);
    }

    @Test
    public void linkThatFlapped_doesNotResetBudget() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        t.onStateUpdate(DISCONNECTED, 0);
        t.onRetryDue(); // attempts 2
        t.onStateUpdate(CONNECTED, 1000);
        t.onStateUpdate(DISCONNECTED, 1200); // up for 200ms only
        assertThat(t.attempts()).isEqualTo(3);
    }

    @Test
    public void connectTimeout_whileConnecting_restarts() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        t.onStateUpdate(CONNECTING, 0);
        Update u = t.onConnectTimeout();
        assertThat(u.action).isEqualTo(Action.RESTART);
        assertThat(u.gaveUp).isFalse();
        assertThat(t.attempts()).isEqualTo(2);
    }

    @Test
    public void connectTimeout_afterConnected_orOff_isNoOp() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        t.onStateUpdate(CONNECTED, 0);
        assertThat(t.onConnectTimeout().action).isEqualTo(Action.NONE);
        t.requestOff();
        assertThat(t.onConnectTimeout().action).isEqualTo(Action.NONE);
    }

    @Test
    public void connectTimeout_withBudgetSpent_givesUp() {
        ScoLinkTracker t = new ScoLinkTracker();
        t.requestOn();
        for (int i = 1; i < ScoLinkTracker.MAX_ATTEMPTS; i++) {
            assertThat(t.onConnectTimeout().action).isEqualTo(Action.RESTART);
        }
        Update u = t.onConnectTimeout();
        assertThat(u.action).isEqualTo(Action.NONE);
        assertThat(u.gaveUp).isTrue();
        assertThat(t.linkState()).isEqualTo(DISCONNECTED);
    }

    @Test
    public void needsMicReinit_onlyWhenRecordIsOffTheHeadset() {
        int sco = AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        int mic = AudioDeviceInfo.TYPE_BUILTIN_MIC;
        int wired = AudioDeviceInfo.TYPE_WIRED_HEADSET;
        int unknown = AudioDeviceInfo.TYPE_UNKNOWN;
        // Default input still on the built-in mic after SCO came up: rebuild.
        assertThat(ScoLinkTracker.needsMicReinit(mic, -1)).isTrue();
        assertThat(ScoLinkTracker.needsMicReinit(unknown, -1)).isTrue();
        // Already on the headset: leave the running capture alone.
        assertThat(ScoLinkTracker.needsMicReinit(sco, -1)).isFalse();
        assertThat(ScoLinkTracker.needsMicReinit(sco, sco)).isFalse();
        // The user pinned "Bluetooth SCO" in Settings but the OS put the record
        // elsewhere (the reporter's exact symptom): rebuild.
        assertThat(ScoLinkTracker.needsMicReinit(mic, sco)).isTrue();
        // The user pinned a non-Bluetooth input on purpose: respect it.
        assertThat(ScoLinkTracker.needsMicReinit(mic, wired)).isFalse();
        // No AudioRecord (USB-direct capture): nothing to rebuild.
        assertThat(ScoLinkTracker.needsMicReinit(-1, -1)).isFalse();
    }

    @Test
    public void stateName_coversAllStates() {
        assertThat(ScoLinkTracker.stateName(CONNECTED)).isEqualTo("CONNECTED");
        assertThat(ScoLinkTracker.stateName(CONNECTING)).isEqualTo("CONNECTING");
        assertThat(ScoLinkTracker.stateName(DISCONNECTED)).isEqualTo("DISCONNECTED");
        assertThat(ScoLinkTracker.stateName(ERROR)).isEqualTo("ERROR");
        assertThat(ScoLinkTracker.stateName(42)).isEqualTo("UNKNOWN(42)");
    }
}
