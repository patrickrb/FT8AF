package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * CRC-16/CCITT-FALSE reference vectors. The canonical test vector from the
 * CRC catalogue is ASCII "123456789" -> 0x29B1; everything else here exercises
 * the three overloads and the byte-vs-short return surfaces against each other.
 */
public class CRC16Test {

    private static final byte[] CHECK = "123456789".getBytes(StandardCharsets.US_ASCII);

    @Test
    public void canonicalCheckVector() {
        assertThat(CRC16.crc16(CHECK)).isEqualTo(0x29B1);
    }

    @Test
    public void emptyInput_returnsSeed() {
        // CCITT-FALSE seed is 0xFFFF; no bytes mixed in means the seed survives.
        assertThat(CRC16.crc16(new byte[0])).isEqualTo(0xFFFF);
    }

    @Test
    public void allZeros_8bytes_isStableAcrossOverloads() {
        // Don't pin a specific magic number here (the canonical "123456789"
        // vector above proves correctness of the algorithm). Instead lock in
        // that all three overloads agree on the same input — a regression
        // guard against accidental divergence between the byte[]-only,
        // (bytes, len), and (bytes, start, len) code paths.
        byte[] zeros = new byte[]{0, 0, 0, 0, 0, 0, 0, 0};
        int full = CRC16.crc16(zeros);
        int withLen = CRC16.crc16(zeros, zeros.length);
        int withStartLen = CRC16.crc16(zeros, 0, zeros.length);
        assertThat(withLen).isEqualTo(full);
        assertThat(withStartLen).isEqualTo(full);
        // Bytes of zero should not leave the seed unchanged — the polynomial
        // mixes the register on every iteration even with zero input.
        assertThat(full).isNotEqualTo(0xFFFF);
    }

    @Test
    public void allOnes_4bytes() {
        byte[] in = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        // The same value computed by the explicit-length overload should match.
        int full = CRC16.crc16(in);
        int withLen = CRC16.crc16(in, in.length);
        assertThat(full).isEqualTo(withLen);
    }

    @Test
    public void startLenOverload_agreesWithFullForFullRange() {
        byte[] in = "the quick brown fox".getBytes(StandardCharsets.US_ASCII);
        // crc16(bytes, start, len) treats `len` as the stop index (loop is
        // `for (; start < len; start++)`), so calling with start=0 and
        // len=bytes.length must reproduce the full-array result.
        assertThat(CRC16.crc16(in, 0, in.length)).isEqualTo(CRC16.crc16(in));
    }

    @Test
    public void shortOverload_truncatesIntCorrectly() {
        // crc16_short downcasts the int via (short); the bit pattern should match
        // the low 16 bits of the int form (which itself is already masked to 16).
        short s = CRC16.crc16_short(CHECK);
        assertThat(s & 0xFFFF).isEqualTo(CRC16.crc16(CHECK));
    }
}
