package com.k1af.ft8af.icom;
/**
 * ICom command data buffer.
 * @author BGY70Z
 * @date 2023-03-20
 */

import java.util.ArrayList;

public class IcomSeqBuffer {
    private long last=System.currentTimeMillis();

    public static class SeqBufEntry {
        public short seq;//Sequence number
        public byte[] data;//Data
        public long addedAt;//Time added; retained for max 10 seconds

        public SeqBufEntry(short seq, byte[] data) {
            this.seq = seq;
            this.data = data;
            addedAt = System.currentTimeMillis();
        }
    }

    public ArrayList<SeqBufEntry> entries = new ArrayList<>();

    /**
     * Add command to sequence buffer
     *
     * @param seq  sequence number
     * @param data command data
     */
    public synchronized void add(short seq, byte[] data) {
        entries.add(new SeqBufEntry(seq, data));
        last=System.currentTimeMillis();
        purgeOldEntries();//Delete expired history entries
    }

    /**
     * Purge old commands to keep buffer within limits
     */
    public void purgeOldEntries() {
        if (entries.size() == 0) return;
        long now=System.currentTimeMillis();
        for (int i = entries.size()-1; i >=0 ; i--) {//Delete entries older than 10 seconds
            if (now-entries.get(i).addedAt>IComPacketTypes.PURGE_MILLISECONDS){
                entries.remove(i);
            }
        }

        //while (entries.size() > MaxBufferCount) {
        //    entries.remove(0);
        //}
    }

    /**
     * Search buffer for historical command by sequence number
     *
     * @param seqNum sequence number
     * @return command data, or NULL if not found.
     */
    public synchronized byte[] get(int seqNum) {
        int founded = -1;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).seq == seqNum) {
                founded = i;
            }
        }
        if (founded != -1) {
            return entries.get(founded).data;
        } else {
            return null;
        }
    }
    public long getTimeOut(){
        return System.currentTimeMillis()-last;
    }
}
