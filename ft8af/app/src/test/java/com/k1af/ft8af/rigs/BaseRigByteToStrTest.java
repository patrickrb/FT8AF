package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link BaseRig#byteToStr(byte[])}'s null tolerance.
 *
 * <p>In FT8CNS mode {@code X6100Connector.sendFt8A91(a91, ...)} logs the payload via
 * {@code byteToStr(a91)}. {@code GenerateFT8.generateA91} returns {@code null} for an
 * invalid (&lt;3-char) own callsign, so a null {@code a91} would reach {@code byteToStr}
 * and throw {@link NullPointerException} on {@code data.length} — crashing the app mid-TX
 * instead of aborting gracefully. The rigs now drop PTT and skip on a null a91, but the
 * logging helper is also hardened as defence-in-depth.
 */
public class BaseRigByteToStrTest {

    @Test
    public void nullArray_returnsSafeMarker_doesNotThrow() {
        assertThat(BaseRig.byteToStr(null)).isEqualTo("null");
    }

    @Test
    public void emptyArray_returnsEmptyString() {
        assertThat(BaseRig.byteToStr(new byte[0])).isEmpty();
    }

    @Test
    public void bytesAreHexFormatted() {
        assertThat(BaseRig.byteToStr(new byte[]{0x00, (byte) 0xff, 0x0a})).isEqualTo("00 ff 0a ");
    }
}
