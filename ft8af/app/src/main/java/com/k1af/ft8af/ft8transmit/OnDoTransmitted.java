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

    //Tune (issue #408): key/unkey the rig for the tune carrier without an
    //Ft8Message. Default no-ops so existing implementers compile unchanged;
    //MainViewModel routes these through the same CAT/RTS/DTR PTT + SCO logic
    //as onBeforeTransmit/onAfterTransmit (VOX rigs key on the audio itself).
    default void onTuneKeyDown() {}
    default void onTuneKeyUp() {}

    /**
     * Whether the connected rig carries TX audio over its network control link
     * (FlexRadio / ICOM / XieGu Wi-Fi) rather than the local sound path. A
     * Hamlib NET rigctl link moves only CAT over TCP, so it returns false and TX
     * audio plays locally like a wired CAT rig. Defaults true so existing
     * network-rig behaviour is unchanged for implementers that don't override.
     */
    default boolean usesNetworkAudio() { return true; }
}
