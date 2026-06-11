package com.bg7yoz.ft8cn.ui;

import com.bg7yoz.ft8cn.Ft8Message;

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
 * especially FT2 (3.8s), whose short, often-empty slots re-stamp far more often
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
     * previous slot's labels. A deep pass only augments: an empty deep pass keeps
     * the normal pass's labels rather than wiping them (deep decode re-runs the
     * same slot's audio and must never erase what the normal pass already found).
     *
     * @param previous the current overlay messages (may be null)
     * @param kept     this pass's kept decodes (own-TX echoes / junk already removed)
     * @param isDeep   whether this is the slower deep-decode pass
     * @return the overlay message list for the waterfall
     */
    public static ArrayList<Ft8Message> afterPass(ArrayList<Ft8Message> previous,
                                                  ArrayList<Ft8Message> kept,
                                                  boolean isDeep) {
        if (isDeep && kept.isEmpty()) {
            return previous;
        }
        return kept;
    }
}
