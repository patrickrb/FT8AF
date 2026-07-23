package com.k1af.ft8af;
/**
 * Hash code list for callsigns.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MessageHashMap extends HashMap<Long,String> {
    private static final String TAG = "MessageHashMap";

    /**
     * Add a callsign and its hash code to the list.
     *
     * <p>Silently ignores anything that is not a real call to hash: a null/empty
     * callsign, the CQ/QRZ/DE tokens, an already-stored hash, a zero hash, or a
     * still-unresolved {@code <...>} placeholder. There is no return value —
     * callers cannot distinguish "added" from "skipped".
     *
     * @param hashCode hash code
     * @param callsign callsign
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

    /**
     * Return an independent, point-in-time copy of the (hash&nbsp;&rarr;&nbsp;callsign)
     * entries, taken while holding this map's monitor.
     *
     * <p>{@link #addHash} and {@link #getCallsign} are {@code synchronized} on this
     * instance, so every writer mutates the backing {@code HashMap} under the
     * monitor: the decode thread, the DB-load thread, and the TX/Fox thread all
     * add hashes several times per 15&nbsp;s cycle. A reader that iterates the
     * <em>live</em> map from another thread races those writers. The web-logger's
     * hash-table page ({@code LogHttpServer.showCallsignHash}) is built on a
     * NanoHTTPD worker thread and used to iterate {@code entrySet()} directly, so a
     * concurrent {@code put} could throw {@link java.util.ConcurrentModificationException}
     * mid-render, and a {@code put}-driven resize racing the iteration can corrupt
     * the very map the decoder relies on for hash resolution. Copying under the
     * lock lets callers iterate a private snapshot without holding the monitor.
     *
     * @return a new list of the current entries, safe to iterate without locking
     */
    public synchronized List<Map.Entry<Long, String>> snapshotEntries() {
        List<Map.Entry<Long, String>> copy = new ArrayList<>(size());
        for (Map.Entry<Long, String> entry : entrySet()) {
            copy.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue()));
        }
        return copy;
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
