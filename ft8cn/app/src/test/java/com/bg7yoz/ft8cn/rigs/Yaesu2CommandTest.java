package com.bg7yoz.ft8cn.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for the Yaesu gen-2 (FT-817/857/897) BCD frequency decoder.
 *
 * The rig reports frequency as 4 BCD bytes (8 nibbles, most-significant first)
 * followed by a mode byte. Expected values below are computed directly from the
 * production nibble weights in {@link Yaesu2Command#getFrequency} (note the
 * decoder's own weighting is asserted as-is, not idealised).
 */
public class Yaesu2CommandTest {

    @Test
    public void getFrequency_decodesFourBcdNibbleBytes() {
        // Bytes 14 07 40 00 + mode byte 00.
        // Per production weights:
        //   (1)*1e8 + (4)*1e7 + (0)*1e6 + (7)*1e5 + (4)*1e4 + (0)*1e3
        //   + (0)*1e4 + (0)*1e3  =  140_740_000
        byte[] raw = {(byte) 0x14, (byte) 0x07, (byte) 0x40, (byte) 0x00, (byte) 0x00};
        assertThat(Yaesu2Command.getFrequency(raw)).isEqualTo(140_740_000L);
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
