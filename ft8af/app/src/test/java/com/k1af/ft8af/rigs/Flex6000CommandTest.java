package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for the FlexRadio 6000 SmartCAT (Kenwood "ZZ"-prefixed)
 * command parser. Command IDs are FOUR letters (e.g. ZZFA); frequency replies
 * are ZZFA/ZZFB carrying an integer Hz string. {@code android.util.Log} returns
 * defaults under plain JUnit.
 */
public class Flex6000CommandTest {

    @Test
    public void getCommand_splitsFourCharIdAndData() {
        Flex6000Command c = Flex6000Command.getCommand("ZZFA00014074000");
        assertThat(c).isNotNull();
        assertThat(c.getCommandID()).isEqualTo("ZZFA");
        assertThat(c.getData()).isEqualTo("00014074000");
    }

    @Test
    public void getCommand_idOnly_hasEmptyData() {
        Flex6000Command c = Flex6000Command.getCommand("ZZFA");
        assertThat(c).isNotNull();
        assertThat(c.getCommandID()).isEqualTo("ZZFA");
        assertThat(c.getData()).isEmpty();
    }

    @Test
    public void getCommand_tooShortReturnsNull() {
        assertThat(Flex6000Command.getCommand("ZZF")).isNull();
    }

    @Test
    public void getCommand_nonAlphaPrefixReturnsNull() {
        assertThat(Flex6000Command.getCommand("ZZ12abc")).isNull();
    }

    @Test
    public void getFrequency_parsesZzfaAndZzfb() {
        assertThat(Flex6000Command.getFrequency(Flex6000Command.getCommand("ZZFA00014074000")))
                .isEqualTo(14_074_000L);
        assertThat(Flex6000Command.getFrequency(Flex6000Command.getCommand("ZZFB00007074000")))
                .isEqualTo(7_074_000L);
    }

    @Test
    public void getFrequency_nonFreqCommandReturnsZero() {
        assertThat(Flex6000Command.getFrequency(Flex6000Command.getCommand("ZZMD2")))
                .isEqualTo(0L);
    }

    @Test
    public void getFrequency_unparseableReturnsZero() {
        assertThat(Flex6000Command.getFrequency(Flex6000Command.getCommand("ZZFAxyz")))
                .isEqualTo(0L);
    }
}
