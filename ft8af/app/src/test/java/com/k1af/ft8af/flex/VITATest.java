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
}
