package com.k1af.ft8af.database;

/**
 * Callback for saving configuration info
 * @author BGY70Z
 * @date 2023-03-20
 */
public interface OnAfterWriteConfig {
    void doOnAfterWriteConfig(boolean writeDone);
}
