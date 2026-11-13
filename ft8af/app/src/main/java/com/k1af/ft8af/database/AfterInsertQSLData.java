package com.k1af.ft8af.database;

public interface AfterInsertQSLData {
    /**
     * @param isInvalid  true only when the record was NOT written to the database at
     *                    all (e.g. no callsign). {@code isNewQSL} is meaningless when
     *                    this is true.
     * @param isNewQSL   true for a new insert, false for an update of an existing
     *                    record.
     * @param hasWarning true when the record WAS written (new or updated) but one of
     *                    its own fields couldn't be parsed and was dropped to a
     *                    fallback value -- see {@code QSLRecord.isInvalid}. Distinct
     *                    from {@code isInvalid}: a record flagged here was still
     *                    counted as added/updated, just imperfectly.
     */
    void doAfterInsert(boolean isInvalid, boolean isNewQSL, boolean hasWarning);
}
