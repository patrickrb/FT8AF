package com.k1af.ft8af.log;
/**
 * Callback for querying callsign logs.
 * @author BGY70Z
 * @date 2023-03-20
 */

import java.util.ArrayList;

public interface OnQueryQSLCallsign {
     void afterQuery(ArrayList<QSLCallsignRecord> records);
}
