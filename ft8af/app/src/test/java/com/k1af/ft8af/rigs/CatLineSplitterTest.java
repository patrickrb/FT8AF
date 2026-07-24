package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for {@link CatLineSplitter}, the shared text-terminator
 * (Yaesu / Kenwood / Elecraft / Flex-6000) CAT stream reassembler.
 *
 * <p>These rigs frame commands as {@code <ID><data>;} and the transport delivers
 * bytes in arbitrary chunks, so a command can span several callbacks or several
 * commands can arrive in one read. These tests pin the reassembly contract and
 * lock in the regression the old per-rig reassembly had: it parsed only the
 * <em>first</em> command in a read and re-buffered the remainder <em>with</em> its
 * retained terminator, so a coalesced second reply (routine for the ALC+SWR meter
 * poll) was dropped and the retained {@code ';'} then poisoned the next parse.
 *
 * <p>No Android types are touched, so no Robolectric runner is needed.
 */
public class CatLineSplitterTest {

    @Test
    public void singleCompleteCommand_oneFrameNoRemainder() {
        CatLineSplitter.Result r = CatLineSplitter.split("", "FA014074000;", ';');
        assertThat(r.frames).containsExactly("FA014074000");
        assertThat(r.remainder).isEmpty();
    }

    @Test
    public void twoCommandsInOneRead_bothReturned() {
        // The regression: the ALC and SWR meter replies coalesce into one read.
        CatLineSplitter.Result r = CatLineSplitter.split("", "RM4100;RM6020;", ';');
        assertThat(r.frames).containsExactly("RM4100", "RM6020").inOrder();
        assertThat(r.remainder).isEmpty();
    }

    @Test
    public void terminatorStrippedFromEveryFrame() {
        CatLineSplitter.Result r = CatLineSplitter.split("", "FA1;FB2;", ';');
        for (String frame : r.frames) {
            assertThat(frame).doesNotContain(";");
        }
    }

    @Test
    public void incompleteCommand_noFramesCarriedAsRemainder() {
        CatLineSplitter.Result r = CatLineSplitter.split("", "FA0140", ';');
        assertThat(r.frames).isEmpty();
        assertThat(r.remainder).isEqualTo("FA0140");
    }

    @Test
    public void commandSplitAcrossTwoReads_reassembledFromBufferedRemainder() {
        CatLineSplitter.Result first = CatLineSplitter.split("", "FA0140", ';');
        assertThat(first.frames).isEmpty();

        CatLineSplitter.Result second = CatLineSplitter.split(first.remainder, "74000;", ';');
        assertThat(second.frames).containsExactly("FA014074000");
        assertThat(second.remainder).isEmpty();
    }

    @Test
    public void completeCommandFollowedByStartOfNext_remainderCarried() {
        CatLineSplitter.Result r = CatLineSplitter.split("", "FA014074000;RM6", ';');
        assertThat(r.frames).containsExactly("FA014074000");
        assertThat(r.remainder).isEqualTo("RM6");
    }

    @Test
    public void bufferedRemainderPrependedBeforeSplitting() {
        // A whole command sitting in the carry-over buffer plus a fresh terminated
        // command must yield two frames, never a single poisoned concatenation.
        CatLineSplitter.Result r = CatLineSplitter.split("RM6020;", "FA014076000;", ';');
        assertThat(r.frames).containsExactly("RM6020", "FA014076000").inOrder();
        assertThat(r.remainder).isEmpty();
    }

    @Test
    public void emptyFrames_preservedForAdjacentAndLeadingTerminators() {
        CatLineSplitter.Result r = CatLineSplitter.split("", ";FA1;;", ';');
        assertThat(r.frames).containsExactly("", "FA1", "").inOrder();
        assertThat(r.remainder).isEmpty();
    }

    @Test
    public void noInputAtAll_noFramesEmptyRemainder() {
        CatLineSplitter.Result r = CatLineSplitter.split("", "", ';');
        assertThat(r.frames).isEmpty();
        assertThat(r.remainder).isEmpty();
    }

    @Test
    public void yaesuGen3MeterPoll_swrSurvivesCoalescedAndPartialReads() {
        // Models the FT-450 / DX10 / Wolf-SDR meter poll: setRead39Meters_ALC()
        // then _SWR() sent back-to-back. The transport first delivers the ALC
        // reply plus the leading bytes of the SWR reply, then the tail. Draining
        // must surface the ALC frame on the first read and the SWR frame on the
        // second -- the old parse would have dropped the coalesced SWR reply and
        // then poisoned the follow-up read with the retained ';'.
        CatLineSplitter.Result first = CatLineSplitter.split("", "RM4100;RM6", ';');
        assertThat(first.frames).containsExactly("RM4100");
        assertThat(first.remainder).isEqualTo("RM6");

        CatLineSplitter.Result second = CatLineSplitter.split(first.remainder, "020;", ';');
        assertThat(second.frames).containsExactly("RM6020");
        assertThat(second.remainder).isEmpty();
    }

