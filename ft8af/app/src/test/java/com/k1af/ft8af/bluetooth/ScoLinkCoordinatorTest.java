package com.k1af.ft8af.bluetooth;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Drives {@link ScoLinkCoordinator} on Robolectric's paused main looper: the
 * handler-facing behaviour PR #772's tracker tests leave out — timeout and retry
 * scheduling/cancellation, the CONNECTED mic-route check, cross-thread
 * serialization, and teardown.
 */
@RunWith(RobolectricTestRunner.class)
public class ScoLinkCoordinatorTest {

    private static final int CONNECTED = AudioManager.SCO_AUDIO_STATE_CONNECTED;
    private static final int CONNECTING = AudioManager.SCO_AUDIO_STATE_CONNECTING;
    private static final int DISCONNECTED = AudioManager.SCO_AUDIO_STATE_DISCONNECTED;

    /** Records every side effect in order. */
    private static final class RecordingSink implements ScoLinkCoordinator.Sink {
        final List<String> calls = new ArrayList<>();
        final List<String> log = new ArrayList<>();

        @Override public void startSco() { calls.add("start"); }
        @Override public void stopScoForRestart() { calls.add("stopForRestart"); }
        @Override public void stopSco() { calls.add("stop"); }
        @Override public void verifyMicRouting() { calls.add("verifyMic"); }
        @Override public void log(String message) { log.add(message); }
    }

    private RecordingSink sink;
    private ScoLinkTracker tracker;
    private ScoLinkCoordinator coordinator;
    private long nowMs;

    @Before
    public void setUp() {
        sink = new RecordingSink();
        tracker = new ScoLinkTracker();
        coordinator = new ScoLinkCoordinator(tracker,
                new Handler(Looper.getMainLooper()), sink, () -> nowMs);
    }

