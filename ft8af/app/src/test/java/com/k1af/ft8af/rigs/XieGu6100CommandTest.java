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

    /** Build a read-frequency reply carrying the 5 little-endian BCD frequency bytes. */
    private static byte[] freqFrame(int b0, int b1, int b2, int b3, int b4) {
        return new byte[]{
                (byte) 0xFE, (byte) 0xFE, (byte) CTRL, (byte) RIG,
                (byte) 0x03,
                (byte) b0, (byte) b1, (byte) b2, (byte) b3, (byte) b4,
                (byte) 0xFD};
    }

    /**
     * The most-significant BCD digit is the 1 GHz place and must be weighted 10^9,
     * matching {@link IcomCommand#getFrequency}. Weighting it 10^8 (a copy/paste of
     * the 100 MHz multiplier) drops that digit by an order of magnitude — e.g.
     * 1.296 GHz would decode as 0.396 GHz.
     */
    @Test
    public void getFrequency_weightsGigahertzDigitCorrectly() {
        // 1,296,074,000 Hz -> BCD 00 40 07 96 12 (GHz digit = 1)
        XieGu6100Command c = XieGu6100Command.getCommand(CTRL, RIG,
                freqFrame(0x00, 0x40, 0x07, 0x96, 0x12));
        assertThat(c).isNotNull();
        assertThat(c.getFrequency(false)).isEqualTo(1_296_074_000L);
    }

    /**
     * A frequency above {@link Integer#MAX_VALUE} must survive the BCD assembly: the
     * decoder returns {@code long}, so the arithmetic has to stay in {@code long}
     * rather than overflowing an {@code int} partway through (again mirroring
     * {@link IcomCommand#getFrequency}, whose top term is a {@code long} literal).
     */
    @Test
    public void getFrequency_doesNotOverflowIntAboveTwoGigahertz() {
        // 2,400,000,000 Hz -> BCD 00 00 00 00 24 (> Integer.MAX_VALUE)
        XieGu6100Command c = XieGu6100Command.getCommand(CTRL, RIG,
                freqFrame(0x00, 0x00, 0x00, 0x00, 0x24));
        assertThat(c).isNotNull();
        assertThat(c.getFrequency(false)).isEqualTo(2_400_000_000L);
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
