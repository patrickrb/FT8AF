package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.log.QSLRecord;
import com.k1af.ft8af.timer.UtcTimer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

/**
 * Sequencer-level coverage for caller-queue liveness: the queue tracks who is
 * calling us on every pass of a QSO, and a caller who has stopped calling is
 * dropped instead of being called back after the QSO.
 *
 * <p>Field report: "I'm tired of calling people that have given up." A station
 * that called once during a QSO stayed queued and got several overs of
 * "W1ABC K1AF -10" at nobody after the QSO ended, while stations still calling
 * waited. Only a caller heard in the most recent receive slot is worked from the
 * queue; otherwise we go back to CQ (or answer whoever is calling right now).
 *
 * <p>Message slot times are real "now"-based slot boundaries because freshness is
 * judged against {@link UtcTimer#getSystemTime()}. Robolectric: the sequencer
 * posts LiveData and logs through {@code GeneralVariables.fileLog}.
 */
@RunWith(RobolectricTestRunner.class)
public class CallerQueueLivenessTest {

    private static final int SLOT_MS = 15_000;
    private static final int CYCLE_MS = 2 * SLOT_MS;

    private FT8TransmitSignal signal;
    /** Start of the most recent odd (partner/RX) slot — "the slot just decoded". */
    private long rxSlotUtc;

    @Before
    public void setUp() {
        GeneralVariables.myCallsign = "K1AF";
        GeneralVariables.noReplyCount = 0;
        GeneralVariables.noReplyLimit = 3;
        GeneralVariables.houndMode = false;
        GeneralVariables.autoFollowCQ = false;
        GeneralVariables.autoCallFollow = false;
        GeneralVariables.autoCQAfterQSO = false;
        GeneralVariables.pileupStrongestFirst = false;
        GeneralVariables.synFrequency = false;
        GeneralVariables.qslRecordList.clear();
        GeneralVariables.transmitMessages.clear();
        GeneralVariables.addCallsignAndGrid("N2JFD", "FN20");
        UtcTimer.delay = 0;

        long now = UtcTimer.getSystemTime();
        long slotStart = now - now % SLOT_MS;
        rxSlotUtc = ((slotStart / SLOT_MS) % 2 == 1) ? slotStart : slotStart - SLOT_MS;

        signal = new FT8TransmitSignal(null, null, null);
    }

    @After
    public void tearDown() {
        GeneralVariables.myCallsign = "";
        GeneralVariables.noReplyCount = 0;
        GeneralVariables.pileupStrongestFirst = false;
        GeneralVariables.qslRecordList.clear();
        GeneralVariables.callsignAndGrids.remove("N2JFD");
    }

    /** Mid-QSO with N2JFD at the given order, transmitting in the even slot. */
    private void startQsoAtOrder(int order) {
        QSLRecord record = new QSLRecord(0, 0, "K1AF", "EM28", "N2JFD", "FM09",
                -17, -18, "FT8", GeneralVariables.band, 14074000);
        GeneralVariables.qslRecordList.addQSLRecord(record);
        signal.setTransmit(new TransmitCallsign(1, 0, "N2JFD", 1500f, 1, -17), order, "");
    }

    /** A decode from the partner's (odd) slot at {@code utc}. */
    private static Ft8Message rxMsg(String to, String from, String extra, long utc, int snr) {
        Ft8Message msg = new Ft8Message(1, 0, to, from, extra);
        msg.utcTime = utc;
        msg.band = GeneralVariables.band;
        msg.snr = snr;
        return msg;
    }

    private Ft8Message callingMeNow(String from, int snr) {
        return rxMsg("K1AF", from, "EM12", rxSlotUtc, snr);
    }

    private Ft8Message calledMeOneCycleAgo(String from, int snr) {
        return rxMsg("K1AF", from, "EM12", rxSlotUtc - CYCLE_MS, snr);
    }

    private static ArrayList<Ft8Message> list(Ft8Message... msgs) {
        ArrayList<Ft8Message> out = new ArrayList<>();
        for (Ft8Message m : msgs) out.add(m);
        return out;
    }

    // ---- the queue tracks live callers on every pass --------------------------

