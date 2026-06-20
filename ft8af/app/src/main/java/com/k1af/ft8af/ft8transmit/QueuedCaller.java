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

    public QueuedCaller(String callsign, float frequency, int sequential, int snr,
                        int i3, int n3, String extraInfo) {
        this.callsign = callsign;
        this.frequency = frequency;
        this.sequential = sequential;
        this.snr = snr;
        this.i3 = i3;
        this.n3 = n3;
        this.extraInfo = extraInfo;
        this.queuedTimeMs = System.currentTimeMillis();
    }
}
