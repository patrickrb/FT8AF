package com.k1af.ft8af.wave;

/**
 * Callback when audio data is received.
 * @author BGY70Z
 * @date 2023-03-20
 */
public interface OnHamRecord {
    void OnReceiveData(float[] data,int size);
}
