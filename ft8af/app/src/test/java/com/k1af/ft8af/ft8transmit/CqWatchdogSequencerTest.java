package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

/**
 * Sequencer-level coverage for the CQ Watchdog
 * ({@code GeneralVariables.cqNoReplyLimit}/{@code cqNoReplyCount}) inside
 * {@link FT8TransmitSignal#parseMessageToFunction(ArrayList, boolean)}: a
 * bare, unanswered Call CQ (functionOrder == 6, no target picked) must give
 * itself up entirely after {@code cqNoReplyLimit} silent cycles. Confirmed
 * before this change that this never happened -- {@link
 * FT8TransmitSignal#shouldGiveUpTarget}/{@code noReplyLimit} only ever apply
 * once a target is picked (mid-QSO silence); functionOrder == 6 returned
 * early with no give-up concept of any kind.
 *
 * <p>Same Robolectric harness as {@link Max73SendsSequencerTest}: the
 * sequencer posts LiveData and logs through android.util.Log.
 */
@RunWith(RobolectricTestRunner.class)
public class CqWatchdogSequencerTest {

    /** Slot-1 (odd) utcTime for FT8: (15000+750)/1000/15 == 1. */
    private static final long RX_SLOT_UTC = 15_000L;

    private FT8TransmitSignal signal;

    @Before
    public void setUp() {
        GeneralVariables.myCallsign = "K1AF";
        GeneralVariables.noReplyCount = 0;
        GeneralVariables.noReplyLimit = 0;
        GeneralVariables.cqNoReplyCount = 0;
        GeneralVariables.cqNoReplyLimit = 0;
        GeneralVariables.max73Sends = 0;
        GeneralVariables.houndMode = false;
        GeneralVariables.autoFollowCQ = false;
        GeneralVariables.autoCallFollow = false;
        GeneralVariables.autoCQAfterQSO = false;
        GeneralVariables.synFrequency = false;
        GeneralVariables.qslRecordList.clear();
        GeneralVariables.transmitMessages.clear();
        signal = new FT8TransmitSignal(null, null, null);
        signal.setActivated(true);
    }

    @After
    public void tearDown() {
        GeneralVariables.myCallsign = "";
        GeneralVariables.noReplyCount = 0;
        GeneralVariables.cqNoReplyCount = 0;
        GeneralVariables.cqNoReplyLimit = 0;
        GeneralVariables.qslRecordList.clear();
    }

    /** A decoded message between two OTHER stations -- nothing addressed to us. */
    private static Ft8Message unrelatedMsg() {
        Ft8Message msg = new Ft8Message(1, 0, "W1AW", "VE3ABC", "-10");
        msg.utcTime = RX_SLOT_UTC;
        msg.band = GeneralVariables.band;
        msg.snr = -10;
        return msg;
    }

    /** A fresh CQ-answer addressed to us. */
    private static Ft8Message answerMsg() {
        Ft8Message msg = new Ft8Message(1, 0, "K1AF", "N2JFD", "FN20");
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

    @Test
    public void freshSignal_startsInCqState() {
        assertThat(signal.getFunctionOrder()).isEqualTo(6);
    }

    @Test
    public void belowLimit_keepsCallingCq() {
        GeneralVariables.cqNoReplyLimit = 3;
        signal.parseMessageToFunction(list(unrelatedMsg()), false);
        assertThat(signal.getFunctionOrder()).isEqualTo(6);
        assertThat(signal.isActivated()).isTrue();
    }

    @Test
    public void atLimit_stopsCallingCqEntirely() {
        GeneralVariables.cqNoReplyLimit = 2;
        signal.parseMessageToFunction(list(unrelatedMsg()), false);
        assertThat(signal.isActivated()).isTrue(); // 1st silent cycle, not yet
        signal.parseMessageToFunction(list(unrelatedMsg()), false);
        assertThat(signal.isActivated()).isFalse(); // 2nd hits the limit
    }

    @Test
    public void unlimitedMode_callsCqForever() {
        GeneralVariables.cqNoReplyLimit = 0; // the "robot operation" default
        for (int i = 0; i < 50; i++) {
            signal.parseMessageToFunction(list(unrelatedMsg()), false);
        }
        assertThat(signal.isActivated()).isTrue();
        assertThat(signal.getFunctionOrder()).isEqualTo(6);
    }

    @Test
    public void cqWatchdog_isIndependentOfMidQsoTxWatchdog() {
        // A tight mid-QSO retry tolerance (noReplyLimit) must not leak into
        // the idle-CQ state -- these are deliberately separate counters.
        GeneralVariables.noReplyLimit = 1;
        GeneralVariables.cqNoReplyLimit = 0;
        for (int i = 0; i < 10; i++) {
            signal.parseMessageToFunction(list(unrelatedMsg()), false);
        }
        assertThat(signal.isActivated()).isTrue();
        assertThat(signal.getFunctionOrder()).isEqualTo(6);
    }

    @Test
    public void aRealCallerPickedUp_resetsTheCqWatchdogCounter() {
        GeneralVariables.cqNoReplyLimit = 2;
        signal.parseMessageToFunction(list(unrelatedMsg()), false); // 1 silent cycle
        assertThat(GeneralVariables.cqNoReplyCount).isEqualTo(1);

        // A real CQ-answer addressed to us advances us out of CQ state.
        signal.parseMessageToFunction(list(answerMsg()), false);
        assertThat(signal.getFunctionOrder()).isNotEqualTo(6);
        assertThat(GeneralVariables.cqNoReplyCount).isEqualTo(0);
    }

    @Test
    public void freshCqRun_startsTheCounterOver() {
        GeneralVariables.cqNoReplyLimit = 2;
        signal.parseMessageToFunction(list(unrelatedMsg()), false);
        assertThat(GeneralVariables.cqNoReplyCount).isEqualTo(1);
        signal.resetToCQ();
        assertThat(GeneralVariables.cqNoReplyCount).isEqualTo(0);
    }
}
