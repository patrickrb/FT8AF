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
 * 10 Hz-aligned dials. The encoder packs the last nibble as the tens-of-Hz digit
 * ({@code freq % 100 / 10}); only the sub-10 Hz remainder (below the rig's CAT
 * resolution) is dropped, so the two are exact inverses for any 10 Hz-aligned VFO.
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
        // The decoder must invert the encoder for any 10 Hz-aligned dial. This dial
        // has non-zero hundreds- and tens-of-Hz digits, exercising both nibbles of
        // the last byte (which previously desynced: 50 packed raw read back as 320).
        long dial = 14_074_350L; // 14.074 MHz + 350 Hz
        byte[] encoded = Yaesu2RigConstant.setOperationFreq(dial);
        assertThat(Yaesu2Command.getFrequency(encoded)).isEqualTo(dial);
    }

    @Test
    public void getFrequency_dropsOnlySub10HzOnRoundTrip() {
        // The rig CAT frame has 10 Hz resolution, so the sub-10 Hz remainder is the
        // only part not preserved; everything at or above 10 Hz round-trips exactly.
        byte[] encoded = Yaesu2RigConstant.setOperationFreq(14_074_058L);
        assertThat(Yaesu2Command.getFrequency(encoded)).isEqualTo(14_074_050L);
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
