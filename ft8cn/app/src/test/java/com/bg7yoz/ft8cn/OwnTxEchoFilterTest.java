package com.bg7yoz.ft8cn;

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

    /**
     * A junk/false decode (garbage sender callsign such as ".<. >") is dropped
     * and counted separately from own-TX echoes.
     */
    @Test
    public void filter_dropsJunkDecode() {
        Ft8Message junk = new Ft8Message("CQ", ".<. >", "FN42");
        junk.i3 = 1;// standard message type -> sender must be a real callsign

        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(junk);
        decoded.add(thirdParty("CQ", "DL1ABC"));

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.junkCount).isEqualTo(1);
        assertThat(result.ownEchoCount).isEqualTo(0);
        assertThat(result.kept).hasSize(1);
        assertThat(result.kept.get(0).getCallsignFrom()).isEqualTo("DL1ABC");
    }

    @Test
    public void decodeLogLine_reportsJunkCount() {
        Ft8Message junk = new Ft8Message("CQ", ".<. >", "FN42");
        junk.i3 = 1;

        List<Ft8Message> decoded = new ArrayList<>();
        decoded.add(junk);
        decoded.add(thirdParty("CQ", "DL1ABC"));

        OwnTxEchoFilter result = OwnTxEchoFilter.filter(decoded);

        assertThat(result.decodeLogLine(0))
                .isEqualTo("DECODE: kept=1 ownEcho=0 junk=1 replyToMe=false slot=0");
    }
}
