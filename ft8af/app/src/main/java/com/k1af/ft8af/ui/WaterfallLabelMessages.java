package com.k1af.ft8af.ui;

import com.k1af.ft8af.Ft8Message;

import java.util.ArrayList;

/**
 * Decides which decoded messages the waterfall label overlay should show after a
 * decode pass. Extracted from {@code MainViewModel.afterDecode} so the per-pass
 * rule can be unit-tested (the rest of afterDecode is bound to the Android
 * decode listener).
 *
 * <p>The waterfall stamps these labels onto its scrolling bitmap once per slot
 * ({@link WaterfallLabelGate}). The overlay therefore has to be refreshed every
 * slot — including a <em>silent</em> slot. Before this, afterDecode only updated
 * the overlay on a non-empty decode, so a silent slot left the previous slot's
 * messages in place and they were re-stamped onto the waterfall each cycle,
 * appearing to repeat and never disappear. The effect is worst on FT4 (7.5s) and
 * especially FT2 (3.75s), whose short, often-empty slots re-stamp far more often
 * than FT8's 15s slots.
 */
public final class WaterfallLabelMessages {

    private WaterfallLabelMessages() {
    }

    /**
     * The overlay message list after a decode pass.
     *
     * <p>A normal (non-deep) pass is authoritative for the slot: its result —
     * even an empty one — becomes the overlay, so a silent slot clears the
     * previous slot's labels. A deep pass only augments: it never replaces the
     * normal pass's labels, it adds to them. The listener already dedups each
     * deep pass against the earlier passes ({@code FT8SignalListener.addMsgToList}
     * removes from {@code msgs} anything already in {@code allMsg}), so {@code
     * kept} here holds only this deep pass's <em>new</em> decodes — they must be
     * merged onto {@code previous}, not used to replace it, or the normal pass's
     * labels would be dropped. An empty deep pass keeps {@code previous} unchanged
     * (deep decode re-runs the same slot's audio and must never erase what an
     * earlier pass already found). This mirrors how {@code afterDecode} accumulates
     * the main message list and decode count across deep passes.
     *
     * @param previous the current overlay messages (may be null)
     * @param kept     this pass's kept decodes (own-TX echoes / junk already removed)
     * @param isDeep   whether this is the slower deep-decode pass
     * @return the overlay message list for the waterfall
     */
    public static ArrayList<Ft8Message> afterPass(ArrayList<Ft8Message> previous,
                                                  ArrayList<Ft8Message> kept,
                                                  boolean isDeep) {
        if (!isDeep) {
            return kept;
        }
        if (kept.isEmpty()) {
            return previous;
        }
        ArrayList<Ft8Message> merged = new ArrayList<>();
        if (previous != null) {
            merged.addAll(previous);
        }
        merged.addAll(kept);
        return merged;
    }
}
