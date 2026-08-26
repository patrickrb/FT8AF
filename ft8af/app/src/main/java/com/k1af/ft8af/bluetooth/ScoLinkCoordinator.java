package com.k1af.ft8af.bluetooth;

import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.util.function.LongSupplier;

/**
 * Drives a {@link ScoLinkTracker} against the real world: every request,
 * broadcast, retry and timeout is applied on one {@link Handler} thread, in
 * order, so the tracker's decision and its {@code AudioManager} side effects
 * are never interleaved with another caller's.
 *
 * <p>Why the single-thread rule (Copilot review on PR #772): TX asks for
 * {@code stopSco()}/{@code startSco()} from the transmit executor while the
 * {@code ACTION_SCO_AUDIO_STATE_UPDATED} broadcast and the retry/timeout
 * callbacks run on the main thread. Without funnelling, a request could change
 * the tracker between another caller's decision and its application, letting
 * an obsolete START run after a newer STOP (or vice versa). Calls that arrive
 * on the handler's own looper run inline; all others are posted.
 *
 * <p>Everything Android-specific — the {@code AudioManager} calls, the mic
 * re-route check, the debug log — goes through {@link Sink}, and the clock is
 * injected, so the scheduling and cancellation logic is Robolectric-testable
 * without a rig or a headset.
 */
public final class ScoLinkCoordinator {

    /** Side effects the coordinator asks the host to perform. */
    public interface Sink {
        /** {@code setBluetoothScoOn(true); startBluetoothSco(); setSpeakerphoneOn(false)}. */
        void startSco();

        /** Bare {@code stopBluetoothSco()} that balances a start before a fresh one. */
        void stopScoForRestart();

        /** {@code setBluetoothScoOn(false); stopBluetoothSco(); setSpeakerphoneOn(true)}. */
        void stopSco();

        /** SCO is CONNECTED and wanted: check that the AudioRecord is on the link. */
        void verifyMicRouting();

        void log(String message);
    }

    /** Delay before asking where the record landed after CONNECTED. */
    public static final long MIC_ROUTE_CHECK_DELAY_MS = 300;

    private final ScoLinkTracker tracker;
    private final Handler handler;
    private final Sink sink;
    private final LongSupplier clock;

    private final Runnable retryRunnable;
    private final Runnable connectTimeoutRunnable;
    private final Runnable micCheckRunnable;

    public ScoLinkCoordinator(ScoLinkTracker tracker, Handler handler, Sink sink,
                              LongSupplier clock) {
        this.tracker = tracker;
        this.handler = handler;
        this.sink = sink;
        this.clock = clock;
        this.retryRunnable = () -> apply(this.tracker.onRetryDue(), "retry");
        this.connectTimeoutRunnable = this::onConnectTimeout;
        this.micCheckRunnable = this::onMicCheckDue;
    }

    /** Main-thread coordinator with a monotonic clock. */
    public static ScoLinkCoordinator onMainThread(Sink sink) {
        return new ScoLinkCoordinator(new ScoLinkTracker(),
                new Handler(Looper.getMainLooper()), sink,
                android.os.SystemClock::elapsedRealtime);
    }

    // ---- read-only state --------------------------------------------------

    public boolean isWanted() {
        return tracker.isWanted();
    }

    public int linkState() {
        return tracker.linkState();
    }

    /**
     * The link is CONNECTING or CONNECTED as far as the tracker knows — the state the
     * headset-mode refresh cross-checks instead of {@code AudioManager.isBluetoothScoOn()},
     * which only mirrors the legacy force-use flag (Copilot review on #778).
     */
    public boolean isLinkUpOrPending() {
        int s = tracker.linkState();
        return s == AudioManager.SCO_AUDIO_STATE_CONNECTING
                || s == AudioManager.SCO_AUDIO_STATE_CONNECTED;
    }

    public int attempts() {
        return tracker.attempts();
    }

    // ---- entry points (any thread) ----------------------------------------

    /**
     * The app wants SCO up. {@code onApplied} runs (on the handler thread) only
     * when the request actually started or restarted the link; a request that
     * finds the link already pending/up is logged and otherwise ignored.
     */
    public void requestOn(String why, Runnable onApplied) {
        dispatch(() -> {
            ScoLinkTracker.Action action = tracker.requestOn();
            if (action == ScoLinkTracker.Action.NONE) {
                sink.log("SCO: " + why + " ignored, link already "
                        + ScoLinkTracker.stateName(tracker.linkState()));
                return;
            }
            apply(action, why);
            if (onApplied != null) onApplied.run();
        });
    }

