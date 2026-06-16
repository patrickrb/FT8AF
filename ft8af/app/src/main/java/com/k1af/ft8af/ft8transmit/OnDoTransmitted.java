package com.k1af.ft8af.ft8transmit;
/**
 * Transmit callback interface.
 * @author BGY70Z
 * @date 2023-03-20
 */

import com.k1af.ft8af.Ft8Message;

public interface OnDoTransmitted {
    void onBeforeTransmit(Ft8Message message,int functionOder);
    void onAfterTransmit(Ft8Message message, int functionOder);
    void onTransmitByWifi(Ft8Message message);

    //2023-08-16 Modification submitted by DS1UFX (based on v0.9), adding (tr)uSDX audio over CAT support.
    boolean supportTransmitOverCAT();
    void onTransmitOverCAT(Ft8Message message);
}
