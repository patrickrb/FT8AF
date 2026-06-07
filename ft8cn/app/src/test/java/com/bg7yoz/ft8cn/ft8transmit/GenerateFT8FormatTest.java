package com.bg7yoz.ft8cn.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for the pure byte-formatting helpers on {@link GenerateFT8}:
 *   - {@link GenerateFT8#byteToBinString(byte[])} (comma-prefixed, 8-bit zero-padded binary)
 *   - {@link GenerateFT8#byteToHexString(byte[])} (comma-prefixed 2-digit hex)
 *
 * Plain JUnit: GenerateFT8's static initialiser swallows the
 * {@code System.loadLibrary("ft8cn")} UnsatisfiedLinkError, so the class loads on
 * the bare JVM (and JaCoCo records its coverage). These helpers never touch JNI.
 */
public class GenerateFT8FormatTest {

    @Test
    public void byteToBinString_null_returnsEmpty() {
        assertThat(GenerateFT8.byteToBinString(null)).isEqualTo("");
    }

    @Test
    public void byteToBinString_empty_returnsEmpty() {
        assertThat(GenerateFT8.byteToBinString(new byte[0])).isEqualTo("");
    }

    @Test
    public void byteToBinString_zeroPadsEachByteToEightBits() {
        // 0x05 -> binary "101" -> padded "00000101", prefixed with a comma.
        assertThat(GenerateFT8.byteToBinString(new byte[]{0x05})).isEqualTo(",00000101");
    }

    @Test
    public void byteToBinString_handlesAllOnesAndZero() {
        // (byte)0xFF & 0xff = 255 -> "11111111"; 0x00 -> "00000000".
        assertThat(GenerateFT8.byteToBinString(new byte[]{(byte) 0xFF, 0x00}))
                .isEqualTo(",11111111,00000000");
    }

    @Test
    public void byteToHexString_formatsNonNegativeBytesAsTwoDigits() {
        // 0x0A -> ",0A"; 0x7F -> ",7F".
        assertThat(GenerateFT8.byteToHexString(new byte[]{0x0A, 0x7F}))
                .isEqualTo(",0A,7F");
    }

    @Test
    public void byteToHexString_masksNegativeBytesToTwoDigits() {
        // The byte is masked with 0xFF before formatting, so (byte)0xFF -> "FF"
        // (no int sign-extension).
        assertThat(GenerateFT8.byteToHexString(new byte[]{(byte) 0xFF}))
                .isEqualTo(",FF");
    }

    @Test
    public void byteToHexString_empty_returnsEmpty() {
        assertThat(GenerateFT8.byteToHexString(new byte[0])).isEqualTo("");
    }
}
