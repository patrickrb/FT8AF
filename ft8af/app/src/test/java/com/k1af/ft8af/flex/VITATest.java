package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for the {@link VITA#VITA(byte[])} decoder — specifically the
 * crash fix for the 4-bit packet-type field.
 *
 * <p>A VITA49 header's packet-type field is 4 bits wide (values {@code 0..15}),
 * but {@link VitaPacketType} defines only 6 constants. The decoder previously did
 * {@code VitaPacketType.values()[(data[0] >> 4) & 0x0f]} with no bounds check, so
 * any packet whose first-byte high nibble was {@code >= 6} threw
 * {@link ArrayIndexOutOfBoundsException}.
 *
 * <p>VITA packets are decoded on the Flex/Xiegu discovery and stream read loops
 * ({@link RadioUdpClient} / {@code RadioTcpClient}), which catch only
 * {@code IOException}. An {@code ArrayIndexOutOfBoundsException} therefore escaped
 * the loop and crashed the whole app — any stray broadcast UDP datagram of at
 * least 28 bytes on the discovery port could trigger it. These tests pin the
 * guarded behaviour: reserved packet types are rejected (not crashed on) while all
 * six defined types still decode.
 *
 * <p>{@link VITA} touches no Android runtime types, so no Robolectric runner is
 * needed.
 */
public class VITATest {

    /** Build a minimal 28-byte VITA packet whose header sets the given nibbles. */
    private static byte[] packet(int packetTypeNibble) {
        byte[] data = new byte[VITA_MIN];
        // High nibble = packet type, low nibble (class/trailer bits) left clear.
        data[0] = (byte) ((packetTypeNibble & 0x0f) << 4);
        // data[1] = 0 -> TSI_NONE / TSF_NONE, so no timestamp fields are read.
        return data;
    }

    private static final int VITA_MIN = 28;

    @Test
    public void allDefinedPacketTypesDecode() {
        VitaPacketType[] types = VitaPacketType.values();
        for (int i = 0; i < types.length; i++) {
            VITA vita = new VITA(packet(i));
            assertThat(vita.isAvailable).isTrue();
            assertThat(vita.packetType).isEqualTo(types[i]);
        }
    }

    @Test
    public void reservedPacketType_isRejectedNotCrashed() {
        // Regression: nibble 6..15 indexed a 6-element enum -> AIOOBE on the read
        // thread -> whole-app crash. Must now be treated as an invalid packet.
        for (int nibble = VitaPacketType.values().length; nibble <= 0x0f; nibble++) {
            VITA vita = new VITA(packet(nibble));
            assertThat(vita.isAvailable).isFalse();
            assertThat(vita.packetType).isNull();
        }
    }

    @Test
    public void highBitPacketType_doesNotCrash() {
        // data[0] = (byte) 0xF0 is negative; (data[0] >> 4) is sign-extended, so
        // the & 0x0f mask matters. Nibble 0xF must still be rejected cleanly.
        VITA vita = new VITA(packet(0x0f));
        assertThat(vita.isAvailable).isFalse();
    }

    @Test
    public void tooShortPacket_isUnavailable() {
        VITA vita = new VITA(new byte[VITA_MIN - 1]);
        assertThat(vita.isAvailable).isFalse();
    }

    @Test
    public void nullPacket_isUnavailable() {
        VITA vita = new VITA((byte[]) null);
        assertThat(vita.isAvailable).isFalse();
    }

    /**
     * Build a VITA packet of {@code length} bytes whose header declares every
     * optional field present — stream id, class id, and both integer and
     * fractional timestamps — so the parse cursor advances the full 28-byte
     * header. With the trailer bit set and {@code length == 29}, this is the exact
     * datagram that made the payload length {@code 29 - 28 - 2 == -1}.
     */
    private static byte[] fullHeaderPacket(int length, boolean trailer) {
        byte[] data = new byte[length];
        // packetType = EXT_DATA_WITH_STREAM (ordinal 3) -> stream id present;
        // class-id bit (0x08); optional trailer bit (0x04).
        data[0] = (byte) ((VitaPacketType.EXT_DATA_WITH_STREAM.ordinal() << 4)
                | 0x08 | (trailer ? 0x04 : 0x00));
        // TSI in bits 7-6, TSF in bits 5-4, both non-zero -> both timestamps present.
        data[1] = (byte) 0x50;
        return data;
    }

    @Test
    public void truncatedPacketWithTrailerBit_doesNotThrow() {
        // Regression: a 29-byte packet with every header field plus the trailer bit
        // drove the parse cursor to 28 and made payload = new byte[29 - 28 - 2] =
        // new byte[-1] -> NegativeArraySizeException on the IOException-only UDP read
        // loop -> whole-app crash. Must now decode without throwing.
        VITA vita = new VITA(fullHeaderPacket(29, true));
        assertThat(vita.isAvailable).isTrue();
        assertThat(vita.payload).isNotNull();
        assertThat(vita.payload).hasLength(0);
    }

    @Test
    public void headerOnlyPacket_hasEmptyNonNullPayload() {
        // A valid 28-byte packet whose fields consume the whole header leaves no
        // payload region. payload used to stay null, and the Flex/Xiegu meter and
        // audio handlers dereference vita.payload with no null check -> NPE -> app
        // crash on the same UDP read loop.
        VITA vita = new VITA(fullHeaderPacket(28, false));
        assertThat(vita.isAvailable).isTrue();
        assertThat(vita.payload).isNotNull();
        assertThat(vita.payload).hasLength(0);
    }

    @Test
    public void payloadIsNeverNull_forInvalidPackets() {
        // Even packets the decoder rejects must not leave payload null: the discovery
        // handlers build `new String(vita.payload)` without gating on isAvailable.
        assertThat(new VITA(new byte[VITA_MIN - 1]).payload).isNotNull();
        assertThat(new VITA((byte[]) null).payload).isNotNull();
    }

    @Test
    public void payloadBytesAreExtractedAfterHeader() {
        // Regression guard: the length clamp must not disturb normal payload
        // extraction. packetType IF_DATA (no stream), no class id, no timestamps ->
        // cursor stays at 4; a 32-byte packet yields a 28-byte payload copied verbatim.
        byte[] data = new byte[32];
        data[0] = 0; // IF_DATA, no class/trailer bits
        data[1] = 0; // no timestamps
        for (int i = 4; i < data.length; i++) {
            data[i] = (byte) (i - 4);
        }
        VITA vita = new VITA(data);
        assertThat(vita.isAvailable).isTrue();
        assertThat(vita.payload).hasLength(28);
        assertThat(vita.payload[0]).isEqualTo((byte) 0);
        assertThat(vita.payload[27]).isEqualTo((byte) 27);
    }

    @Test
    public void trailerBytesAreExcludedFromPayload() {
        // Regression guard: with the trailer bit set the last 16-bit word is the
        // trailer, not payload, and must not be copied into payload.
        byte[] data = new byte[32];
        data[0] = 0x04; // IF_DATA + trailer bit (0x04), no class id
        data[1] = 0;    // no timestamps
        for (int i = 4; i < data.length; i++) {
            data[i] = (byte) i;
        }
        VITA vita = new VITA(data);
        assertThat(vita.isAvailable).isTrue();
        // offset 4 (no stream/class/timestamp); 32 - 4 - 2 trailer = 26 payload bytes.
        assertThat(vita.payload).hasLength(26);
    }
}
