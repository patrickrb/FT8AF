package com.k1af.ft8af.wave;

/**
 * Decides how {@link MicRecorder} reacts when a libusb-direct USB audio capture
 * session ends unexpectedly. Pure logic, no Android types — unit-tested.
 *
 * <p>Answers two questions:
 *
 * <ol>
 *   <li><b>Was the session a failure?</b> A session that dies before it can
 *       carry even a fraction of a 15&nbsp;s FT8 cycle is useless <em>even if a
 *       handful of samples arrived first</em>. That is the field failure mode on
 *       a Pixel&nbsp;8 + C-Media adapter (VID:PID {@code 0D8C:0012}): every
 *       libusb session delivered ~100&nbsp;ms of audio and then retired all its
 *       isochronous transfers ({@code code=0}). The old rule counted only
 *       "saw <em>no</em> data at all" as a failure, so that case never tripped
 *       the failure tally and churned a fresh {@code libusb_init}/{@code
 *       libusb_exit} every 2&nbsp;s forever — the waterfall freeze, and the
 *       repeated init/exit that raced libusb into a native SIGSEGV.</li>
 *   <li><b>How long to wait before the next attempt?</b> Exponential backoff so
 *       a persistently-failing device stops hammering the bus (and libusb global
 *       state), capped so a device that recovers is still picked back up. We
 *       never fall back to the phone's built-in microphone: an operator wants
 *       radio audio or none.</li>
 * </ol>
 */
public final class UsbCaptureRetryPolicy {

    private UsbCaptureRetryPolicy() {}

    /**
     * A session alive for less than this was too short to be useful — under a
     * third of a 15&nbsp;s FT8 cycle, so it cannot have carried a decodable
     * transmission no matter how many samples it briefly delivered.
     */
    static final long MIN_USEFUL_SESSION_MS = 5_000;

    /** First backoff step. */
    static final long BASE_BACKOFF_MS = 2_000;

    /**
     * Backoff ceiling. A persistently-dead adapter is still retried this often,
     * in case it comes back (cable resettled, RF-desense cleared), without
     * spinning the bus.
     */
    static final long MAX_BACKOFF_MS = 60_000;

    /**
     * Whether a finished capture session warrants the failure-retry path at all,
     * based on the native stop reason (see
     * {@code UsbAudioDevice.describeCaptureStopCode}).
     *
     * <p>A clean stop ({@code stopCode == 0}) is a {@code nativeStop} <em>we</em>
     * requested — a reinit, a band change, {@code stopRecord()}, or teardown —
     * and the initiator restarts capture itself where appropriate. Treating it
     * as a failure and scheduling a retry tears down the capture the initiator
     * just brought back; doing that on every clean stop is what pinned the
     * C-Media adapter in a fail&rarr;backoff&rarr;reinit loop (429 of 434 field
     * stops were clean stops misclassified as failures), starving the FT8
     * decoder of ~87% of receive slots so QSO replies were never heard. Only a
     * non-zero stop reason (transfers retired, NO_DEVICE, event-loop error, or
     * the {@code UsbRequest}-fallback death sentinel) is a real failure.
     *
     * @param stopCode native stop reason; {@code 0} == clean stop
     */
    static boolean isRetryableFailure(int stopCode) {
        return stopCode != 0;
    }

    /**
     * Whether a finished capture session should count against the
     * consecutive-failure tally.
     *
     * @param sawData whether any audio arrived during the session
     * @param aliveMs how long the session ran before it stopped
     */
    static boolean isFailure(boolean sawData, long aliveMs) {
        if (!sawData) return true;
        return aliveMs < MIN_USEFUL_SESSION_MS;
    }

    /**
     * Delay before the next USB reinit, given how many consecutive failures have
     * occurred so far ({@code 1} = the first failure). Exponential
     * (2&nbsp;s, 4&nbsp;s, 8&nbsp;s, …) capped at {@link #MAX_BACKOFF_MS}. A
     * non-positive count (a healthy session just reset the tally) yields no wait.
     */
    static long backoffMs(int consecutiveFailures) {
        if (consecutiveFailures <= 0) return 0;
        // Cap the shift so the left-shift can't overflow before we clamp.
        int shift = Math.min(consecutiveFailures - 1, 20);
        long delay = BASE_BACKOFF_MS << shift;
        if (delay <= 0 || delay > MAX_BACKOFF_MS) return MAX_BACKOFF_MS;
        return delay;
    }

    /**
     * The failure tally after a session ends: incremented when the session was a
     * failure (see {@link #isFailure}), reset to {@code 0} when it stayed alive
     * long enough to be useful. Kept separate from the raw {@code ++} at the call
     * site so the state transition is unit-testable without a live capture.
     *
     * @param prevFailures the tally before this session ended (never negative)
     * @param sawData      whether any audio arrived during the session
     * @param aliveMs      how long the session ran before it stopped
     */
    static int failureTallyAfter(int prevFailures, boolean sawData, long aliveMs) {
        return isFailure(sawData, aliveMs) ? prevFailures + 1 : 0;
    }

    /**
     * The complete plan for reacting to a finished USB capture session: the
     * updated failure tally and how long to wait before the next reinit attempt.
     * Computed up front (on the native capture event thread) so the actual
     * backoff sleep + {@code reinitialize()} can be handed to a separate worker
     * thread — running them on the event thread is what raced libusb into the
     * destroyed-mutex SIGABRT (nativeStop()'s join() blocks on the event thread
     * while it drives the next nativeStart(), overlapping the two libusb
     * contexts). See {@code MicRecorder}'s onCaptureStopped handoff.
     */
    static final class ReinitPlan {
        /** Consecutive-failure tally after this session (see {@link #failureTallyAfter}). */
        final int consecutiveFailures;
        /** Delay before the reinit, in ms ({@code 0} = reinit immediately). */
        final long backoffMs;

        ReinitPlan(int consecutiveFailures, long backoffMs) {
            this.consecutiveFailures = consecutiveFailures;
            this.backoffMs = backoffMs;
        }
    }

    /**
     * Fold {@link #failureTallyAfter} and {@link #backoffMs} into the single
     * decision {@code MicRecorder} needs when a capture session stops.
     *
     * @param sawData      whether any audio arrived during the session
     * @param aliveMs      how long the session ran before it stopped
     * @param prevFailures the tally before this session ended
     */
    static ReinitPlan planReinit(boolean sawData, long aliveMs, int prevFailures) {
        int tally = failureTallyAfter(prevFailures, sawData, aliveMs);
        return new ReinitPlan(tally, backoffMs(tally));
    }
}
