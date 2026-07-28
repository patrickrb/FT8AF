package com.k1af.ft8af.ft8listener;

import com.k1af.ft8af.ModeProfile;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
 * <p>The two buffers are complementary, not competing: the early pass exists for SPEED
 * (its fast results feed the auto-sequencer in time for the next slot's TX), the
 * full-slot pass for DEPTH (high-DT recovery, and — with deep decode on — the
 * subtract-and-redecode loop, which runs here instead of on the truncated early buffer;
 * see {@link #deepLoopRunsOnEarlyBuffer}). The full-slot pass is bounded by
 * {@link #latePassDeadlineMillis} so it normally finishes before the next slot's early
 * decode starts rather than colliding with it, with the {@link AnalysisGate} contention
 * abort as the backstop when it can't (per-candidate overrun, or a mid-slot timer
 * rebuild moving the next boundary).
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

    /**
     * Safety margin subtracted from the late pass's absolute deadline (next slot boundary
     * + early window = the instant the next slot's early decode thread starts). The
     * deadline is only checked between candidates, so the late pass can overrun it by one
     * candidate's analysis — deep multi-iteration LDPC on a busy band runs into the
     * hundreds of milliseconds. The margin absorbs that plus scheduling jitter, so the
     * analysis-gate abort stays a backstop instead of the routine exit path.
     */
    static final long LATE_PASS_SAFETY_MS = 750;

    private LateDecode() {}

    /**
     * Absolute wall-clock instant the late full-slot pass must finish by: when the NEXT
     * slot's early decode thread starts (next boundary + early window), minus
     * {@link #LATE_PASS_SAFETY_MS}. Computed from the slot's RECORD-TIME mode snapshot,
     * so it assumes the next slot keeps the same cycle timing; if the operator switches
     * modes mid-slot, {@code FT8SignalListener.rebuildTimer(...)} moves the actual next
     * boundary and this bound no longer tracks it — in that (rare) case the
     * {@link AnalysisGate} contention abort is the backstop, exactly as it is for a
     * per-candidate overrun. Before this bound existed the late pass was only
     * budget-bounded; with deep decode on, the early buffer's subtraction loop pushed its
     * start right up against the next early decode, so its first gate contention aborted
     * the whole candidate scan almost every slot — the two decode passes fought instead
     * of cooperating.
     *
     * @param registeredAtMs wall-clock time the full-slot monitor was registered
     *                       (the slot boundary)
     * @param mode           the slot's RECORD-TIME mode snapshot
     */
    static long latePassDeadlineMillis(long registeredAtMs, ModeProfile mode) {
        return registeredAtMs + mode.slotMillis + mode.earlyDecodeMillis - LATE_PASS_SAFETY_MS;
    }

    /**
     * The deadline the late full-slot pass actually runs under: the window bound above
     * capped by the mode's deep budget (a slow device must not chain late work across
     * multiple later slots even when the window would allow it).
     */
    public static long effectiveLatePassDeadline(long windowDeadlineEpochMs,
                                                 long budgetDeadlineEpochMs) {
        return Math.min(windowDeadlineEpochMs, budgetDeadlineEpochMs);
    }

    /**
     * Where this slot's deep subtract-and-redecode loop runs. With no late pass scheduled
     * (early decode off, or FT4/FT2) the early buffer is the only buffer, so the loop runs
     * there. When a late full-slot pass IS scheduled, the early buffer is a strict prefix
     * of the full-slot buffer — running the loop on both would duplicate the deep work AND
     * delay the late pass past the next slot's early decode (the "decoders fighting"
     * failure). So the loop is deferred to the full-slot pass, which becomes the slot's
     * single deep engine.
     */
    public static boolean deepLoopRunsOnEarlyBuffer(Handoff lateHandoff) {
        return lateHandoff == null;
    }

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
     * Capture one cycle's decode plan at record time. Everything the decode thread (and
     * its late full-slot pass) needs from the operating mode is frozen HERE, at the slot
     * boundary: the user can switch modes mid-slot (rebuilding the cycle timers does not
     * interrupt an in-flight decode thread, and the late pass deliberately runs into the
     * next slot), and a decode that re-read {@code GeneralVariables.currentMode()} later
     * would then initialize the decoder with the NEW mode's protocol/flags against a
     * buffer recorded under the OLD mode — producing garbage. Thread {@link SlotPlan#mode}
     * through the whole decode instead of re-reading the global.
     *
     * @param earlyDecode current value of {@code GeneralVariables.earlyDecode}
     * @param mode        the operating mode at the slot boundary
     * @param nowMs       wall-clock time the monitors are being registered
     */
    public static SlotPlan planSlot(boolean earlyDecode, ModeProfile mode, long nowMs) {
        int recordMillis = earlyDecode ? mode.earlyDecodeMillis : mode.slotMillis;
        Handoff lateHandoff = shouldRunLatePass(earlyDecode, mode)
                ? new Handoff(nowMs, mode)
                : null;
        return new SlotPlan(mode, recordMillis, lateHandoff);
    }

    /**
     * Immutable per-cycle decode plan, captured by {@link #planSlot} at the slot boundary:
     * the mode every decode pass of this slot must use, the primary record window, and the
     * late full-slot handoff (null = no late pass this cycle).
     */
    public static final class SlotPlan {
        /** The operating mode frozen at record time; ALL of this slot's decode passes use it. */
        public final ModeProfile mode;
        /** Primary capture window: early window when fast decode is on, else the full slot. */
        public final int recordMillis;
        /** Buffer handoff for the late full-slot pass, or null when no late pass runs. */
        public final Handoff lateHandoff;

        SlotPlan(ModeProfile mode, int recordMillis, Handoff lateHandoff) {
            this.mode = mode;
            this.recordMillis = recordMillis;
            this.lateHandoff = lateHandoff;
        }
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
         * Absolute instant the late full-slot pass must finish by — the next slot's
         * early-decode start minus the safety margin. See {@link #latePassDeadlineMillis}.
         */
        public final long latePassDeadlineEpochMs;

        /**
         * @param registeredAtMs wall-clock time the full-slot voice monitor was registered
         *                       (the slot boundary)
         * @param mode           the slot's RECORD-TIME mode snapshot
         */
        public Handoff(long registeredAtMs, ModeProfile mode) {
            this.deadlineEpochMs = deliveryDeadlineMillis(registeredAtMs, mode.slotMillis);
            this.latePassDeadlineEpochMs = latePassDeadlineMillis(registeredAtMs, mode);
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

    /**
     * Serializes per-candidate native analysis across decode threads. The FT8 cross-slot
     * recall cache (cpp/ft8af_glue/ft8_xslot.c) is process-global and NOT thread-safe, and
     * the late full-slot pass lets a previous slot's decode thread overlap the next slot's
     * decode thread — so no two analysis calls may ever run concurrently.
     *
     * <p>The gate is asymmetric so best-effort late work can never stall time-critical
     * early work: the early/primary path always blocks (its wait is bounded by ONE
     * in-flight candidate, since the late holder releases between candidates), while the
     * late path only tries briefly and treats failure as "the next slot's early decode has
     * started" — its cue to abort the remaining late candidates entirely.
     */
    public static final class AnalysisGate {
        /**
         * How long the late pass waits for the gate before declaring contention. Long
         * enough to absorb a micro-race for a free lock, far shorter than one candidate's
         * analysis — so a late pass genuinely overlapping an early decode aborts on the
         * first collision instead of interleaving with (and slowing) every early candidate.
         */
        static final long LATE_TRYLOCK_TIMEOUT_MS = 10;

        private final ReentrantLock lock = new ReentrantLock();

        /** Early/primary path: always acquires; waits at most one candidate's analysis. */
        public void acquireBlocking() {
            lock.lock();
        }

        /**
         * Late path: best-effort acquire bounded by {@link #LATE_TRYLOCK_TIMEOUT_MS}.
         *
         * @return true if acquired (caller MUST {@link #release()}); false on contention
         *         or interrupt — the caller must NOT release, and should abort its
         *         remaining late candidates
         */
        public boolean tryAcquireForLate() {
            try {
                return lock.tryLock(LATE_TRYLOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        public void release() {
            lock.unlock();
        }
    }

    /**
     * Sticky abort flag for one late full-slot pass. Tripped on the first analysis-gate
     * contention; once tripped, the late pass stops scanning candidates and skips any
     * remaining late passes (e.g. the deep pass) — the next slot's early decode has
     * priority and re-contending per candidate would just burn CPU against it.
     */
    public static final class LateAbort {
        private volatile boolean tripped;

        public void trip() {
            tripped = true;
        }

        public boolean tripped() {
            return tripped;
        }
    }
}
