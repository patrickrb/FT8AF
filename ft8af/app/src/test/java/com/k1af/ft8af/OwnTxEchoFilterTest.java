package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link OwnTxEchoFilter}, which drops own-TX loopback echoes
 * (decodes whose sender is our own callsign) from a decode cycle. This is the
 * core of the fix for the QSO panel showing our own transmissions twice when
 * the rig monitors TX audio back to line-in.
 *
 * <p>{@link Ft8Message}'s three-arg constructor takes (to, from, extra) and
 * upper-cases each, so {@code new Ft8Message("RA3XYZ", "UB8CSJ", "R-10")} is a
 * message we sent (from = UB8CSJ) to RA3XYZ — i.e. a loopback echo.
 */
@RunWith(RobolectricTestRunner.class)
public class OwnTxEchoFilterTest {

    private static final String MY_CALL = "UB8CSJ";

    @Before
    public void setUp() {
        GeneralVariables.myCallsign = MY_CALL;
    }

    @After
    public void tearDown() {
        GeneralVariables.myCallsign = "";
    }

    /** A message we transmitted (from == my callsign) is a loopback echo. */
    private Ft8Message ownEcho(String toCall) {
        return new Ft8Message(toCall, MY_CALL, "R-10");
    }

    /** A message another station sent to us (a reply). */
    private Ft8Message replyToMe(String fromCall) {
        return new Ft8Message(MY_CALL, fromCall, "-12");
    }

    /** Traffic between two other stations / a CQ — unrelated to us. */
    private Ft8Message thirdParty(String toCall, String fromCall) {
        return new Ft8Message(toCall, fromCall, "RR73");
    }

