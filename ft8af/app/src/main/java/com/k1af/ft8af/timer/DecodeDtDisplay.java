package com.k1af.ft8af.timer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stabilises the decode-DT figure the operating screen shows (the clock-sync pill on the slot
 * timer bar, and the "measured DT" readout in Settings &rarr; Time Sync).
 *
 * <p><b>Why this exists.</b> The pill used to render the raw arithmetic <em>mean</em> of every
 * decode in the slot, re-posted on every decode pass. Three things made that read as an unstable
 * app on a rig that was in fact perfectly in time:
 *
 * <ul>
 *   <li><b>A mean has no defence against one bad decode.</b> DT is per-station: a station
 *       transmitting late, or a marginal decode the searcher placed a couple of seconds off,
 *       carries its own big DT. Average three good decodes at +0.1 s with one at +7 s and the
 *       pill announces "+1.8 s" — a red "clock off" verdict produced entirely by somebody
 *       else's clock.</li>
 *   <li><b>Every pass re-posted.</b> A slot is decoded several times (fast pass, then deep and
 *       late passes that dig out the weakest signals — exactly the decodes with the sloppiest
 *       DT), so the number visibly jumped several times within one 15 s slot.</li>
 *   <li><b>No slot-to-slot smoothing.</b> Even with clean data, a slot with two decodes bounces
 *       against a slot with nine.</li>
 * </ul>
 *
 * <p>The estimator that actually <em>corrects</em> the clock ({@link ClockSelfSync}) has always
 * been robust — median, MAD outlier rejection, multi-slot confirmation — so the display and the
 * correction disagreed: the pill flashed "2.5 s off" while auto-sync, looking at the same decodes,
 * correctly concluded there was nothing to do. This class puts the display on the same footing:
 *
 * <ul>
 *   <li>Per slot: MAD-based outlier rejection then the <em>median</em> of the survivors, reusing
 *       {@link ClockSelfSync}'s helpers so both paths measure the same way.</li>
 *   <li>Passes accumulate into the slot they belong to instead of each replacing the reading, so a
 *       deep pass refines the slot's median rather than yanking the display.</li>
 *   <li>Across slots: the median of the last {@link #WINDOW_SLOTS} slots, so a single freak slot
 *       cannot move the needle at all.</li>
 *   <li>A {@link #PUBLISH_QUANTUM_SEC} deadband on the published value, so the last digit stops
 *       twitching between otherwise identical readings.</li>
 * </ul>
 *
 * <p><b>A deliberate clock change resets the window.</b> When {@link UtcTimer#delay} moves — the
 * operator applied a correction, or GPS/NTP/self-sync did — every stored sample describes the old
 * clock. Holding them would average the correction away and make the pill lie for half a minute,
 * so the history is dropped and the reading re-acquires from the next slot.
 *
 * <p>Pure Java, no Android imports, so it is unit-testable without Robolectric. The entry point is
 * synchronized: decode passes for adjacent slots can deliver concurrently.
 */
public class DecodeDtDisplay {

    /**
     * How many slots the displayed value is the median of. Three is the smallest window that
     * outvotes a single bad slot while still following a real change within ~2 slots (30 s of FT8).
     */
    public static final int WINDOW_SLOTS = 3;

    /**
     * Minimum change (seconds) before a new reading is published. The pill renders one decimal, so
     * anything under half a displayed digit is invisible movement that only costs a recomposition.
     */
    public static final float PUBLISH_QUANTUM_SEC = 0.05f;

    /** Cap on the DTs retained for one slot, so a pathological slot can't grow without bound. */
    static final int MAX_SLOT_SAMPLES = 64;

    // The slot currently accumulating, and every DT seen for it across passes.
    private long currentUtc = Long.MIN_VALUE;
    private float[] currentSamples = new float[0];

    // Robust medians of the most recently finished slots, oldest first, at most WINDOW_SLOTS - 1
    // (the in-progress slot supplies the newest value).
    private final List<Float> finishedMedians = new ArrayList<>();

    // Last value handed to the UI, for the publish deadband. Null = nothing published yet.
    private Float published = null;

    // The clock offset the stored samples were measured against.
    private Integer sampledAtDelayMs = null;

    /**
     * Feed one decode pass's per-decode DTs (seconds, own-TX echoes already filtered out) and get
     * the value the UI should now show, or {@code null} to keep showing what it has.
     *
     * @param utc           the slot the decodes belong to; stragglers for an older slot are ignored
     * @param dtSec         this pass's DTs; may be empty
     * @param clockDelayMs  the live {@link UtcTimer#delay} — a change resets the window
     */
    public synchronized Float onDecodes(long utc, float[] dtSec, int clockDelayMs) {
        if (sampledAtDelayMs == null || sampledAtDelayMs != clockDelayMs) {
            // The clock moved under us: everything measured before it describes a different clock.
            resetHistory();
            sampledAtDelayMs = clockDelayMs;
        }
        if (utc < currentUtc) {
            // A late pass for an already-superseded slot — stale evidence, ignore it.
            return null;
        }
        if (utc > currentUtc) {
            finishCurrentSlot();
            currentUtc = utc;
            currentSamples = new float[0];
        }
        appendSamples(dtSec);

        float[] survivors = ClockSelfSync.rejectOutliers(currentSamples);
        if (survivors.length == 0) {
            return null;
        }
        float slotMedian = ClockSelfSync.medianOf(survivors);

        float[] window = new float[finishedMedians.size() + 1];
        for (int i = 0; i < finishedMedians.size(); i++) {
            window[i] = finishedMedians.get(i);
        }
        window[window.length - 1] = slotMedian;
        float display = ClockSelfSync.medianOf(window);

        if (published != null && Math.abs(display - published) < PUBLISH_QUANTUM_SEC) {
            return null;
        }
        published = display;
        return display;
    }

    /** The value last handed to the UI, or null if nothing has been published yet. */
    public synchronized Float lastPublished() {
        return published;
    }

    /** Drops all history — the samples, the window, and the published value. */
    public synchronized void reset() {
        resetHistory();
        sampledAtDelayMs = null;
    }

    private void resetHistory() {
        finishedMedians.clear();
        currentSamples = new float[0];
        currentUtc = Long.MIN_VALUE;
        published = null;
    }

    /** Retire the in-progress slot into the window (dropping the oldest once it is full). */
    private void finishCurrentSlot() {
        float[] survivors = ClockSelfSync.rejectOutliers(currentSamples);
        if (survivors.length == 0) {
            return;
        }
        finishedMedians.add(ClockSelfSync.medianOf(survivors));
        while (finishedMedians.size() > WINDOW_SLOTS - 1) {
            finishedMedians.remove(0);
        }
    }

    private void appendSamples(float[] dtSec) {
        if (dtSec == null || dtSec.length == 0) {
            return;
        }
        int room = MAX_SLOT_SAMPLES - currentSamples.length;
        if (room <= 0) {
            return;
        }
        int take = Math.min(room, dtSec.length);
        float[] merged = Arrays.copyOf(currentSamples, currentSamples.length + take);
        System.arraycopy(dtSec, 0, merged, currentSamples.length, take);
        currentSamples = merged;
    }
}
