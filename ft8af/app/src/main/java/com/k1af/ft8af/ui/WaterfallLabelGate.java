package com.k1af.ft8af.ui;

import com.k1af.ft8af.Ft8Message;

import java.util.List;

/**
 * Gates the waterfall's decoded-message labels to one stamp per decode slot.
 *
 * <p>The waterfall stamps the labels onto its scrolling bitmap as a one-shot, re-armed
 * every time a decode pass finishes. A decode runs more than one pass per slot (a normal
 * pass and a slower deep pass), so without a gate the same slot's labels are stamped several
 * times, at different scroll offsets, and appear to repeat down the waterfall — including
 * over the next slot's blank rows where the signal is already gone. Keyed on a per-slot
 * index (the caller derives it from the current mode's slot length, e.g.
 * {@code utcMs / slotMillis} — 15s FT8, 7.5s FT4, 3.75s FT2), this returns true for only the
 * first stamp of each slot.
 *
 * <p>The slot length is part of the key as well as the slot index, because a single
 * {@code WaterfallView} (and its one gate) is reused across mode changes: switching
 * FT8&rarr;FT4/FT2 changes the divisor, so {@code utcMs / slotMillis} can land on a slot
 * index already stamped under the previous mode and wrongly suppress the new mode's first
 * label. Including {@code slotMillis} makes any mode change a distinct key, so the first
 * slot after the switch always stamps once.
 *
 * <p>The slot index must be derived from the <em>decoded</em> slot (the messages' own
 * {@link Ft8Message#utcTime}), NOT from the wall clock at stamp time — use
 * {@link #shouldStamp(long, List)}. The deep-decode subtraction/cross-slot passes run on a
 * budget of most of a slot ({@code ModeProfile#deepDecodeBudgetMillis}) starting near the
 * end of the slot, so late passes routinely finish <em>after</em> the slot boundary. Each
 * one re-arms the stamp; keyed on wall clock they land in a fresh slot index, the gate
 * lets them through, and the same slot's labels are stamped again a few scrolled rows
 * lower — every label on the waterfall appears doubled/garbled.
 *
 * <p>Makes no Android framework calls of its own, so the once-per-slot rule is unit-tested
 * directly. The {@link #shouldStamp(long, List)} overload does reference {@link Ft8Message},
 * whose fields pull in Android/Play-Services types — tests exercising that overload run
 * under Robolectric (see {@code WaterfallLabelGateSlotKeyTest}); the index-keyed overloads
 * stay testable on plain JUnit.
 */
public final class WaterfallLabelGate {

    /** Sentinel mode key for the slot-index-only {@link #shouldStamp(long)} overload. */
    private static final long NO_MODE = Long.MIN_VALUE;

    private long lastSlotMillis = Long.MIN_VALUE;
    private long lastStampedPeriod = Long.MIN_VALUE;

    /**
     * Whether the labels for {@code period} under a mode of slot length {@code slotMillis}
     * should be stamped now. {@code period} is a decode-slot index the caller keys off the
     * current mode's slot length (e.g. {@code utcMs / slotMillis}, NOT a fixed 15s). Returns
     * true once per distinct {@code (slotMillis, period)} pair and false for any repeat
     * arming of the same pair, so a mode change always stamps even when the new slot index
     * collides with one already stamped under the old mode.
     */
    public boolean shouldStamp(long slotMillis, long period) {
        if (slotMillis == lastSlotMillis && period == lastStampedPeriod) {
            return false;
        }
        lastSlotMillis = slotMillis;
        lastStampedPeriod = period;
        return true;
    }

    /**
     * Slot-index-only gate, for callers that never change mode. Equivalent to
     * {@link #shouldStamp(long, long)} with a fixed mode key.
     */
    public boolean shouldStamp(long period) {
        return shouldStamp(NO_MODE, period);
    }

    /**
     * Whether the labels for {@code messages} should be stamped now, keyed on the slot the
     * messages were <em>decoded from</em> ({@link Ft8Message#utcTime}, the slot-start UTC the
     * listener stamps on every decode of a run) rather than the wall clock at stamp time.
     * All passes over one slot's audio share that utcTime, so a deep/subtraction pass that
     * finishes after the slot boundary maps to the already-stamped key and is suppressed,
     * instead of restamping the same labels at a new scroll offset.
     *
     * <p>An empty (or null) list never stamps and — unlike the index overloads — does not
     * consume the gate: there is nothing to draw, and marking the key would collide with a
     * later pass of the slot that does find messages (the wall-clock fallback of a silent
     * slot shares the index space with the message-derived keys).
     */
    public boolean shouldStamp(long slotMillis, List<Ft8Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        long decodedUtcMs = messages.get(0).utcTime;
        long period = slotMillis > 0 ? decodedUtcMs / slotMillis : decodedUtcMs;
        return shouldStamp(slotMillis, period);
    }

    /** Forget the last stamped slot (e.g. when the bitmap is recreated). */
    public void reset() {
        lastSlotMillis = Long.MIN_VALUE;
        lastStampedPeriod = Long.MIN_VALUE;
    }
}