    private void advance(long ms) {
        nowMs += ms;
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS);
    }

    @Test
    public void requestOn_onMainThread_startsInline_andArmsTimeout() {
        coordinator.requestOn("test", null);
        assertThat(sink.calls).containsExactly("start");
        // Nothing until the timeout elapses...
        advance(ScoLinkTracker.CONNECT_TIMEOUT_MS - 1);
        assertThat(sink.calls).containsExactly("start");
        // ...then the failed attempt gets a spaced retry, not an instant restart.
        advance(1);
        assertThat(sink.calls).containsExactly("start");
        assertThat(tracker.attempts()).isEqualTo(2);
        advance(ScoLinkTracker.retryDelayMs(2));
        assertThat(sink.calls).containsExactly("start", "stopForRestart", "start").inOrder();
    }

    @Test
    public void connected_cancelsTimeout_andChecksMicAfterDelay() {
        coordinator.requestOn("test", null);
        coordinator.onStateUpdated(CONNECTING, DISCONNECTED);
        coordinator.onStateUpdated(CONNECTED, CONNECTING);
        assertThat(sink.calls).containsExactly("start");
        advance(ScoLinkCoordinator.MIC_ROUTE_CHECK_DELAY_MS);
        assertThat(sink.calls).containsExactly("start", "verifyMic").inOrder();
        // The connect timeout must have been dropped: no restart later.
        advance(ScoLinkTracker.CONNECT_TIMEOUT_MS * 2);
        assertThat(sink.calls).containsExactly("start", "verifyMic").inOrder();
    }

    @Test
    public void micCheck_skippedIfLinkDroppedBeforeItRuns() {
        coordinator.requestOn("test", null);
        coordinator.onStateUpdated(CONNECTED, CONNECTING);
        coordinator.requestOff("test", null);
        advance(ScoLinkCoordinator.MIC_ROUTE_CHECK_DELAY_MS);
        assertThat(sink.calls).containsExactly("start", "stop").inOrder();
    }

    @Test
    public void droppedLink_retriesAfterDelay_withRestart() {
        coordinator.requestOn("test", null);
        coordinator.onStateUpdated(CONNECTED, CONNECTING);
        advance(100);
        coordinator.onStateUpdated(DISCONNECTED, CONNECTED);
        assertThat(sink.log).contains("SCO: retry in " + ScoLinkTracker.retryDelayMs(2) + "ms");
        advance(ScoLinkTracker.retryDelayMs(2) - 1);
        assertThat(sink.calls).doesNotContain("stopForRestart");
        advance(1);
        assertThat(sink.calls).containsExactly("start", "stopForRestart", "start").inOrder();
    }

    @Test
    public void lateConnect_duringRetryDelay_cancelsRetry() {
        coordinator.requestOn("test", null);
        advance(ScoLinkTracker.CONNECT_TIMEOUT_MS); // timeout -> retry scheduled
        coordinator.onStateUpdated(CONNECTED, CONNECTING);
        advance(ScoLinkTracker.retryDelayMs(2) + 1);
        assertThat(sink.calls).containsExactly("start", "verifyMic").inOrder();
    }

    @Test
    public void requestOn_whileConnecting_isLoggedAsIgnored_notApplied() {
        List<String> applied = new ArrayList<>();
        coordinator.requestOn("first", () -> applied.add("first"));
        coordinator.requestOn("second", () -> applied.add("second"));
        assertThat(applied).containsExactly("first");
        assertThat(sink.calls).containsExactly("start");
        assertThat(sink.log).contains("SCO: second ignored, link already CONNECTING");
    }

    @Test
    public void isLinkUpOrPending_tracksTheTrackerNotAudioManager() {
        // The headset-mode refresh cross-checks this instead of isBluetoothScoOn()
        // (Copilot review on #778).
        assertThat(coordinator.isLinkUpOrPending()).isFalse();
        coordinator.requestOn("test", null);
        assertThat(coordinator.isLinkUpOrPending()).isTrue();   // optimistic CONNECTING
        coordinator.onStateUpdated(CONNECTED, CONNECTING);
        assertThat(coordinator.isLinkUpOrPending()).isTrue();
        // Dropped underneath us: still wanted (held) but no longer up/pending.
        coordinator.onStateUpdated(DISCONNECTED, CONNECTED);
        assertThat(coordinator.isLinkUpOrPending()).isFalse();
        assertThat(coordinator.isWanted()).isTrue();
        // Released: neither.
        coordinator.requestOff("test", null);
        assertThat(coordinator.isLinkUpOrPending()).isFalse();
        assertThat(coordinator.isWanted()).isFalse();
    }

    @Test
    public void requestOff_withoutStart_doesNothing() {
        List<String> applied = new ArrayList<>();
        coordinator.requestOff("test", () -> applied.add("off"));
        assertThat(applied).isEmpty();
        assertThat(sink.calls).isEmpty();
    }

    // Copilot on PR #772: TX calls stopSco()/startSco() from its executor while
    // broadcasts run on the main thread; every decision + application must land
    // on the handler thread in submission order.
    @Test
    public void requestsFromAnotherThread_arePostedAndAppliedInOrder() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Thread tx = new Thread(() -> {
            coordinator.requestOn("tx-end", null);
            coordinator.requestOff("tx-begin", null);
            coordinator.requestOn("tx-end", null);
            done.countDown();
        });
        tx.start();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        // Nothing applied on the caller's thread.
        assertThat(sink.calls).isEmpty();
        shadowOf(Looper.getMainLooper()).idle();
        assertThat(sink.calls).containsExactly("start", "stop", "start").inOrder();
    }

    @Test
    public void shutdown_cancelsTimers_andBalancesOutstandingStart() {
        coordinator.requestOn("test", null);
        coordinator.shutdown();
        assertThat(sink.calls).containsExactly("start", "stop").inOrder();
        assertThat(tracker.isRequested()).isFalse();
        // No retry/timeout survives the teardown.
        advance(ScoLinkTracker.CONNECT_TIMEOUT_MS + ScoLinkTracker.retryDelayMs(2) + 1);
        assertThat(sink.calls).containsExactly("start", "stop").inOrder();
    }

    @Test
    public void shutdown_withNothingOutstanding_isQuiet() {
        coordinator.shutdown();
        assertThat(sink.calls).isEmpty();
    }

    @Test
    public void retriesExhausted_logsGiveUp_andStopsScheduling() {
        coordinator.requestOn("test", null);
        for (int i = 1; i < ScoLinkTracker.MAX_ATTEMPTS; i++) {
            advance(ScoLinkTracker.CONNECT_TIMEOUT_MS);
            advance(ScoLinkTracker.retryDelayMs(i + 1));
        }
        int starts = (int) sink.calls.stream().filter("start"::equals).count();
        assertThat(starts).isEqualTo(ScoLinkTracker.MAX_ATTEMPTS);
        advance(ScoLinkTracker.CONNECT_TIMEOUT_MS);
        assertThat(sink.log.get(sink.log.size() - 1)).contains("giving up");
        advance(60_000);
        assertThat((int) sink.calls.stream().filter("start"::equals).count())
                .isEqualTo(ScoLinkTracker.MAX_ATTEMPTS);
    }
}
