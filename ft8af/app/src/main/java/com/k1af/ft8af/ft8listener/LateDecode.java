package com.k1af.ft8af.ft8listener;

import com.k1af.ft8af.ModeProfile;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Policy and buffer handoff for the late full-slot decode pass (issue #363).
 *
 * <p>With early decode on (the default), the primary decode only sees the first
 * {@link ModeProfile#earlyDecodeMillis} of the slot so replies can go out on the very next
 * slot. The price: a signal that starts later than {@code slotMillis - earlyDecodeMillis}
 * minus the audio slack (about DT &gt; +0.86 s for FT8) is truncated past decodability and
 * silently lost. To recover those, the cycle also records the <em>whole</em> slot into a
 * second buffer; the still-running decode thread picks that buffer up after the early
 * passes finish and decodes it as an extra, late delivery.
 *
 * <p>This class holds the parts of that feature that are decisions/state rather than
 * native-decoder plumbing, so they stay unit-testable: the should-run predicate, the
 * bounded-wait math, and the one-shot buffer handoff between the recorder's monitor
 * thread and the decode thread.
 */
public final class LateDecode {

    /**
     * Extra wait past the slot boundary for the full-slot buffer to arrive. The buffer is
     * normally delivered right at the boundary (the monitor fills after exactly
     * {@code slotMillis}); if capture stalls mid-slot, HamRecorder's one-shot stall
     * watchdog force-delivers a partial buffer 2 s after the requested duration. 3 s
     * covers that plus scheduling jitter; past it the buffer is never coming (e.g. the
     * recorder was stopped before the monitor could even register).
     */
    static final long DELIVERY_GRACE_MS = 3000;

    private LateDecode() {}

    /**
     * Whether this cycle should schedule the second, full-slot decode pass.
     *
     * <p>FT8 only: its 1.5 s early-window gap is the reported loss (DT &gt; +0.86 s).
     * FT4/FT2 have smaller gaps (1.0 s / 0.75 s) on much shorter slots, where a second
     * full decode roughly doubles per-slot CPU and the late thread would routinely chain
     * across several subsequent slots — not worth it for those modes.
     *
     * @param earlyDecode current value of {@code GeneralVariables.earlyDecode}
     * @param mode        the cycle's operating mode
     */
    public static boolean shouldRunLatePass(boolean earlyDecode, ModeProfile mode) {
        return earlyDecode && mode.isFt8 && mode.earlyDecodeMillis < mode.slotMillis;
    }

    /**
     * Absolute wall-clock instant after which waiting for the full-slot buffer is
     * pointless (see {@link #DELIVERY_GRACE_MS}).
     *
     * @param registeredAtMs wall-clock time the full-slot monitor was registered
     *                       (the slot boundary)
     * @param slotMillis     the mode's slot length
     */
    static long deliveryDeadlineMillis(long registeredAtMs, int slotMillis) {
        return registeredAtMs + slotMillis + DELIVERY_GRACE_MS;
    }

    /** Milliseconds still worth waiting for the buffer; never negative. */
    static long remainingWaitMillis(long deadlineEpochMs, long nowMs) {
        return Math.max(0, deadlineEpochMs - nowMs);
    }

    /**
     * One-shot handoff of the full-slot audio buffer from the recorder's monitor thread
     * to the decode thread (which is still busy with the early passes when the buffer
     * completes). Exactly one buffer is ever accepted; the consumer's wait is bounded by
     * the delivery deadline so a capture stopped mid-slot cannot leave the decode thread
     * hanging.
     */
    public static final class Handoff {
        private final ArrayBlockingQueue<float[]> queue = new ArrayBlockingQueue<>(1);
        private final long deadlineEpochMs;

        /**
         * @param registeredAtMs wall-clock time the full-slot voice monitor was registered
         * @param slotMillis     the mode's slot length (== the monitor's duration)
         */
        public Handoff(long registeredAtMs, int slotMillis) {
            this.deadlineEpochMs = deliveryDeadlineMillis(registeredAtMs, slotMillis);
        }

        /**
         * Recorder side: deliver the full-slot buffer. Only the first non-null offer is
         * accepted.
         *
         * @return true if the buffer was accepted
         */
        public boolean offer(float[] fullSlotData) {
            return fullSlotData != null && queue.offer(fullSlotData);
        }

        /**
         * Decode-thread side: wait for the buffer, bounded by the delivery deadline.
         *
         * @param nowMs current wall-clock time ({@code System.currentTimeMillis()})
         * @return the full-slot buffer, or null on timeout/interrupt (skip the late pass)
         */
        public float[] awaitBuffer(long nowMs) {
            try {
                return queue.poll(remainingWaitMillis(deadlineEpochMs, nowMs),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }
}
