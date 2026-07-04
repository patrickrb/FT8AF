package com.k1af.ft8af;

import com.k1af.ft8af.ui.WaterfallLabelMessages;

import java.util.ArrayList;

/**
 * Per-cycle decode results shared between decode threads and the UI: the waterfall
 * label overlay list and the decoded-message counter.
 *
 * <p>Since the late full-slot pass (#363), two decode threads routinely overlap —
 * slot N's late/deep pass delivers at ~the slot boundary while slot N+1's early
 * pass is already running — and both call {@code MainViewModel.afterDecode}. The
 * overlay list and the counter are read-modify-written there, so without a common
 * monitor one pass's update clobbers the other's (stale label set, wrong decoded
 * count) and a reader can iterate a list reference mid-reassignment. All access
 * goes through this class's synchronized methods, serializing the RMWs and giving
 * readers a happens-before on the latest published snapshot.
 *
 * <p>The label lists themselves are never mutated after publication
 * ({@link WaterfallLabelMessages#afterPass} builds fresh lists), so a reader
 * holding a snapshot can iterate it safely while the next pass publishes a new one.
 */
public final class DecodeCycleState {

    private ArrayList<Ft8Message> messages = null;
    private int decodeCount = 0;

    /**
     * Fold a decode pass's kept messages into the waterfall label overlay
     * (normal pass replaces, deep pass augments — see
     * {@link WaterfallLabelMessages#afterPass}).
     */
    public synchronized void labelsAfterPass(ArrayList<Ft8Message> kept, boolean isDeep) {
        messages = WaterfallLabelMessages.afterPass(messages, kept, isDeep);
    }

    /**
     * Fold a decode pass's kept-message count into the cycle total (normal pass
     * replaces, deep pass accumulates) and return the new total, so the caller
     * posts the same value this call produced rather than re-reading a field
     * another pass may have already advanced.
     */
    public synchronized int countAfterPass(int keptCount, boolean isDeep) {
        decodeCount = isDeep ? decodeCount + keptCount : keptCount;
        return decodeCount;
    }

    /** The current waterfall label overlay; null when nothing is stamped. */
    public synchronized ArrayList<Ft8Message> getMessages() {
        return messages;
    }

    /** The current cycle's decoded-message count. */
    public synchronized int getDecodeCount() {
        return decodeCount;
    }

    /** Drop the label overlay (spectrum views clear it when they (re)attach). */
    public synchronized void clearMessages() {
        messages = null;
    }

    /** Reset the counter (start-of-cycle wipe, mode changes). */
    public synchronized void resetCount() {
        decodeCount = 0;
    }
}