    @Test
    public void filter_dropsOwnTxEcho_keepsOthers() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(ownEcho("RA3XYZ"));
        decoded.add(thirdParty("CQ", "DL1ABC"));

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.kept).hasSize(1);
        assertThat(result.kept.get(0).getCallsignFrom()).isEqualTo("DL1ABC");
        assertThat(result.ownEchoCount).isEqualTo(1);
    }

    @Test
    public void filter_countsMultipleEchoes() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(ownEcho("RA3XYZ"));
        decoded.add(ownEcho("DL1ABC"));
        decoded.add(thirdParty("CQ", "JA1XYZ"));

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.ownEchoCount).isEqualTo(2);
        assertThat(result.kept).hasSize(1);
    }

    @Test
    public void filter_flagsReplyToMePresent() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(replyToMe("RA3XYZ"));
        decoded.add(thirdParty("CQ", "DL1ABC"));

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.replyToMePresent).isTrue();
        assertThat(result.kept).hasSize(2); // reply to me is kept, not an echo
        assertThat(result.ownEchoCount).isEqualTo(0);
    }

    @Test
    public void filter_replyToMeFalse_whenNoneAddressedToUs() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(thirdParty("CQ", "DL1ABC"));
        decoded.add(thirdParty("JA1XYZ", "VK2DEF"));

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.replyToMePresent).isFalse();
        assertThat(result.kept).hasSize(2);
    }

    @Test
    public void filter_emptyInput_returnsEmpty() {
        OwnTxEchoFilter result = OwnTxEchoFilter.filter(Collections.<Ft8Message>emptyList());

        assertThat(result.kept).isEmpty();
        assertThat(result.ownEchoCount).isEqualTo(0);
        assertThat(result.replyToMePresent).isFalse();
    }

    @Test
    public void filter_allEchoes_keptIsEmpty() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(ownEcho("RA3XYZ"));
        decoded.add(ownEcho("DL1ABC"));

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.kept).isEmpty();
        assertThat(result.ownEchoCount).isEqualTo(2);
    }

    @Test
    public void filter_noMyCallsignSet_dropsNothing() {
        // With no callsign configured, checkIsMyCallsign() returns false for
        // everything, so a message that *would* be ours must not be dropped.
        GeneralVariables.myCallsign = "";
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(new Ft8Message("RA3XYZ", "UB8CSJ", "R-10"));
        decoded.add(new Ft8Message("CQ", "DL1ABC", "JO31"));

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.kept).hasSize(2);
        assertThat(result.ownEchoCount).isEqualTo(0);
    }

    @Test
    public void filter_compoundCallsign_dropsBaseCallEcho() {
        // checkIsMyCallsign() reduces a compound call to its longest token, so
        // an echo decoded as the base call must still be recognised as ours.
        GeneralVariables.myCallsign = "UB8CSJ/P";
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(new Ft8Message("RA3XYZ", "UB8CSJ", "R-10")); // echo, base call
        decoded.add(new Ft8Message("CQ", "DL1ABC", "JO31"));

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.ownEchoCount).isEqualTo(1);
        assertThat(result.kept).hasSize(1);
        assertThat(result.kept.get(0).getCallsignFrom()).isEqualTo("DL1ABC");
    }

    @Test
    public void filter_doesNotMutateInput() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(ownEcho("RA3XYZ"));
        decoded.add(thirdParty("CQ", "DL1ABC"));

        OwnTxEchoFilter.filter(decoded);

        assertThat(decoded).hasSize(2); // original list untouched
    }

    @Test
    public void decodeLogLine_reportsKeptEchoReplyAndSlot() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(ownEcho("RA3XYZ"));        // dropped
        decoded.add(replyToMe("DL1ABC"));      // kept, reply to me
        decoded.add(thirdParty("CQ", "JA1XYZ")); // kept

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.decodeLogLine(1))
                .isEqualTo("DECODE: kept=2 ownEcho=1 junk=0 replyToMe=true slot=1");
    }

    @Test
    public void decodeLogLine_noEchoesNoReply() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(thirdParty("CQ", "DL1ABC"));

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.decodeLogLine(0))
                .isEqualTo("DECODE: kept=1 ownEcho=0 junk=0 replyToMe=false slot=0");
    }

    /** A structured decode whose sender renders as junk (e.g. ".<. >") is dropped. */
    private Ft8Message structuredJunk() {
        // i3=1 standard message; sender field is CRC-collision garbage.
        return new Ft8Message(1, 0, "K1ABC", ".<. >", "EN37");
    }

    /** A structured decode with a real sender callsign. */
    private Ft8Message structuredReal(String fromCall) {
        return new Ft8Message(1, 0, "K1ABC", fromCall, "EN37");
    }

    @Test
    public void filter_dropsJunkDecode_keepsReal() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(structuredJunk());
        decoded.add(structuredReal("W9XYZ"));

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.junkCount).isEqualTo(1);
        assertThat(result.kept).hasSize(1);
        assertThat(result.kept.get(0).getCallsignFrom()).isEqualTo("W9XYZ");
    }

    @Test
    public void filter_junkCountedSeparatelyFromEchoes() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(structuredJunk());          // dropped as junk
        decoded.add(ownEcho("RA3XYZ"));          // dropped as echo (free text)
        decoded.add(structuredReal("DL1ABC"));   // kept

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.junkCount).isEqualTo(1);
        assertThat(result.ownEchoCount).isEqualTo(1);
        assertThat(result.kept).hasSize(1);
    }

    @Test
    public void filter_freeTextWithNonCallsignSender_notJunk() {
        // Free text (i3=0,n3=0) legitimately carries non-callsign text, so it is
        // exempt from junk filtering even when the sender field isn't a callsign.
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(new Ft8Message(0, 0, "CQ", "TNX 73 GL", "FN42"));

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.junkCount).isEqualTo(0);
        assertThat(result.kept).hasSize(1);
    }

    @Test
    public void filter_junkAppearsInLogLine() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(structuredJunk());
        decoded.add(structuredReal("DL1ABC"));

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.decodeLogLine(0))
                .isEqualTo("DECODE: kept=1 ownEcho=0 junk=1 replyToMe=false slot=0");
    }

    /** Stamp a decode with a WSJT-style DT (seconds). */
    private static Ft8Message withDt(Ft8Message m, float dtSec) {
        m.time_sec = dtSec;
        return m;
    }

    /**
     * The clock-sync pill's value: the mean DT of the kept decodes. A TX-slot
     * loopback echo carries the TX chain latency as its DT (here +1.4 s), which
     * used to drag the raw slot mean to red; it must not contribute.
     */
    @Test
    public void meanTimeOffsetSec_excludesOwnTxEcho() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(withDt(ownEcho("RA3XYZ"), 1.4f));
        decoded.add(withDt(thirdParty("CQ", "DL1ABC"), 0.1f));
        decoded.add(withDt(replyToMe("W1AW"), -0.1f));

        assertThat(OwnTxEchoFilter.meanTimeOffsetSec(decoded)).isWithin(1e-6f).of(0f);
        assertThat(OwnTxEchoFilter.filter(decoded).meanTimeOffsetSec()).isWithin(1e-6f).of(0f);
    }

    /** Junk decodes carry a random DT and are excluded from the mean as well. */
    @Test
    public void meanTimeOffsetSec_excludesJunk() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(withDt(structuredJunk(), 2.3f));
        decoded.add(withDt(thirdParty("CQ", "DL1ABC"), 0.2f));
        decoded.add(withDt(thirdParty("CQ", "JA1XYZ"), 0.4f));

        assertThat(OwnTxEchoFilter.meanTimeOffsetSec(decoded)).isWithin(1e-6f).of(0.3f);
    }

    /** Plain mean over everything kept when nothing was filtered. */
    @Test
    public void meanTimeOffsetSec_isPlainMeanOfKept() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(withDt(thirdParty("CQ", "DL1ABC"), -0.5f));
        decoded.add(withDt(thirdParty("CQ", "JA1XYZ"), 0.1f));

        assertThat(OwnTxEchoFilter.meanTimeOffsetSec(decoded)).isWithin(1e-6f).of(-0.2f);
    }

    /** Echo-only slot (or empty list): no evidence, so NaN rather than a fake 0.0. */
    @Test
    public void meanTimeOffsetSec_isNaNWhenNothingKept() {
        List<Ft8Message> echoOnly = new ArrayList<>();
        echoOnly.add(withDt(ownEcho("RA3XYZ"), 1.4f));

        assertThat(OwnTxEchoFilter.meanTimeOffsetSec(echoOnly)).isNaN();
        assertThat(OwnTxEchoFilter.meanTimeOffsetSec(Collections.emptyList())).isNaN();
    }

    /**
     * The dropped echoes are retained, not just counted: full-duplex (satellite)
     * operating shows them back to the operator as their own downlink.
     */
    @Test
    public void filter_retainsTheDroppedEchoesInOrder() {
        Ft8Message firstEcho = ownEcho("RA3XYZ");
        Ft8Message secondEcho = ownEcho("DL1ABC");
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(firstEcho);
        decoded.add(thirdParty("CQ", "DL1ABC"));
        decoded.add(secondEcho);

        OwnTxEchoFilter filtered = OwnTxEchoFilter.filter(decoded);

        assertThat(filtered.echoes).containsExactly(firstEcho, secondEcho).inOrder();
        assertThat(filtered.ownEchoCount).isEqualTo(2);
    }

    /** Nothing of ours on the air: the echo list is empty, not null. */
    @Test
    public void filter_echoesIsEmptyWhenNothingWasOurs() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(thirdParty("CQ", "DL1ABC"));

        assertThat(OwnTxEchoFilter.filter(decoded).echoes).isEmpty();
    }

    /**
     * Junk decodes are dropped without being collected. They are CRC-collision
     * garbage with an implausible sender, so there is nothing to show even in
     * full duplex.
     */
    @Test
    public void filter_doesNotCollectJunkAsAnEcho() {
        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(structuredJunk());

        OwnTxEchoFilter filtered = OwnTxEchoFilter.filter(decoded);

        assertThat(filtered.kept).isEmpty();
        assertThat(filtered.echoes).isEmpty();
        assertThat(filtered.junkCount).isEqualTo(1);
    }
}
