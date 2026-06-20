package com.k1af.ft8af.rigs;

/**
 * CRC16-CCITT-FALSE checksum utility
 *
 * @author BGY70Z
 * @date 2023-03-20
 *
 */
public class CRC16 {

    /**
     * CRC16-CCITT-FALSE encode/decode (four bytes)
     *
     * @param bytes
     * @return
     */
    public static int crc16(byte[] bytes) {
        return crc16(bytes, bytes.length);
    }

    /**
     * CRC16-CCITT-FALSE encode/decode (four bytes)
     *
     * @param bytes - byte array
     * @return
     */
    public static int crc16(byte[] bytes, int len) {
        int crc = 0xFFFF;
        for (int j = 0; j < len; j++) {
            crc = ((crc >>> 8) | (crc << 8)) & 0xffff;
            crc ^= (bytes[j] & 0xff);// byte to int, trunc sign
            crc ^= ((crc & 0xff) >> 4);
            crc ^= (crc << 12) & 0xffff;
            crc ^= ((crc & 0xFF) << 5) & 0xffff;
        }
        crc &= 0xffff;
        return crc;
    }

    /**
     * CRC16-CCITT-FALSE encode/decode (four bytes)
     *
     * @param bytes
     * @return
     */
    public static int crc16(byte[] bytes, int start, int len) {
        int crc = 0xFFFF;
        for (; start < len; start++) {
            crc = ((crc >>> 8) | (crc << 8)) & 0xffff;
            crc ^= (bytes[start] & 0xff);// byte to int, trunc sign
            crc ^= ((crc & 0xff) >> 4);
            crc ^= (crc << 12) & 0xffff;
            crc ^= ((crc & 0xFF) << 5) & 0xffff;
        }
        crc &= 0xffff;
        return crc;
    }

    /**
     * CRC16-CCITT-FALSE encode/decode
     *
     * @param bytes
     *            - byte array
     * @return
     */
    public static short crc16_short(byte[] bytes) {
        return crc16_short(bytes, 0, bytes.length);
    }

    /**
     * CRC16-CCITT-FALSE encode/decode (calculate from position 0 for len length)
     *
     * @param bytes
     *            - byte array
     * @param len
     *            - length
     * @return
     */
    public static short crc16_short(byte[] bytes, int len) {
        return (short) crc16(bytes, len);
    }

    /**
     * CRC16-CCITT-FALSE encode/decode (two bytes)
     *
     * @param bytes
     * @return
     */
    public static short crc16_short(byte[] bytes, int start, int len) {
        return (short) crc16(bytes, start, len);
    }
}