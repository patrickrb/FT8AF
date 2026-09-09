package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.log.QSLRecord;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

/**
 * Sequencer-level coverage for the "Max 73 Sends" cap
 * ({@code GeneralVariables.max73Sends}) inside
 * {@link FT8TransmitSignal#parseMessageToFunction(ArrayList, boolean)}: the cap
 * must end the QSO in exactly the loops the no-reply caps cannot reach —
 * a partner repeating RR73 at our 73 (each one re-triggers a 73 reply), or
 * re-sending R+report at our RR73 (each decode resets the no-reply counter) —
 * and must change nothing when set to Auto (0).
 *
 * <p>Same Robolectric harness as {@link EvidenceOnlyParseTest}: the sequencer
 * posts LiveData and logs through android.util.Log.
 */
@RunWith(RobolectricTestRunner.class)
public class Max73SendsSequencerTest {

    /** Slot-1 (odd) utcTime for FT8: (15000+750)/1000/15 == 1. */
    private static final long RX_SLOT_UTC = 15_000L;

    private FT8TransmitSignal signal;

    @Before
    public void setUp() {
        GeneralVariables.myCallsign = "K1AF";
        GeneralVariables.noReplyCount = 0;
        GeneralVariables.noReplyLimit = 3;
        GeneralVariables.max73Sends = 0;
        GeneralVariables.houndMode = false;
        GeneralVariables.autoFollowCQ = false;
        GeneralVariables.autoCallFollow = false;
        GeneralVariables.autoCQAfterQSO = false;
        GeneralVariables.synFrequency = false;
        GeneralVariables.qslRecordList.clear();
        GeneralVariables.transmitMessages.clear();
        GeneralVariables.addCallsignAndGrid("N2JFD", "FN20");
        signal = new FT8TransmitSignal(null, null, null);
    }

    @After
    public void tearDown() {
        GeneralVariables.myCallsign = "";
        GeneralVariables.noReplyCount = 0;
        GeneralVariables.max73Sends = 0;
        GeneralVariables.qslRecordList.clear();
        GeneralVariables.callsignAndGrids.remove("N2JFD");
    }

    /** Put the sequencer mid-QSO with N2JFD at the given order (TX slot 0). */
    private void startQsoAtOrder(int order) {
        QSLRecord record = new QSLRecord(0, 0, "K1AF", "EM28", "N2JFD", "FM09",
                -17, -18, "FT8", GeneralVariables.band, 14074000);
        GeneralVariables.qslRecordList.addQSLRecord(record);
        signal.setTransmit(new TransmitCallsign(1, 0, "N2JFD", 1500f, 1, -17), order, "");
    }

    /** A decoded message in the partner's (odd) slot. */
    private static Ft8Message rxMsg(String to, String from, String extra) {
        Ft8Message msg = new Ft8Message(1, 0, to, from, extra);
        msg.utcTime = RX_SLOT_UTC;
        msg.band = GeneralVariables.band;
        msg.snr = -10;
        return msg;
    }

    private static ArrayList<Ft8Message> list(Ft8Message... msgs) {
        ArrayList<Ft8Message> out = new ArrayList<>();
        for (Ft8Message m : msgs) out.add(m);
        return out;
    }

    // ---- the RR73→73 loop (partner never decodes our 73) ---------------------

    @Test
    public void repeatedRR73_belowCap_stillReplies73() {
        GeneralVariables.max73Sends = 3;
        startQsoAtOrder(5);
        signal.finalAckSends = 2;// two 73s already on the air
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "RR73")), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(5);// keep replying 73
    }

    @Test
    public void repeatedRR73_atCap_endsQsoInsteadOfAnother73() {
        // Without the cap this loop is unbounded: every received RR73
        // re-triggers a 73 reply, and noReplyCount resets on each decode.
        GeneralVariables.max73Sends = 3;
        startQsoAtOrder(5);
        signal.finalAckSends = 3;
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "RR73")), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(6);// moved on to CQ
    }

    // ---- the RR73 loop (partner never decodes our RR73) ----------------------

    @Test
    public void partnerResendsReportAtOurRR73_atCap_endsQso() {
        // The partner keeps re-sending R+report, so newOrder is never -1 and
        // the no-reply caps in shouldCompleteQso never fire. The send cap must.
        GeneralVariables.max73Sends = 2;
        startQsoAtOrder(4);
        signal.finalAckSends = 2;
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "R-18")), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(6);
    }

    @Test
    public void partnerResendsReportAtOurRR73_belowCap_keepsRR73() {
        GeneralVariables.max73Sends = 4;
        startQsoAtOrder(4);
        signal.finalAckSends = 2;
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "R-18")), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(4);// classic re-send
    }

    // ---- deterministic own-send evidence applies on deep passes too ----------

    @Test
    public void deepPass_atCap_alsoEndsQso() {
        GeneralVariables.max73Sends = 3;
        startQsoAtOrder(5);
        signal.finalAckSends = 3;
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "RR73")), true);
        assertThat(signal.getFunctionOrder()).isEqualTo(6);
    }

    // ---- Auto (0) keeps the classic behavior ---------------------------------

    @Test
    public void autoSetting_repeatedRR73_keepsReplying73() {
        GeneralVariables.max73Sends = 0;
        startQsoAtOrder(5);
        signal.finalAckSends = 50;
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "RR73")), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(5);
    }

    // ---- the capped-station gate ----------------------------------------------

    @Test
    public void cappedStationRepeatsRR73_whileWeCQ_staysIgnored() {
        GeneralVariables.max73Sends = 3;
        startQsoAtOrder(5);
        signal.finalAckSends = 3;
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "RR73")), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(6);
        // Next cycle: the still-deaf partner repeats RR73 at our CQ. Without
        // the gate this would re-answer them and resume the capped loop.
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "RR73")), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(6);
    }

    @Test
    public void cappedStationFreshGridCall_isAnsweredAndLiftsGate() {
        GeneralVariables.max73Sends = 3;
        startQsoAtOrder(5);
        signal.finalAckSends = 3;
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "RR73")), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(6);
        // A brand-new call from them (grid message) is a fresh QSO attempt:
        // answer it, and the gate lifts for the new contact.
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "FN20")), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(2);
        assertThat(signal.cappedCallsign).isEmpty();
    }

    // ---- counter lifecycle ----------------------------------------------------

    @Test
    public void newTarget_resetsCounter() {
        startQsoAtOrder(4);
        signal.finalAckSends = 3;
        // A fresh QSO with a different station must not inherit the count.
        signal.setTransmit(new TransmitCallsign(1, 0, "KN6KI", 1500f, 1, -17), 1, "");
        assertThat(signal.finalAckSends).isEqualTo(0);
    }

    @Test
    public void deactivate_resetsCounterAndGate() {
        startQsoAtOrder(4);
        signal.finalAckSends = 3;
        signal.cappedCallsign = "N2JFD";
        signal.setActivated(false);
        assertThat(signal.finalAckSends).isEqualTo(0);
        assertThat(signal.cappedCallsign).isEmpty();
    }
}
