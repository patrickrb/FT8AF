package com.k1af.ft8af.ft8transmit;
/**
 * Callback after transmit completes.
 * @author BGY70Z
 * @date 2023-03-20
 */

import com.k1af.ft8af.log.QSLRecord;

public interface OnTransmitSuccess {
    void doAfterTransmit(QSLRecord qslRecord);
}
