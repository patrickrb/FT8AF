package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for the Elecraft (Kenwood-style ASCII CAT) command parser.
 *
 * Frames are {@code <2-letter ID><data>} with the trailing semicolon already
 * stripped by the caller. Frequency replies use FA/FB carrying an integer Hz
 * string. {@code android.util.Log} returns defaults under plain JUnit.
 */
public class ElecraftCommandTest {

    @Test
    public void getCommand_splitsIdAndData() {
        ElecraftCommand c = ElecraftCommand.getCommand("FA00014074000");
        assertThat(c).isNotNull();
        assertThat(c.getCommandID()).isEqualTo("FA");
        assertThat(c.getData()).isEqualTo("00014074000");
    }

    @Test
    public void getCommand_idOnly_hasEmptyData() {
        ElecraftCommand c = ElecraftCommand.getCommand("FA");
        assertThat(c).isNotNull();
        assertThat(c.getCommandID()).isEqualTo("FA");
        assertThat(c.getData()).isEmpty();
    }

    @Test
    public void getCommand_tooShortReturnsNull() {
        assertThat(ElecraftCommand.getCommand("F")).isNull();
        assertThat(ElecraftCommand.getCommand("")).isNull();
    }

    @Test
    public void getCommand_nonAlphaPrefixReturnsNull() {
        assertThat(ElecraftCommand.getCommand("12abc")).isNull();
    }

    @Test
    public void getFrequency_parsesFaAndFb() {
        ElecraftCommand fa = ElecraftCommand.getCommand("FA00014074000");
        ElecraftCommand fb = ElecraftCommand.getCommand("FB00007074000");
        assertThat(ElecraftCommand.getFrequency(fa)).isEqualTo(14_074_000L);
        assertThat(ElecraftCommand.getFrequency(fb)).isEqualTo(7_074_000L);
    }

    @Test
    public void getFrequency_nonFreqCommandReturnsZero() {
        ElecraftCommand md = ElecraftCommand.getCommand("MD2");
        assertThat(ElecraftCommand.getFrequency(md)).isEqualTo(0L);
    }

    @Test
    public void getFrequency_unparseableDataReturnsZero() {
        // FA with non-numeric data triggers the catch -> 0.
        ElecraftCommand bad = ElecraftCommand.getCommand("FAxyz");
        assertThat(ElecraftCommand.getFrequency(bad)).isEqualTo(0L);
    }

    @Test
    public void isSWRMeter_trueWhenAtLeastThreeDataChars() {
        ElecraftCommand c = ElecraftCommand.getCommand("SW012");
        assertThat(ElecraftCommand.isSWRMeter(c)).isTrue();
    }

    @Test
    public void isSWRMeter_falseWhenDataTooShort() {
        ElecraftCommand c = ElecraftCommand.getCommand("SW1");
        assertThat(ElecraftCommand.isSWRMeter(c)).isFalse();
    }

    @Test
    public void getSWRMeter_readsFirstThreeDigits() {
        ElecraftCommand c = ElecraftCommand.getCommand("SW027");
        assertThat(ElecraftCommand.getSWRMeter(c)).isEqualTo(27);
    }
}
