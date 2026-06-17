package com.k1af.ft8af.log;
/**
 * Callback for querying QSO logs.
 * @author BGY70Z
 * @date 2023-03-20
 */

import java.util.ArrayList;

public interface OnQueryQSLRecordCallsign {
     void afterQuery(ArrayList<QSLRecordStr> records);
}
