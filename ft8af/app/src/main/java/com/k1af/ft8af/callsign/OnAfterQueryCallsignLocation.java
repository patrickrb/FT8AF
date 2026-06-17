package com.k1af.ft8af.callsign;

/**
 * Callback interface for callsign location queries, since database operations are asynchronous
 *
 * @author BG7YOZ
 * @date 2023-03-20
 *
 */
public interface OnAfterQueryCallsignLocation {
    void doOnAfterQueryCallsignLocation(CallsignInfo callsignInfo);
}
