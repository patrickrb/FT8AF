package com.k1af.ft8af.ft8listener;
/**
 * Callback for audio listening; calls afterDecode to notify decoded messages after decoding completes.
 * @author BGY70Z
 * @date 2023-03-20
 */

import com.k1af.ft8af.Ft8Message;

import java.util.ArrayList;

public interface OnFt8Listen {
    /**
     * Event triggered when listening starts.
     * @param utc current UTC time
     */
    void beforeListen(long utc);

    /**
     * Event triggered after decoding completes.
     * @param utc UTC time of the current cycle
     * @param time_sec average time offset for this cycle (seconds)
     * @param sequential current sequence number
     * @param messages message list
     */
    void afterDecode(long utc,float time_sec,int sequential, ArrayList<Ft8Message> messages,boolean isDeep);
}
