package com.bg7yoz.ft8cn.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for the ICOM CI-V command parser/decoder.
 *
 * CI-V frame layout: {@code FE FE <to> <from> <cmd> [<sub>...] [<data>...] FD}.
 * {@link IcomCommand#getCommand} strips the leading {@code FE FE <to> <from>}
 * preamble check and the trailing {@code FD} terminator, keeping the bytes from
 * the first preamble byte up to (but not including) {@code FD} in {@code rawData}.
 *
 * Frequencies are 5-byte little-endian BCD. Reference frame for 14.074 MHz is the
 * one documented in production: {@code FE FE A4 E0 05 00 40 07 14 00 FD}, here
 * addressed to the controller as a read-frequency (cmd 03) reply.
 *
 * {@code android.util.Log} returns defaults under the unit-test JVM
 * (returnDefaultValues=true), so no Robolectric runner is needed.
 */
public class IcomCommandTest {

    private static final int CTRL = 0xE0; // controller (host) address
    private static final int RIG = 0xA4;  // IC-705 default rig address

    /** Helper: a read-frequency reply carrying 14.074 MHz (cmd 03, no sub-cmd). */
    private static byte[] freqFrame14074() {
        return new byte[]{
                (byte) 0xFE, (byte) 0xFE, (byte) CTRL, (byte) RIG,
                (byte) 0x03,                       // command: operating frequency
                (byte) 0x00, (byte) 0x40, (byte) 0x07, (byte) 0x14, (byte) 0x00, // BCD
                (byte) 0xFD};
    }

    @Test
    public void getCommand_parsesValidFrame() {
        IcomCommand cmd = IcomCommand.getCommand(CTRL, RIG, freqFrame14074());
        assertThat(cmd).isNotNull();
        assertThat(cmd.getCommandID()).isEqualTo(0x03);
    }

    @Test
    public void getCommand_acceptsZeroControllerAddress() {
        // The parser allows the "to" byte to be either the controller addr or 0x00.
        byte[] frame = freqFrame14074();
        frame[2] = 0x00;
        IcomCommand cmd = IcomCommand.getCommand(CTRL, RIG, frame);
        assertThat(cmd).isNotNull();
    }

    @Test
    public void getCommand_tooShortReturnsNull() {
        // length must be > 5.
        assertThat(IcomCommand.getCommand(CTRL, RIG,
                new byte[]{(byte) 0xFE, (byte) 0xFE, (byte) CTRL, (byte) RIG, 0x03}))
                .isNull();
    }

    @Test
    public void getCommand_wrongRigAddressReturnsNull() {
        byte[] frame = freqFrame14074();
        frame[3] = (byte) 0x70; // some other rig
        assertThat(IcomCommand.getCommand(CTRL, RIG, frame)).isNull();
    }

    @Test
    public void getCommand_noTerminatorReturnsNull() {
        // Valid preamble but no 0xFD terminator anywhere.
        byte[] frame = {(byte) 0xFE, (byte) 0xFE, (byte) CTRL, (byte) RIG,
                0x03, 0x00, 0x40, 0x07, 0x14, 0x00};
        assertThat(IcomCommand.getCommand(CTRL, RIG, frame)).isNull();
    }

    @Test
    public void getFrequency_decodesLittleEndianBcd() {
        IcomCommand cmd = IcomCommand.getCommand(CTRL, RIG, freqFrame14074());
        assertThat(cmd).isNotNull();
        // cmd 03 has no sub-command, so the data section begins at index 5.
        assertThat(cmd.getFrequency(false)).isEqualTo(14_074_000L);
    }

    @Test
    public void getData_returnsPayloadAfterCommand() {
        IcomCommand cmd = IcomCommand.getCommand(CTRL, RIG, freqFrame14074());
        assertThat(cmd).isNotNull();
        byte[] data = cmd.getData(false);
        assertThat(data).isNotNull();
        assertThat(data).hasLength(5); // five BCD bytes
        assertThat(data[1] & 0xFF).isEqualTo(0x40);
    }

    @Test
    public void getSubCommand_returnedFromSixthByte() {
        // Frame with a sub-command: cmd 15 (read meter), sub 12 (SWR).
        byte[] frame = {(byte) 0xFE, (byte) 0xFE, (byte) CTRL, (byte) RIG,
                (byte) 0x15, (byte) 0x12, (byte) 0x00, (byte) 0xFD};
        IcomCommand cmd = IcomCommand.getCommand(CTRL, RIG, frame);
        assertThat(cmd).isNotNull();
        assertThat(cmd.getCommandID()).isEqualTo(0x15);
        assertThat(cmd.getSubCommand()).isEqualTo(0x12);
    }

    @Test
    public void readShortData_bigEndianAssembly() {
        // readShortData packs data[start] as the high byte, data[start+1] as low.
        byte[] in = {(byte) 0x12, (byte) 0x34};
        assertThat(IcomCommand.readShortData(in, 0)).isEqualTo((short) 0x1234);
    }

    @Test
    public void readShortData_outOfRangeReturnsZero() {
        assertThat(IcomCommand.readShortData(new byte[]{0x01}, 0)).isEqualTo((short) 0);
    }
}
