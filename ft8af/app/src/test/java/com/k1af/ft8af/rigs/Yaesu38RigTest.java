package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for {@link Yaesu38Rig#splitCommands}, the framing helper
 * that drains a Yaesu gen-3 ({@code ';'}-terminated) CAT text stream into whole
 * commands plus a trailing remainder.
 *
 * <p>The FT-2000 meter poll sends two reads back-to-back — {@code RM4;} (ALC)
 * then {@code RM6;} (SWR) — so the transport very often delivers both replies
 * coalesced in a single {@code onReceiveData} chunk ({@code "RM4nnn;RM6nnn;"}).
 * The old parser consumed only the first {@code ';'}-terminated command and
 * re-buffered the rest <em>with</em> its terminator, where the next poll's
 * {@code clearBufferData()} wiped it — so the SWR (second) reply was
 * permanently lost and the SWR meter never moved. These tests pin the drain
 * contract: every complete command is returned, and only the unterminated tail
 * is carried into the next read.
 *
 * <p>No Android types are touched, so no Robolectric runner is needed.
 */
public class Yaesu38RigTest {

    @Test
    public void singleCompleteCommand_oneCommandNoRemainder() {
        Yaesu38Rig.Frames f = Yaesu38Rig.splitCommands("FA007074000;");

        assertThat(f.commands).containsExactly("FA007074000");
        assertThat(f.remainder).isEmpty();
    }

    @Test
    public void twoCoalescedMeterReplies_bothCommandsReturned() {
        // The regression: ALC then SWR arrive coalesced in one read. The old
        // parser returned only the first (ALC); SWR was dropped. Both must be
        // drained so the SWR meter updates.
        Yaesu38Rig.Frames f = Yaesu38Rig.splitCommands("RM4001;RM6002;");

        assertThat(f.commands).containsExactly("RM4001", "RM6002").inOrder();
        assertThat(f.remainder).isEmpty();
    }

    @Test
    public void completeCommandPlusPartialTail_tailBecomesRemainder() {
        Yaesu38Rig.Frames f = Yaesu38Rig.splitCommands("RM4001;RM6");

        assertThat(f.commands).containsExactly("RM4001");
        assertThat(f.remainder).isEqualTo("RM6");
    }

    @Test
    public void noTerminator_wholeInputIsRemainder() {
        Yaesu38Rig.Frames f = Yaesu38Rig.splitCommands("FA0070");

        assertThat(f.commands).isEmpty();
        assertThat(f.remainder).isEqualTo("FA0070");
    }

    @Test
    public void emptyInput_noCommandsNoRemainder() {
        Yaesu38Rig.Frames f = Yaesu38Rig.splitCommands("");

        assertThat(f.commands).isEmpty();
        assertThat(f.remainder).isEmpty();
    }

    @Test
    public void leadingTerminator_yieldsEmptyLeadingCommand() {
        // A stray leading ';' (e.g. the tail terminator of a prior frame) must
        // not swallow the command that follows it.
        Yaesu38Rig.Frames f = Yaesu38Rig.splitCommands(";RM6002;");

        assertThat(f.commands).containsExactly("", "RM6002").inOrder();
        assertThat(f.remainder).isEmpty();
    }

    @Test
    public void splitAcrossReads_reassemblesViaRemainderCarry() {
        // A command split over two reads: the first read has no terminator, so
        // its bytes become the remainder that the caller re-feeds with the next
        // read. Draining the concatenation recovers the whole command.
        Yaesu38Rig.Frames first = Yaesu38Rig.splitCommands("FA0070");
        assertThat(first.commands).isEmpty();

        Yaesu38Rig.Frames second = Yaesu38Rig.splitCommands(first.remainder + "74000;");
        assertThat(second.commands).containsExactly("FA007074000");
        assertThat(second.remainder).isEmpty();
    }

    @Test
    public void threeCommandsPlusTail_allDrainedTailCarried() {
        Yaesu38Rig.Frames f = Yaesu38Rig.splitCommands("RM4001;RM6002;FA007074000;RM4");

        assertThat(f.commands)
                .containsExactly("RM4001", "RM6002", "FA007074000").inOrder();
        assertThat(f.remainder).isEqualTo("RM4");
    }
}
