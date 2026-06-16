package com.k1af.ft8af.database;

/**
 * Callback after configuration info has been read
 * @author BGY70Z
 * @date 2023-03-20
 */
public interface OnAfterQueryConfig {
    void doOnBeforeQueryConfig(String KeyName);
    void doOnAfterQueryConfig(String KeyName,String Value);
}
