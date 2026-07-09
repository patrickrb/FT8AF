package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Behavior coverage for {@link SWLQsoList#findSwlQso}, the SWL QSO detector that
 * runs on the decode thread when {@code GeneralVariables.saveSWL_QSO} is enabled.
 *
 * <p>findSwlQso scans the "all messages" list with index-based backward loops
 * ({@code for i = size()-1 .. 0; get(i)}) inside checkPart1/checkPart2. In
 * MainViewModel the list handed in used to be the live, concurrently-mutated
 * {@code ft8Messages}; that let the scan hit IndexOutOfBounds when a concurrent
 * decode pass trimmed the list mid-loop. The fix copies the list under the
 * writers' lock before calling findSwlQso. These tests lock in that findSwlQso is
 * a pure function of the two lists it is given, so scanning a snapshot instead of
 * the live instance is behavior-preserving.
 *
 * <p>Plain JUnit is enough (mirrors {@link QSLRecordTest}): the only Android touch
 * is {@code android.util.Log}, which returns defaults under
 * {@code testOptions.unitTests.returnDefaultValues}.
 */
public class SWLQsoListTest {

    private String savedMyCallsign;

    @Before
    public void setUp() {
        savedMyCallsign = GeneralVariables.myCallsign;
        GeneralVariables.myCallsign = "K1ABC";
    }

    @After
    public void tearDown() {
        GeneralVariables.myCallsign = savedMyCallsign;
    }

    /** Collects every QSO the detector reports back. */
    private static final class CapturingCallback implements SWLQsoList.OnFoundSwlQso {
        final List<QSLRecord> found = new ArrayList<>();

        @Override
        public void doFound(QSLRecord record) {
            found.add(record);
        }
    }

    // callTo, callFrom, extraInfo (Ft8Message ctor order) — the sender is callFrom.
    private static Ft8Message msg(String to, String from, String extra) {
        return new Ft8Message(to, from, extra);
    }

    @Test
    public void completeQso_firesCallbackWithBothReports() {
        // Two stations exchange reports and one closes with RR73. The report
        // matching keys off checkIsMyCallsign, so K1ABC appears on the reporting
        // side of both exchanges.
        Ft8Message reportSent = msg("W1AW", "K1ABC", "R-05");  // K1ABC -> W1AW, R-05
        Ft8Message reportRcvd = msg("K1ABC", "W1AW", "-10");   // W1AW -> K1ABC, -10
        Ft8Message ending = msg("W1AW", "DL1XYZ", "RR73");     // closing marker

        ArrayList<Ft8Message> all = new ArrayList<>();
        all.add(reportSent);
        all.add(reportRcvd);
        all.add(ending);

        ArrayList<Ft8Message> fresh = new ArrayList<>();
        fresh.add(ending);

        CapturingCallback cb = new CapturingCallback();
        new SWLQsoList().findSwlQso(fresh, all, cb);

        assertThat(cb.found).hasSize(1);
        QSLRecord record = cb.found.get(0);
        assertThat(record.getToCallsign()).isEqualTo("W1AW");
        assertThat(record.getSendReport()).isEqualTo(-5);
        assertThat(record.getReceivedReport()).isEqualTo(-10);
    }

    @Test
    public void missingOneReport_noQso() {
        // Only one direction of the report exchange is present, so checkPart2
        // fails and no QSO is recorded.
        Ft8Message reportSent = msg("W1AW", "K1ABC", "R-05");
        Ft8Message ending = msg("W1AW", "DL1XYZ", "RR73");

        ArrayList<Ft8Message> all = new ArrayList<>();
        all.add(reportSent);
        all.add(ending);

        ArrayList<Ft8Message> fresh = new ArrayList<>();
        fresh.add(ending);

        CapturingCallback cb = new CapturingCallback();
        new SWLQsoList().findSwlQso(fresh, all, cb);

        assertThat(cb.found).isEmpty();
    }

    @Test
    public void endingInMyCall_isSkipped() {
        // A closing marker that involves my own callsign is not an SWL QSO and
        // must be skipped, even with both reports present.
        Ft8Message reportSent = msg("W1AW", "K1ABC", "R-05");
        Ft8Message reportRcvd = msg("K1ABC", "W1AW", "-10");
        Ft8Message ending = msg("K1ABC", "W1AW", "RR73");      // to == my callsign

        ArrayList<Ft8Message> all = new ArrayList<>();
        all.add(reportSent);
        all.add(reportRcvd);
        all.add(ending);

        ArrayList<Ft8Message> fresh = new ArrayList<>();
        fresh.add(ending);

        CapturingCallback cb = new CapturingCallback();
        new SWLQsoList().findSwlQso(fresh, all, cb);

        assertThat(cb.found).isEmpty();
    }

    @Test
    public void scanningACopyMatchesScanningTheLiveList() {
        // The MainViewModel fix scans a defensive copy of ft8Messages instead of
        // the live instance. findSwlQso depends only on list *contents*, so a copy
        // yields an identical result — this guards that equivalence.
        Ft8Message reportSent = msg("W1AW", "K1ABC", "R-05");
        Ft8Message reportRcvd = msg("K1ABC", "W1AW", "-10");
        Ft8Message ending = msg("W1AW", "DL1XYZ", "RR73");

        ArrayList<Ft8Message> live = new ArrayList<>();
        live.add(reportSent);
        live.add(reportRcvd);
        live.add(ending);

        ArrayList<Ft8Message> fresh = new ArrayList<>();
        fresh.add(ending);

        CapturingCallback liveCb = new CapturingCallback();
        new SWLQsoList().findSwlQso(fresh, live, liveCb);

        CapturingCallback copyCb = new CapturingCallback();
        new SWLQsoList().findSwlQso(fresh, new ArrayList<>(live), copyCb);

        assertThat(copyCb.found).hasSize(liveCb.found.size());
        assertThat(copyCb.found.get(0).getSendReport())
                .isEqualTo(liveCb.found.get(0).getSendReport());
        assertThat(copyCb.found.get(0).getReceivedReport())
                .isEqualTo(liveCb.found.get(0).getReceivedReport());
    }
}
