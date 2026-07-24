package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for the FlexRadio 6000 reply handling: the coalesced-frame
 * drain (via {@link CatLineSplitter}) and {@link Flex6000Rig#frequencyFromFrame},
 * the per-frame dispatch that decides which frequency a drained command applies.
 *
 * <p>FlexRadio frames every reply as {@code ZZ<cmd><data>';'} (e.g.
 * {@code ZZFA00014074000;}) and the transport — the SmartSDR TCP link in
 * particular — delivers bytes in arbitrary chunks, routinely coalescing two
 * replies into one read. The old parser consumed only the <em>first</em>
 * {@code ';'}-terminated command and re-buffered the rest <em>with</em> its
 * terminator, where the next poll's {@code clearBufferData()} wiped it — so the
 * second reply was permanently lost and the retained terminator poisoned the
 * next parse. These tests pin the drain-then-dispatch contract.
 *
 * <p>No Android types are touched, so no Robolectric runner is needed.
 */
public class Flex6000RigTest {

    /** Drain a coalesced read the way {@code onReceiveData} now does. */
    private static CatLineSplitter.Result drain(String buffered, String incoming) {
        return CatLineSplitter.split(buffered, incoming, ';');
    }

    @Test
    public void frequencyFromFrame_zzfa_returnsParsedFrequency() {
        assertThat(Flex6000Rig.frequencyFromFrame("ZZFA00014074000")).isEqualTo(14074000L);
    }

    @Test
    public void frequencyFromFrame_zeroReadback_isRejected() {
        // A 0 read-back is treated as invalid and must not overwrite the freq.
        assertThat(Flex6000Rig.frequencyFromFrame("ZZFA00000000000")).isEqualTo(-1L);
    }

    @Test
    public void frequencyFromFrame_nonFrequencyCommand_isIgnored() {
        // A well-formed but unrelated command (e.g. a status reply) yields no update.
        assertThat(Flex6000Rig.frequencyFromFrame("ZZSW001")).isEqualTo(-1L);
    }

    @Test
    public void frequencyFromFrame_malformedFrame_isIgnored() {
        assertThat(Flex6000Rig.frequencyFromFrame("ZZ")).isEqualTo(-1L);
        assertThat(Flex6000Rig.frequencyFromFrame("")).isEqualTo(-1L);
    }

    @Test
    public void singleReply_frequencyApplied() {
        CatLineSplitter.Result r = drain("", "ZZFA00014074000;");

        assertThat(r.frames).containsExactly("ZZFA00014074000");
        assertThat(r.remainder).isEmpty();
        assertThat(Flex6000Rig.frequencyFromFrame(r.frames.get(0))).isEqualTo(14074000L);
    }

    @Test
    public void twoCoalescedReplies_bothFrequenciesRecovered() {
        // The regression: two ZZFA replies coalesce into one read. The old parser
        // applied only the first and dropped the second; both must now drain so
        // the latest frequency (14075000) wins.
        CatLineSplitter.Result r = drain("", "ZZFA00014074000;ZZFA00014075000;");

        assertThat(r.frames).containsExactly("ZZFA00014074000", "ZZFA00014075000").inOrder();
        assertThat(r.remainder).isEmpty();
        assertThat(Flex6000Rig.frequencyFromFrame(r.frames.get(0))).isEqualTo(14074000L);
        assertThat(Flex6000Rig.frequencyFromFrame(r.frames.get(1))).isEqualTo(14075000L);
    }

    @Test
    public void completeReplyPlusPartialTail_tailCarried() {
        CatLineSplitter.Result r = drain("", "ZZFA00014074000;ZZFA000140");

        assertThat(r.frames).containsExactly("ZZFA00014074000");
        assertThat(r.remainder).isEqualTo("ZZFA000140");
    }

    @Test
    public void replySplitAcrossReads_reassemblesViaRemainderCarry() {
        // First read has no terminator: its bytes become the remainder that the
        // caller re-feeds with the next read.
        CatLineSplitter.Result first = drain("", "ZZFA000140");
        assertThat(first.frames).isEmpty();
        assertThat(first.remainder).isEqualTo("ZZFA000140");

        CatLineSplitter.Result second = drain(first.remainder, "74000;");
        assertThat(second.frames).containsExactly("ZZFA00014074000");
        assertThat(Flex6000Rig.frequencyFromFrame(second.frames.get(0))).isEqualTo(14074000L);
    }
}
