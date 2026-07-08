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
 * Coverage for the evidence-only parse path,
 * {@link FT8TransmitSignal#parseMessageToFunction(ArrayList, boolean)} with
 * {@code evidenceOnly=true} — how deep/subtraction/late-pass decodes drive the
 * QSO sequencer.
 *
 * <p>Background (POTA field report): the sequencer only ever saw the fast
 * decode pass, so a reply found only by a deep pass was displayed in the UI
 * but never advanced the QSO — the app repeated its previous message while the
 * partner re-sent the same report for several cycles. Deep passes must now
 * contribute <em>positive evidence</em> (advance, RR73→73, completion on 73,
 * caller enqueue) but never <em>absence-of-evidence</em> decisions (no-reply
 * counting, give-up) that the fast pass already made for the cycle.
 *
 * <p>Robolectric: the sequencer posts LiveData and logs through
 * {@code GeneralVariables.fileLog} (android.util.Log).
 */
@RunWith(RobolectricTestRunner.class)
public class EvidenceOnlyParseTest {

    /** Slot-1 (odd) utcTime for FT8: (15000+750)/1000/15 == 1. */
    private static final long RX_SLOT_UTC = 15_000L;
    /** Slot-0 (even) utcTime — the parity our test QSO transmits in. */
    private static final long OWN_SLOT_UTC = 0L;

    private FT8TransmitSignal signal;

    @Before
    public void setUp() {
        GeneralVariables.myCallsign = "K1AF";
        GeneralVariables.noReplyCount = 0;
        GeneralVariables.noReplyLimit = 3;
        GeneralVariables.houndMode = false;
        GeneralVariables.autoFollowCQ = false;
        GeneralVariables.autoCallFollow = false;
        GeneralVariables.autoCQAfterQSO = false;
        // TX=RX mode would make setTransmit persist the base frequency through
        // the (null) database; keep the offset fixed instead.
        GeneralVariables.synFrequency = false;
        GeneralVariables.qslRecordList.clear();
        GeneralVariables.transmitMessages.clear();
        // doComplete()/updateQSlRecordList resolve the partner's grid through
        // the database when it's not cached; seed the cache since there is no
        // database in these tests.
        GeneralVariables.addCallsignAndGrid("N2JFD", "FN20");

        // Null callbacks: nothing transmits in these tests, and a null
        // onDoTransmitted makes doComplete() skip its save/toast side effects
        // (which would otherwise reach the absent database).
        signal = new FT8TransmitSignal(null, null, null);
    }

    @After
    public void tearDown() {
        GeneralVariables.myCallsign = "";
        GeneralVariables.noReplyCount = 0;
        GeneralVariables.houndMode = false;
        GeneralVariables.qslRecordList.clear();
        GeneralVariables.callsignAndGrids.remove("N2JFD");
    }

    /**
     * Put the sequencer mid-QSO with N2JFD at the given order, transmitting in
     * slot 0 (the partner called in slot 1). Seeds the in-progress QSL record
     * so updateQSlRecordList never takes its record-creation branch, which
     * resolves the grid through the (absent) database.
     */
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

    // ---- positive evidence advances the QSO ---------------------------------

    @Test
    public void deepReport_advancesOrderToRR73() {
        // The exact field failure: we sent a report (order 2), the partner's
        // R-report only decoded in a deep pass. It must advance us to RR73 (4).
        startQsoAtOrder(2);
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "R-18")), true);
        assertThat(signal.getFunctionOrder()).isEqualTo(4);
        assertThat(GeneralVariables.noReplyCount).isEqualTo(0);
    }

    @Test
    public void deepReport_resetsNoReplyCount() {
        // A deep-decoded reply is a reply: the fast pass's no-reply count for
        // this target must clear.
        startQsoAtOrder(2);
        GeneralVariables.noReplyCount = 2;
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "R-18")), true);
        assertThat(GeneralVariables.noReplyCount).isEqualTo(0);
    }

    @Test
    public void deepRR73_advancesTo73Reply() {
        startQsoAtOrder(4);
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "RR73")), true);
        assertThat(signal.getFunctionOrder()).isEqualTo(5);
    }

    @Test
    public void deep73_completesQsoToCQ() {
        startQsoAtOrder(5);
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "73")), true);
        assertThat(signal.getFunctionOrder()).isEqualTo(6);
    }

    @Test
    public void deepCallerDuringQso_isQueued() {
        // A station calling us that only a deep pass decoded must still land in
        // the caller queue (the "decoded messages to me aren't answered" report).
        startQsoAtOrder(2);
        signal.parseMessageToFunction(list(rxMsg("K1AF", "KN6KI", "EM12")), true);
        assertThat(signal.getFunctionOrder()).isEqualTo(2);// current QSO untouched
        assertThat(signal.dequeueSpecificCaller("KN6KI")).isTrue();
    }

    // ---- absence-of-evidence decisions stay fast-pass-only ------------------

    @Test
    public void deepPassWithNothingNew_doesNotIncrementNoReply() {
        startQsoAtOrder(2);
        GeneralVariables.noReplyCount = 1;
        signal.parseMessageToFunction(list(rxMsg("CQ", "W1XYZ", "FN42")), true);
        assertThat(GeneralVariables.noReplyCount).isEqualTo(1);
        assertThat(signal.getFunctionOrder()).isEqualTo(2);
    }

    @Test
    public void fastPassWithNothingNew_stillIncrementsNoReply() {
        // Contrast case: the normal path's no-reply accounting must be intact.
        startQsoAtOrder(2);
        GeneralVariables.noReplyCount = 1;
        signal.parseMessageToFunction(list(rxMsg("CQ", "W1XYZ", "FN42")), false);
        assertThat(GeneralVariables.noReplyCount).isEqualTo(2);
        assertThat(signal.getFunctionOrder()).isEqualTo(2);
    }

    @Test
    public void deepPassAt73WithNothingNew_doesNotCompleteQso() {
        // functionOrder==5 && newOrder==-1 completes on the fast pass; a deep
        // pass finding nothing must NOT conclude "partner went silent".
        startQsoAtOrder(5);
        signal.parseMessageToFunction(list(rxMsg("CQ", "W1XYZ", "FN42")), true);
        assertThat(signal.getFunctionOrder()).isEqualTo(5);
    }

    // ---- slot and mode guards ------------------------------------------------

    @Test
    public void deepOwnSlotDecodes_doNotAdvance() {
        // Evidence lists bypass the newest-in-own-slot early-out (they can mix
        // slots), so the per-message parity check must still reject own-slot
        // decodes.
        startQsoAtOrder(2);
        Ft8Message ownSlot = rxMsg("K1AF", "N2JFD", "R-18");
        ownSlot.utcTime = OWN_SLOT_UTC;
        signal.parseMessageToFunction(list(ownSlot), true);
        assertThat(signal.getFunctionOrder()).isEqualTo(2);
        assertThat(GeneralVariables.noReplyCount).isEqualTo(0);
    }

    @Test
    public void houndMode_ignoresDeepPasses() {
        startQsoAtOrder(2);
        GeneralVariables.houndMode = true;
        signal.parseMessageToFunction(list(rxMsg("K1AF", "N2JFD", "R-18")), true);
        assertThat(signal.getFunctionOrder()).isEqualTo(2);
    }
}