    @Test
    public void callerDuringSmoothQso_isQueuedOnTheFastPass() {
        // The partner replied this cycle (so the old scan behind the no-reply
        // branch would never have run) and someone else is calling too. Both
        // must be recorded: the QSO advances AND the caller is queued.
        startQsoAtOrder(2);
        signal.parseMessageToFunction(list(
                rxMsg("K1AF", "N2JFD", "R-18", rxSlotUtc, -10),
                callingMeNow("KN6KI", -5)), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(4);
        assertThat(signal.queuedCallsigns()).containsExactly("KN6KI");
    }

    @Test
    public void partnerAnd73s_areNotQueued() {
        startQsoAtOrder(2);
        signal.parseMessageToFunction(list(
                rxMsg("K1AF", "N2JFD", "R-18", rxSlotUtc, -10),
                rxMsg("K1AF", "W9XYZ", "73", rxSlotUtc, -3)), false);
        assertThat(signal.queuedCallsigns()).isEmpty();
    }

    @Test
    public void callerCallingAgain_hasLastHeardRefreshed_notDropped() {
        startQsoAtOrder(2);
        signal.enqueueCaller(calledMeOneCycleAgo("KN6KI", -5));
        // Next cycle they call again: the refresh keeps them alive.
        signal.parseMessageToFunction(list(
                rxMsg("K1AF", "N2JFD", "R-18", rxSlotUtc, -10),
                callingMeNow("KN6KI", -5)), false);
        assertThat(signal.queuedCallsigns()).containsExactly("KN6KI");
    }

    @Test
    public void callerWhoStoppedCalling_isPrunedOnTheNextPass() {
        startQsoAtOrder(2);
        signal.enqueueCaller(calledMeOneCycleAgo("KN6KI", -5));
        assertThat(signal.queuedCallsigns()).containsExactly("KN6KI");
        // This cycle only the partner is heard: KN6KI has given up.
        signal.parseMessageToFunction(list(
                rxMsg("K1AF", "N2JFD", "R-18", rxSlotUtc, -10)), false);
        assertThat(signal.queuedCallsigns()).isEmpty();
    }

    // ---- QSO completion works only callers still calling ----------------------

    @Test
    public void completion_goesBackToCqWhenTheQueuedCallerGaveUp() {
        // THE complaint: we finish with N2JFD and would have called KN6KI, who
        // last called a full cycle ago and is gone. Back to CQ instead.
        startQsoAtOrder(5);
        signal.enqueueCaller(calledMeOneCycleAgo("KN6KI", -5));
        signal.parseMessageToFunction(list(
                rxMsg("K1AF", "N2JFD", "73", rxSlotUtc, -10)), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(6);
        assertThat(signal.queuedCallsigns()).isEmpty();
    }

    @Test
    public void completion_worksTheCallerStillCalling() {
        startQsoAtOrder(5);
        signal.enqueueCaller(calledMeOneCycleAgo("KN6KI", -5));// gave up
        signal.parseMessageToFunction(list(
                rxMsg("K1AF", "N2JFD", "73", rxSlotUtc, -10),
                callingMeNow("W9XYZ", -12)), false);// still calling
        assertThat(signal.getToCallsignString()).isEqualTo("W9XYZ");
        assertThat(signal.getFunctionOrder()).isEqualTo(2);// answering their grid with a report
        assertThat(signal.queuedCallsigns()).isEmpty();
    }

    @Test
    public void completion_strongestFirst_ignoresAStrongCallerWhoGaveUp() {
        GeneralVariables.pileupStrongestFirst = true;
        startQsoAtOrder(5);
        signal.enqueueCaller(calledMeOneCycleAgo("LOUD", +10));
        signal.parseMessageToFunction(list(
                rxMsg("K1AF", "N2JFD", "73", rxSlotUtc, -10),
                callingMeNow("QUIET", -15)), false);
        assertThat(signal.getToCallsignString()).isEqualTo("QUIET");
    }

    @Test
    public void cqState_doesNotCallAStaleQueuedCaller() {
        // Already idling on CQ with a leftover queued caller who stopped calling;
        // a cycle with nobody calling us must stay on CQ, not dequeue them.
        startQsoAtOrder(5);
        signal.parseMessageToFunction(list(
                rxMsg("K1AF", "N2JFD", "73", rxSlotUtc, -10)), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(6);
        signal.enqueueCaller(calledMeOneCycleAgo("KN6KI", -5));
        signal.parseMessageToFunction(list(
                rxMsg("CQ", "W1XYZ", "FN42", rxSlotUtc, -8)), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(6);
        assertThat(signal.queuedCallsigns()).isEmpty();
    }

    @Test
    public void userTappedCaller_isStillWorkedEvenIfStale() {
        // dequeueSpecificCaller is the operator's explicit choice; no pruning.
        startQsoAtOrder(2);
        signal.enqueueCaller(calledMeOneCycleAgo("KN6KI", -5));
        assertThat(signal.dequeueSpecificCaller("KN6KI")).isTrue();
        assertThat(signal.getToCallsignString()).isEqualTo("KN6KI");
    }
}
