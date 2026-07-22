package com.k1af.ft8af;
/**
 * Hash code list for callsigns.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import java.util.HashMap;

public class MessageHashMap extends HashMap<Long,String> {
    private static final String TAG = "MessageHashMap";

    /**
     * Add a callsign and its hash code to the list
     *
     * @param hashCode hash code
     * @param callsign callsign
     * @return false means it already exists
     */
    public synchronized void addHash(long hashCode, String callsign) {
        // A null or empty callsign is never a real call to hash. It reaches here
        // from the Ft8Message copy-constructor for a DXpedition (Fox/Hound)
        // decode, which sets callsignFrom="" while still carrying a non-zero
        // call-from hash (derived from the invited callsign) — so the hashCode==0
        // short-circuit below does NOT catch it, and callsign.charAt(0) would
        // throw StringIndexOutOfBoundsException (empty) or callsign.equals(...)
        // an NPE (null). That exception fired inside the decode loop's try/catch
        // and silently dropped the whole Fox message.
        if (callsign == null || callsign.isEmpty()) {
            return;
        }
        if (callsign.equals("CQ")||callsign.equals("QRZ")||callsign.equals("DE")){
            return;
        }
        if (hashCode == 0 || checkHash(hashCode)|| callsign.charAt(0) == '<') {
            return;
        }
        Log.d(TAG, String.format("addHash: callsign:%s ,hash:%x",callsign,hashCode ));
        put(hashCode,callsign);
    }

    //Check if this hash code exists
    public boolean checkHash(long hashCode) {
       return get(hashCode)!=null;
//        for (HashStruct hash : this) {
//            if (hash.hashCode == hashCode) {
//                return true;
//            }
//        }
//        return false;
    }

    //Look up callsign by hash code
    public synchronized String getCallsign(long[] hashCode) {
        for (long l : hashCode) {
            if (checkHash(l)) {
                return String.format("<%s>", get(l));
            }
        }
        return "<...>";
    }
}