    @Test
    public void elecraftCoalescedSwrMeterPolls_bothReadingsSurvive() {
        // During TX the Elecraft SWR meter is polled repeatedly and two "SW"
        // replies routinely coalesce into one read. The old per-rig reassembly
        // kept only the first and dropped the second (higher, i.e. worse) reading,
        // so the SWR alert could miss a spike. Both must now be delivered.
        CatLineSplitter.Result r = CatLineSplitter.split("", "SW030;SW045;", ';');
        assertThat(r.frames).containsExactly("SW030", "SW045").inOrder();
        assertThat(r.remainder).isEmpty();
    }

    @Test
    public void elecraftFreqThenMeter_retainedTerminatorCannotPoisonNextParse() {
        // A freq reply coalesced with a meter reply: the old code parsed FA, then
        // re-buffered "SW045;" WITH its ';'. If a further read arrived before the
        // buffer was cleared, "SW045;" + next command concatenated into a single
        // poisoned parse. The splitter yields both as clean, separate frames.
        CatLineSplitter.Result r = CatLineSplitter.split("", "FA00014074000;SW045;", ';');
        assertThat(r.frames).containsExactly("FA00014074000", "SW045").inOrder();
        assertThat(r.remainder).isEmpty();
    }

    @Test
    public void carriageReturnTerminator_supportedForSiblingRigs() {
        // The splitter is terminator-parameterised so the Kenwood/Elecraft '\r'
        // handlers can adopt it too.
        CatLineSplitter.Result r = CatLineSplitter.split("", "IF00014074000\rFA1\r", '\r');
        assertThat(r.frames).containsExactly("IF00014074000", "FA1").inOrder();
        assertThat(r.remainder).isEmpty();
    }

    // --- Multi-terminator overload: the Kenwood family (TS-570/590/2000, TX-500)
    // frames replies with ';' but the old per-rig handlers split on '\r', so no
    // reply ever parsed (freq read-back + SWR/ALC protection dead, issues
    // #599/#589). These rigs adopt split(..., ";\r") to parse the real ';' stream
    // while still tolerating a stray '\r'.

    @Test
    public void kenwoodSemicolonReply_parsedByMultiTerminatorSplit() {
        // The exact reply the TX-500 reporter documented in #599: RM10023; -- a
        // ';'-terminated meter frame with no '\r'. The old '\r' split never saw a
        // terminator and buffered it forever, so SWR stayed 0 and protection never
        // tripped. The ";\r" split yields the frame.
        CatLineSplitter.Result r = CatLineSplitter.split("", "RM10023;", ";\r");
        assertThat(r.frames).containsExactly("RM10023");
        assertThat(r.remainder).isEmpty();
    }

    @Test
    public void kenwoodMeterPoll_swrAndAlcCoalesced_bothSurvive() {
        // A single RM; poll on the TS-590 answers the SWR (RM1) and ALC (RM3)
        // meters back-to-back; both must be delivered so neither reading is stale.
        CatLineSplitter.Result r = CatLineSplitter.split("", "RM10023;RM30150;", ";\r");
        assertThat(r.frames).containsExactly("RM10023", "RM30150").inOrder();
        assertThat(r.remainder).isEmpty();
    }

    @Test
    public void kenwoodFreqThenMeterCoalesced_bothParsedInOrder() {
        CatLineSplitter.Result r = CatLineSplitter.split("", "FA00014074000;RM10023;", ";\r");
        assertThat(r.frames).containsExactly("FA00014074000", "RM10023").inOrder();
        assertThat(r.remainder).isEmpty();
    }

    @Test
    public void multiTerminator_semicolonSplitAcrossReads_reassembled() {
        CatLineSplitter.Result first = CatLineSplitter.split("", "FA000140", ";\r");
        assertThat(first.frames).isEmpty();
        assertThat(first.remainder).isEqualTo("FA000140");

        CatLineSplitter.Result second = CatLineSplitter.split(first.remainder, "74000;", ";\r");
        assertThat(second.frames).containsExactly("FA00014074000");
        assertThat(second.remainder).isEmpty();
    }

    @Test
    public void multiTerminator_toleratesStrayCarriageReturnAroundSemicolon() {
        // If a transport ever interleaves a '\r' (CR/LF-style framing), the empty
        // span between ';' and '\r' is an empty frame the rig treats as a no-op
        // (Yaesu3Command.getCommand returns null for <2 chars), and the real
        // command is still delivered intact -- so accepting both cannot regress a
        // '\r'-influenced stream.
        CatLineSplitter.Result r = CatLineSplitter.split("", "RM10023;\r", ";\r");
        assertThat(r.frames).containsExactly("RM10023", "").inOrder();
        assertThat(r.remainder).isEmpty();
    }

    @Test
    public void charAndStringOverloadsAgree_forSingleTerminator() {
        CatLineSplitter.Result viaChar = CatLineSplitter.split("", "FA1;FB2;", ';');
        CatLineSplitter.Result viaString = CatLineSplitter.split("", "FA1;FB2;", ";");
        assertThat(viaString.frames).containsExactlyElementsIn(viaChar.frames).inOrder();
        assertThat(viaString.remainder).isEqualTo(viaChar.remainder);
    }
}
