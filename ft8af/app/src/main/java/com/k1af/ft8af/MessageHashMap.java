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
        //if (callsign.length()<2){return;}
        //if (){return;}
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
