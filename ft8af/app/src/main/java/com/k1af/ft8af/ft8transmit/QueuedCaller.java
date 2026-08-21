package com.k1af.ft8af.ft8transmit;

/**
 * Data class representing a caller queued for response after the current QSO completes.
 */
public class QueuedCaller {
    public final String callsign;
    public final float frequency;
    public final int sequential;
    public int snr;
    public final int i3;
    public final int n3;
    public final String extraInfo;
    public long queuedTimeMs;
    /**
     * Slot time ({@code Ft8Message.utcTime}, the {@code UtcTimer.getSystemTime()} base)
     * of the most recent message this station sent us. Refreshed every time they call
     * again; a caller whose last call is older than one cycle has given up and is
     * pruned before the queue is worked (see {@link CallerQueueOrdering#hasGivenUp}).
     */
    public long lastHeardUtc;

    public QueuedCaller(String callsign, float frequency, int sequential, int snr,
                        int i3, int n3, String extraInfo, long lastHeardUtc) {
        this.callsign = callsign;
        this.frequency = frequency;
        this.sequential = sequential;
        this.snr = snr;
        this.i3 = i3;
        this.n3 = n3;
        this.extraInfo = extraInfo;
        this.queuedTimeMs = System.currentTimeMillis();
        this.lastHeardUtc = lastHeardUtc;
    }

    /**
     * The station called again: take the new SNR and move {@link #lastHeardUtc}
     * forward to {@code heardUtc} (never backward — an evidence list can replay an
     * older slot's decode after a newer one has already been counted).
     *
     * @return true if the SNR or the last-heard slot actually changed, i.e. the
     *         queue snapshot the UI and the pick policy see is different now; a
     *         re-delivery of the same decode returns false so callers can skip
     *         republishing an identical list
     */
    public boolean refresh(int newSnr, long heardUtc) {
        queuedTimeMs = System.currentTimeMillis();
        boolean changed = false;
        if (snr != newSnr) {
            snr = newSnr;
            changed = true;
        }
        if (heardUtc > lastHeardUtc) {
            lastHeardUtc = heardUtc;
            changed = true;
        }
        return changed;
    }
}
