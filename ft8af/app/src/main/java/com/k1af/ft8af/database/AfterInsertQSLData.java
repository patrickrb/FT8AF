package com.k1af.ft8af.database;

public interface AfterInsertQSLData {
    void doAfterInsert(boolean isInvalid,boolean isNewQSL);
}
