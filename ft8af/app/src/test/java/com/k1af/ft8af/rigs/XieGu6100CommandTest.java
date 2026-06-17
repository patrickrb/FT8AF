package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for the XieGu X6100 CI-V parser. It mirrors the ICOM CI-V
 * format but deliberately IGNORES the rig (from) address during framing because
 * the X6100 firmware reports an inconsistent address (see production comment).
 * Frequencies are 5-byte little-endian BCD.
 *
 * {@code android.util.Log} returns defaults under plain JUnit.
 */
public class XieGu6100CommandTest {

    private static final int CTRL = 0xE0;
    private static final int RIG = 0x70; // XieGu default, but not enforced

    /** Read-frequency reply carrying 14.074 MHz. */
    private static byte[] freqFrame() {
        return new byte[]{
                (byte) 0xFE, (byte) 0xFE, (byte) CTRL, (byte) RIG,
                (byte) 0x03,
                (byte) 0x00, (byte) 0x40, (byte) 0x07, (byte) 0x14, (byte) 0x00,
                (byte) 0xFD};
    }

    @Test
    public void getCommand_parsesValidFrame() {
        XieGu6100Command c = XieGu6100Command.getCommand(CTRL, RIG, freqFrame());
        assertThat(c).isNotNull();
        assertThat(c.getCommandID()).isEqualTo(0x03);
    }

    @Test
    public void getCommand_ignoresRigAddress() {
        // The parser does not validate the rig (from) address, so a "wrong"
        // address still parses successfully — this is intentional per firmware.
        byte[] frame = freqFrame();
        frame[3] = (byte) 0xA4; // firmware sometimes reports A4 instead of 70
        XieGu6100Command c = XieGu6100Command.getCommand(CTRL, 0x70, frame);
        assertThat(c).isNotNull();
    }

    @Test
    public void getCommand_tooShortReturnsNull() {
        assertThat(XieGu6100Command.getCommand(CTRL, RIG,
                new byte[]{(byte) 0xFE, (byte) 0xFE, (byte) CTRL, (byte) RIG, 0x03}))
                .isNull();
    }

    @Test
    public void getCommand_noTerminatorReturnsNull() {
        byte[] frame = {(byte) 0xFE, (byte) 0xFE, (byte) CTRL, (byte) RIG,
                0x03, 0x00, 0x40, 0x07, 0x14, 0x00};
        assertThat(XieGu6100Command.getCommand(CTRL, RIG, frame)).isNull();
    }

    @Test
    public void getFrequency_decodesLittleEndianBcd() {
        XieGu6100Command c = XieGu6100Command.getCommand(CTRL, RIG, freqFrame());
        assertThat(c).isNotNull();
        assertThat(c.getFrequency(false)).isEqualTo(14_074_000L);
    }

    @Test
    public void readShortData_bigEndianAssembly() {
        assertThat(XieGu6100Command.readShortData(new byte[]{(byte) 0x12, (byte) 0x34}, 0))
                .isEqualTo((short) 0x1234);
    }

    @Test
    public void readShortData_outOfRangeReturnsZero() {
        assertThat(XieGu6100Command.readShortData(new byte[]{0x01}, 0)).isEqualTo((short) 0);
    }
}