    /** The app wants SCO down. {@code onApplied} runs only if a link was actually stopped. */
    public void requestOff(String why, Runnable onApplied) {
        dispatch(() -> {
            ScoLinkTracker.Action action = tracker.requestOff();
            if (action == ScoLinkTracker.Action.NONE) return;
            apply(action, why);
            if (onApplied != null) onApplied.run();
        });
    }

    /** {@code ACTION_SCO_AUDIO_STATE_UPDATED} landed. */
    public void onStateUpdated(int state, int previousState) {
        dispatch(() -> {
            sink.log("SCO: state " + ScoLinkTracker.stateName(previousState)
                    + " -> " + ScoLinkTracker.stateName(state)
                    + " wanted=" + tracker.isWanted()
                    + " attempt=" + tracker.attempts());
            ScoLinkTracker.Update u = tracker.onStateUpdate(state, clock.getAsLong());
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                handler.removeCallbacks(connectTimeoutRunnable);
                handler.removeCallbacks(retryRunnable);
            }
            if (u.gaveUp) {
                sink.log("SCO: link failed " + tracker.attempts()
                        + " times - giving up until the next request");
            }
            scheduleRetry(u);
            apply(u.action, "state");
            if (u.checkMicRouting) {
                // The policy re-route runs asynchronously in the audio server;
                // give it a beat before asking where the record actually landed.
                handler.removeCallbacks(micCheckRunnable);
                handler.postDelayed(micCheckRunnable, MIC_ROUTE_CHECK_DELAY_MS);
            }
        });
    }

    /**
     * The owner is going away: drop every pending callback and balance an
     * outstanding start so a retained-but-torn-down ViewModel can't keep
     * restarting SCO, or leave AudioService holding our request.
     */
    public void shutdown() {
        dispatch(() -> {
            handler.removeCallbacks(retryRunnable);
            handler.removeCallbacks(connectTimeoutRunnable);
            handler.removeCallbacks(micCheckRunnable);
            ScoLinkTracker.Action action = tracker.requestOff();
            if (action != ScoLinkTracker.Action.NONE) {
                apply(action, "shutdown");
            }
        });
    }

    // ---- handler-thread internals -----------------------------------------

    private void dispatch(Runnable r) {
        if (Looper.myLooper() == handler.getLooper()) {
            r.run();
        } else {
            handler.post(r);
        }
    }

    private void onConnectTimeout() {
        ScoLinkTracker.Update u = tracker.onConnectTimeout();
        if (u.gaveUp) {
            sink.log("SCO: no CONNECTED within " + ScoLinkTracker.CONNECT_TIMEOUT_MS
                    + "ms and retries exhausted (" + tracker.attempts()
                    + " attempts) - giving up");
        } else if (u.retryDelayMs > 0) {
            sink.log("SCO: no CONNECTED within " + ScoLinkTracker.CONNECT_TIMEOUT_MS + "ms");
        }
        scheduleRetry(u);
        apply(u.action, "timeout");
    }

    private void onMicCheckDue() {
        if (!tracker.isWanted()
                || tracker.linkState() != AudioManager.SCO_AUDIO_STATE_CONNECTED) {
            return;
        }
        sink.verifyMicRouting();
    }

    private void scheduleRetry(ScoLinkTracker.Update u) {
        if (u.retryDelayMs <= 0) return;
        sink.log("SCO: retry in " + u.retryDelayMs + "ms");
        handler.removeCallbacks(retryRunnable);
        handler.postDelayed(retryRunnable, u.retryDelayMs);
    }

    /** Drive the sink per the tracker's decision; returns whether anything was done. */
    private boolean apply(ScoLinkTracker.Action action, String why) {
        if (action == ScoLinkTracker.Action.NONE) return false;
        sink.log("SCO: " + action + " (" + why + ", attempt " + tracker.attempts() + ")");
        handler.removeCallbacks(retryRunnable);
        handler.removeCallbacks(connectTimeoutRunnable);
        switch (action) {
            case STOP:
                handler.removeCallbacks(micCheckRunnable);
                sink.stopSco();
                break;
            case RESTART:
                // A failed/abandoned start can leave AudioService's per-client
                // start count at 1, where a bare start is a no-op - balance it.
                sink.stopScoForRestart();
                // fall through
            case START:
                sink.startSco();
                handler.postDelayed(connectTimeoutRunnable, ScoLinkTracker.CONNECT_TIMEOUT_MS);
                break;
            default:
                break;
        }
        return true;
    }
}
