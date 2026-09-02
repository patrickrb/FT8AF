package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link GuoHeQ900Rig#checkHead(byte[])}, which locates the frame
 * length byte that follows the mandatory four-consecutive-0xA5 sync header (see
 * {@link GuoHeRigConstant}).
 *
 * <p>The 0xA5 bytes must be counted <em>consecutively</em>. A status frame's
 * payload carries two big-endian VFO frequencies that are frequently 0xA5, so
 * counting 0xA5 anywhere (the old behaviour) could return an index into the sync
 * run. {@code GuoHeQ900Rig.onReceiveData} would then treat a 0xA5 as the length
 * byte — {@code (byte)0xA5 + 1 == -90} — and allocate {@code new byte[-90]},
 * throwing NegativeArraySizeException. The exception aborted framing before
 * {@code clearBuffer()}, leaving stale partial-frame state and silently dropping
 * the rig's frequency update.
 */
public class GuoHeCheckHeadTest {

    @Test
    public void wellFormedFrame_returnsLengthByteIndex() {
        // A5 A5 A5 A5 <len=0x0f> 0b ... : length byte is at index 4.
        byte[] data = {
                (byte) 0xA5, (byte) 0xA5, (byte) 0xA5, (byte) 0xA5,
                (byte) 0x0f, (byte) 0x0b, (byte) 0x01, (byte) 0x02};
        assertThat(GuoHeQ900Rig.checkHead(data)).isEqualTo(4);
        // The byte at the returned index is the length, never a 0xA5 sync byte.
        assertThat(data[GuoHeQ900Rig.checkHead(data)] & 0xFF).isEqualTo(0x0f);
    }

    @Test
    public void nonConsecutiveA5_isNotMistakenForSync() {
        // Three scattered 0xA5 payload bytes then a *real* 4-byte sync run.
        // The old counter reached 4 on the scattered bytes and returned an index
        // pointing *into* the sync run, so the caller read a 0xA5 sync byte as
        // the length byte; the fixed counter resets on every non-0xA5 byte.
        byte[] data = {
                (byte) 0xA5, (byte) 0x01, (byte) 0xA5, (byte) 0x02, (byte) 0xA5,
                (byte) 0xA5, (byte) 0xA5, (byte) 0xA5, (byte) 0xA5, // 4-byte sync
                (byte) 0x0f, (byte) 0x0b};
        int idx = GuoHeQ900Rig.checkHead(data);
        assertThat(idx).isEqualTo(9);
        assertThat(data[idx] & 0xFF).isEqualTo(0x0f);
    }

    @Test
    public void statusFrameWithA5FreqBytes_thenNextSync_findsLengthNotA5() {
        // Realistic stream splice: the tail of a status frame whose VFO
        // frequency bytes are 0xA5 (14.074 MHz style values land on 0xA5), the
        // 2-byte CRC, then the next frame's 4-byte sync + length byte. The old
        // code returned an index landing on a 0xA5 -> len = -90 -> new byte[-90].
        byte[] data = {
                // trailing payload of a previous frame with embedded 0xA5s
                (byte) 0x00, (byte) 0xA5, (byte) 0xC0, (byte) 0xA5, (byte) 0x37,
                // next frame sync + length
                (byte) 0xA5, (byte) 0xA5, (byte) 0xA5, (byte) 0xA5,
                (byte) 0x03, (byte) 0x0b};
        int idx = GuoHeQ900Rig.checkHead(data);
        assertThat(idx).isEqualTo(9);
        // Must be a valid (non-negative) length byte, never a 0xA5 sync byte.
        int len = data[idx] + 1;
        assertThat(len).isGreaterThan(0);
        assertThat(data[idx] & 0xFF).isNotEqualTo(0xA5);
    }

    @Test
    public void moreThanFourConsecutiveA5_skipsEntireSyncRun() {
        // A stray leading 0xA5 (e.g. previous CRC low byte) makes five in a row.
        // The length byte is the first non-0xA5 after the whole run, not the 5th 0xA5.
        byte[] data = {
                (byte) 0xA5, (byte) 0xA5, (byte) 0xA5, (byte) 0xA5, (byte) 0xA5,
                (byte) 0x05, (byte) 0x0b};
        int idx = GuoHeQ900Rig.checkHead(data);
        assertThat(idx).isEqualTo(5);
        assertThat(data[idx] & 0xFF).isEqualTo(0x05);
    }

    @Test
    public void noSyncHeader_returnsMinusOne() {
        byte[] data = {(byte) 0x01, (byte) 0x02, (byte) 0xA5, (byte) 0xA5, (byte) 0x03};
        assertThat(GuoHeQ900Rig.checkHead(data)).isEqualTo(-1);
    }

    @Test
    public void syncRunAtBufferEnd_returnsMinusOne_ratherThanOverrunning() {
        // The 4-byte sync arrives but the length byte is in the next read. The
        // fixed code reports "no complete header yet" (-1) instead of returning
        // an out-of-range index that would throw on data[startIndex].
        byte[] data = {(byte) 0xA5, (byte) 0xA5, (byte) 0xA5, (byte) 0xA5};
        assertThat(GuoHeQ900Rig.checkHead(data)).isEqualTo(-1);
    }
}
