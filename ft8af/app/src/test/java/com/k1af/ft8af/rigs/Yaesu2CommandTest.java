package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for the Yaesu gen-2 (FT-817/857/897) BCD frequency decoder.
 *
 * The rig reports frequency as 4 BCD bytes (8 nibbles, most-significant first)
 * followed by a mode byte. The eight nibbles are a strictly descending decimal
 * sequence with weights 1e8, 1e7, 1e6, 1e5, 1e4, 1e3, 1e2, 1e1 (10 Hz LSB).
 *
 * This inverts {@link Yaesu2RigConstant#setOperationFreq} exactly for
 * 100 Hz-aligned dials. The encoder packs the last nibble as {@code freq % 100}
 * rather than a clean tens-of-Hz digit, so for frequencies with a non-zero
 * sub-100 Hz remainder the encoder and decoder are not exact inverses.
 */
public class Yaesu2CommandTest {

    @Test
    public void getFrequency_decodesFourBcdNibbleBytes() {
        // Bytes 14 07 40 00 + mode byte 00:
        //   (1)*1e8 + (4)*1e7 + (0)*1e6 + (7)*1e5 + (4)*1e4 + (0)*1e3
        //   + (0)*1e2 + (0)*1e1  =  140_740_000
        byte[] raw = {(byte) 0x14, (byte) 0x07, (byte) 0x40, (byte) 0x00, (byte) 0x00};
        assertThat(Yaesu2Command.getFrequency(raw)).isEqualTo(140_740_000L);
    }

    @Test
    public void getFrequency_decodesHundredsAndTensOfHzDigits() {
        // Bytes 14 07 43 21 + mode byte 00. The last byte carries the hundreds-of-Hz
        // (nibble 2 -> 200) and tens-of-Hz (nibble 1 -> 10) digits, which the previous
        // copy-pasted weights (1e4, 1e3) inflated by 100x, corrupting the reported VFO
        // frequency by tens of kHz:
        //   (1)*1e8 + (4)*1e7 + (0)*1e6 + (7)*1e5 + (4)*1e4 + (3)*1e3
        //   + (2)*1e2 + (1)*1e1  =  140_743_210
        byte[] raw = {(byte) 0x14, (byte) 0x07, (byte) 0x43, (byte) 0x21, (byte) 0x00};
        assertThat(Yaesu2Command.getFrequency(raw)).isEqualTo(140_743_210L);
    }

    @Test
    public void getFrequency_roundTripsSetOperationFreqEncoding() {
        // The decoder must invert the encoder. Use a 100 Hz-aligned dial so the
        // round trip isolates the decoder weights (the encoder packs the two
        // sub-100 Hz digits into a single nibble, an unrelated limitation).
        long dial = 14_074_300L; // 14.074 MHz + 300 Hz, exercises the low nibbles
        byte[] encoded = Yaesu2RigConstant.setOperationFreq(dial);
        assertThat(Yaesu2Command.getFrequency(encoded)).isEqualTo(dial);
    }

    @Test
    public void getFrequency_allZero_isZero() {
        byte[] raw = {0x00, 0x00, 0x00, 0x00, 0x00};
        assertThat(Yaesu2Command.getFrequency(raw)).isEqualTo(0L);
    }

    @Test
    public void getFrequency_wrongLengthReturnsMinusOne() {
        assertThat(Yaesu2Command.getFrequency(new byte[]{0x00, 0x00, 0x00, 0x00}))
                .isEqualTo(-1L);
        assertThat(Yaesu2Command.getFrequency(new byte[0])).isEqualTo(-1L);
    }
}
