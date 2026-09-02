package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.connector.BaseRigConnector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers {@link IcomRig#runReadFreqTick()} — the tick body of the dedicated
 * frequency-poll timer added in the follow-up to issue #753.
 *
 * <p>After the CI-V address hex/decimal fix (PR #774) the app could COMMAND an
 * IC-705 correctly, but rig&rarr;app dial updates still didn't land: the user
 * turned the VFO on the rig and FT8AF stayed on the old frequency. Root cause
 * was that {@code IcomRig} had no CAT-side frequency poll of its own — every
 * other CAT rig class runs one (Yaesu38Rig, KenwoodTS590Rig, ElecraftRig, …) —
 * and relied entirely on {@link CatLiveness}'s 3 s liveness probe, which stops
 * hard on an 8 s quiet timeout and therefore can't be depended on to keep the
 * app's dial in sync.
 *
 * <p>These tests pin the tick's decision contract using a fake connector that
 * captures the CI-V bytes {@link IcomRig#readFreqFromRig()} sends, so the
 * regression can't come back silently:
 * <ul>
 *   <li>connected + PTT off &rarr; sends the 6-byte read-frequency frame,</li>
 *   <li>connected + PTT on  &rarr; sends nothing (meters are handled by the
 *       500 ms meter timer; polling frequency mid-TX would race it), and</li>
 *   <li>disconnected         &rarr; sends nothing (no-op even if the transport
 *       object still exists).</li>
 * </ul>
 *
 * <p>No Android types are touched, so no Robolectric runner is needed.
 */
public class IcomRigReadFreqPollTest {

    /** IC-705's factory address, matching the rest of the ICOM tests. */
    private static final int IC705_CIV = 0xA4;

    /** Arbitrary fixed "now" so the connect-settle gate needs no wall clock. */
    private static final long T0 = 1_700_000_000_000L;

    /**
     * A tick far enough past {@link #T0} that the connect-settle window has
     * elapsed, for tests whose subject is the PTT/connected decision rather
     * than the gate itself.
     */
    private static final long T_SETTLED = T0 + IcomRig.READ_FREQ_CONNECT_SETTLE_MS;

    /** CI-V read-frequency frame the app must send: FE FE A4 E0 03 FD. */
    private static final byte[] READ_FREQ_FRAME = {
            (byte) 0xFE, (byte) 0xFE, (byte) 0xA4, (byte) 0xE0,
            (byte) 0x03, (byte) 0xFD
    };

    private CapturingConnector connector;
    private IcomRig rig;

    @Before
    public void setUp() {
        connector = new CapturingConnector();
        // "newRig=true" avoids the oldVersion meter-suppression path, which is
        // irrelevant to frequency polling but keeps this rig behaving like a
        // modern IC-705 / IC-7300 / IC-9700.
        // A scheduler that never runs anything: these tests drive the tick by
        // hand, so no background thread can append to `sent` between a manual
        // runReadFreqTick and its assertion. IcomRigPollSchedulingTest covers
        // what the constructor schedules.
        rig = new IcomRig(IC705_CIV, true, new IcomRig.PollScheduler() {
            @Override
            public IcomRig.Cancellable scheduleFixedDelay(Runnable task, long delayMs, long periodMs) {
                return () -> { };
            }

            @Override
            public IcomRig.Cancellable scheduleFixedRate(Runnable task, long delayMs, long periodMs) {
                return () -> { };
            }
        });
        rig.setConnector(connector);
    }

    @After
    public void tearDown() {
        rig.onDisconnecting();
    }

    @Test
    public void connectedAndPttOff_sendsReadFreqFrame() {
        connector.connected = true;
        // PTT off: rig.isPttOn() is false out of the constructor.

        // First tick observes the link coming up and starts the settle window;
        // the second is past it, so the read goes out.
        rig.runReadFreqTick(T0, 0L);
        rig.runReadFreqTick(T_SETTLED, 0L);

        assertThat(connector.sent).hasSize(1);
        assertThat(connector.sent.get(0)).isEqualTo(READ_FREQ_FRAME);
    }

    @Test
    public void disconnected_sendsNothing() {
        // A dropped Wi-Fi link or an unplugged USB cable must not spam the
        // absent connector with reads it can't deliver anyway.
        connector.connected = false;

        rig.runReadFreqTick(T0, 0L);
        rig.runReadFreqTick(T_SETTLED, 0L);

        assertThat(connector.sent).isEmpty();
    }

    @Test
    public void firstTickAfterConnect_doesNotPollBeforeTheConnectHandshake() {
        // The regression this gate exists for: the Timer's start delay runs
        // from IcomRig construction, but the rig is built before the connector
        // logs in, so the first tick always lands before setOperationBand's
        // FA write (onConnected + 1500 ms + 800 ms). Reading there returns the
        // rig's power-on dial, which onFreqChanged adopts as commandedBandHz.
        connector.connected = true;

        rig.runReadFreqTick(T0, 0L);
        rig.runReadFreqTick(T0 + IcomRig.READ_FREQ_CONNECT_SETTLE_MS - 1, 0L);

        assertThat(connector.sent).isEmpty();
    }

    @Test
    public void connectPushDelivered_pollsWithoutWaitingOutTheSettleWindow() {
        // setOperationBand's FA write landed on this connection, so the rig is
        // already on the dial we asked for and there is nothing left to wait
        // for — no reason to sit out the rest of the window.
        connector.connected = true;

        rig.runReadFreqTick(T0, 0L);
        rig.runReadFreqTick(T0 + 100, T0 + 50);

        assertThat(countMatching(connector.sent, READ_FREQ_FRAME)).isEqualTo(1);
    }

    @Test
    public void reconnectBetweenTicks_earnsAFreshSettleWindow() {
        // CableConnector's auto-reconnect retries within 500 ms, so a drop and
        // a reopen can both happen between two 2 s ticks: this timer never
        // samples the link down. The connector's connection generation is what
        // tells the tick a new session started (Copilot review on #789).
        connector.connected = true;
        connector.getOnConnectorStateChanged().onConnected(); // session 1 up
        rig.runReadFreqTick(T0, 0L);
        rig.runReadFreqTick(T_SETTLED, 0L);
        assertThat(connector.sent).hasSize(1); // settled, polled once
        connector.sent.clear();

        // Drop and reopen with no tick in between: isConnected() reads true on
        // every sample this timer takes.
        connector.getOnConnectorStateChanged().onDisconnected();
        connector.getOnConnectorStateChanged().onConnected(); // session 2 up

        // First tick of session 2 lands inside its connect handshake: no poll.
        rig.runReadFreqTick(T_SETTLED + 2_000, 0L);
        assertThat(connector.sent).isEmpty();
        // ...and polls again once the fresh window has elapsed.
        rig.runReadFreqTick(T_SETTLED + 2_000 + IcomRig.READ_FREQ_CONNECT_SETTLE_MS, 0L);
        assertThat(connector.sent).hasSize(1);
    }

    @Test
    public void connectionFlagsAreVolatile() throws Exception {
        // The tick reads both fields on the Timer thread; the writers are the
        // connector I/O thread (connected) and the TX path (isPttOn). Without
        // volatile there is no happens-before edge and a stale read is legal.
        assertThat(java.lang.reflect.Modifier.isVolatile(
                BaseRigConnector.class.getDeclaredField("connected").getModifiers())).isTrue();
        assertThat(java.lang.reflect.Modifier.isVolatile(
                BaseRig.class.getDeclaredField("isPttOn").getModifiers())).isTrue();
        // The connector itself is set by MainViewModel after the constructor
        // has already started the polls, from another thread.
        assertThat(java.lang.reflect.Modifier.isVolatile(
                BaseRig.class.getDeclaredField("connector").getModifiers())).isTrue();
    }

    @Test
    public void wlanCivSendsAreSerialized() throws Exception {
        // IcomCivUdp builds a packet from civSeq, sends it, then increments the
        // sequence. sendTrackedPacket alone being synchronized let two senders
        // (this poll, the CAT-liveness poll, a PTT command) stamp the same
        // sequence number and have the rig drop one as a duplicate.
        for (String name : new String[] {"sendCivData", "sendOpenClose"}) {
            java.lang.reflect.Method m = name.equals("sendCivData")
                    ? com.k1af.ft8af.icom.IcomCivUdp.class.getDeclaredMethod(name, byte[].class)
                    : com.k1af.ft8af.icom.IcomCivUdp.class.getDeclaredMethod(name, boolean.class);
            assertThat(java.lang.reflect.Modifier.isSynchronized(m.getModifiers())).isTrue();
        }
    }

    @Test
    public void connectionGeneration_countsEveryLinkUp() {
        assertThat(connector.connectionGeneration()).isEqualTo(0);
        connector.getOnConnectorStateChanged().onConnected();
        connector.getOnConnectorStateChanged().onDisconnected();
        connector.getOnConnectorStateChanged().onConnected();
        assertThat(connector.connectionGeneration()).isEqualTo(2);
    }

    @Test
    public void reconnect_earnsAFreshSettleWindow() {
        // A drop resets connectedSinceMs, so the link coming back does not
        // inherit the previous session's elapsed window and poll immediately
        // into the new connect handshake.
        connector.connected = true;
        rig.runReadFreqTick(T0, 0L);
        connector.connected = false;
        rig.runReadFreqTick(T0 + 10_000, 0L);

        connector.connected = true;
        rig.runReadFreqTick(T0 + 20_000, 0L);

        assertThat(connector.sent).isEmpty();
    }

    @Test
    public void tickSurvivesAnUncheckedExceptionFromTheTransport() {
        // An unchecked exception out of a TimerTask kills the Timer thread, so
        // one bad tick would end dial polling for the whole session. The tick
        // has to swallow and log it instead.
        connector.connected = true;
        connector.throwOnSend = true;

        rig.runReadFreqTick(T0, 0L);
        rig.runReadFreqTick(T_SETTLED, 0L);

        // Still ticking afterwards: the next poll is attempted, not skipped.
        connector.throwOnSend = false;
        rig.runReadFreqTick(T_SETTLED + 2000, 0L);
        assertThat(countMatching(connector.sent, READ_FREQ_FRAME)).isEqualTo(1);
    }

    // -- mayPollDial (pure gate) ---------------------------------------------

    private static final long SETTLE = IcomRig.READ_FREQ_CONNECT_SETTLE_MS;

    @Test
    public void mayPollDial_linkDown_isFalse() {
        assertThat(IcomRig.mayPollDial(T0, 0L, 0L, T0)).isFalse();
    }

    @Test
    public void mayPollDial_deliveryIsSessionState_notAClockComparison() {
        // The stamp as it stood when the window was taken belongs to the last
        // session (or to nothing); only a stamp that CHANGED since means the
        // connect-time push landed on this connection.
        long stale = T0 - 5_000;
        assertThat(IcomRig.mayPollDial(T0 + 100, T0, stale, stale)).isFalse();
        assertThat(IcomRig.mayPollDial(T0 + 100, T0, stale, T0 + 50)).isTrue();
        // A wall-clock step backwards between connect and delivery makes the
        // new stamp read EARLIER than the old one; it still changed, so it
        // still counts — the old ">= connect time" test would have missed it.
        assertThat(IcomRig.mayPollDial(T0 + 100, T0, stale, stale - 60_000)).isTrue();
    }

    @Test
    public void mayPollDial_stampResetByANewSelection_isNotADelivery() {
        // GeneralVariables.operatorChoseDial() zeroes the delivered stamp when
        // the operator picks a new dial: the stamp changed, but a new FA write
        // is now PENDING, and polling would read the rig's pre-command dial.
        long previous = T0 - 5_000;
        assertThat(IcomRig.mayPollDial(T0 + 100, T0, previous, 0L)).isFalse();
        // The window still expires on its own...
        assertThat(IcomRig.mayPollDial(T0 + SETTLE, T0, previous, 0L)).isTrue();
        // ...and the new selection's own delivery still clears it early.
        assertThat(IcomRig.mayPollDial(T0 + 100, T0, previous, T0 + 90)).isTrue();
        // A window taken while the stamp was already 0 behaves the same.
        assertThat(IcomRig.mayPollDial(T0 + 100, T0, 0L, 0L)).isFalse();
        assertThat(IcomRig.mayPollDial(T0 + 100, T0, 0L, T0 + 90)).isTrue();
    }

    @Test
    public void newSelectionDuringTheSettleWindow_doesNotOpenTheGate() {
        // Through the tick: connect, then the operator chooses a dial before
        // the connect handshake has settled. The reset must not poll.
        connector.connected = true;
        long previousSession = T0 - 5_000;
        rig.runReadFreqTick(T0, previousSession);
        rig.runReadFreqTick(T0 + 100, 0L); // operatorChoseDial() zeroed it
        assertThat(connector.sent).isEmpty();
        rig.runReadFreqTick(T0 + 200, T0 + 150); // that selection's write landed
        assertThat(countMatching(connector.sent, READ_FREQ_FRAME)).isEqualTo(1);
    }

    @Test
    public void mayPollDial_settleWindowBoundaryIsInclusive() {
        assertThat(IcomRig.mayPollDial(T0 + SETTLE - 1, T0, 0L, 0L)).isFalse();
        assertThat(IcomRig.mayPollDial(T0 + SETTLE, T0, 0L, 0L)).isTrue();
    }

    @Test
    public void mayPollDial_settleWindowCoversTheWholeConnectHandshake() {
        // onConnected + 1500 ms (the posted setOperationBand) + 800 ms (its
        // delayed FA write): the window must outlast that even when the push
        // is never observed landing.
        assertThat(SETTLE).isAtLeast(2_300L);
        assertThat(IcomRig.mayPollDial(T0 + 2_300, T0, 0L, 0L)).isFalse();
    }

    @Test
    public void tickUsesTheSnapshotItTookWhenTheLinkCameUp() {
        // The gate sees the stamp as of the first "up" sample, so a stamp from
        // the previous session that is numerically later than this window's
        // start (clock corrected forwards in between) cannot short-circuit it.
        connector.connected = true;
        long previousSession = T0 + 999_999;
        rig.runReadFreqTick(T0, previousSession);
        rig.runReadFreqTick(T0 + 100, previousSession);
        assertThat(connector.sent).isEmpty();
        // ...until the stamp actually changes.
        rig.runReadFreqTick(T0 + 200, previousSession + 1);
        assertThat(countMatching(connector.sent, READ_FREQ_FRAME)).isEqualTo(1);
    }

    @Test
    public void connectedAndPttOn_sendsNothing() {
        // The 500 ms meter timer handles SWR/ALC reads during TX. This poll
        // deliberately skips: an unsolicited read-frequency mid-transmit can
        // arrive coalesced with a meter reply and confuse the parser, and the
        // rig can't turn its VFO while keyed anyway.
        connector.connected = true;
        rig.setPTT(true);

        rig.runReadFreqTick(T0, 0L);
        rig.runReadFreqTick(T_SETTLED, 0L);

        // setPTT(true) itself writes a data-mode command on the connector, so
        // filter to just the read-frequency frame we're guarding against.
        assertThat(countMatching(connector.sent, READ_FREQ_FRAME)).isEqualTo(0);
    }

    @Test
    public void directReadFreqFromRig_sendsFrameEvenWhenConnectorReportsDisconnected() {
        // readFreqFromRig() itself only null-checks the connector — it does NOT
        // gate on isConnected(). The tick above is what enforces the connected
        // guard, so a caller that reaches straight in (CatLiveness during a
        // brief link blip) still gets its write attempted. Pinning both halves
        // catches an accidental extra guard slipped into readFreqFromRig.
        connector.connected = false;

        rig.readFreqFromRig();

        assertThat(connector.sent).hasSize(1);
        assertThat(connector.sent.get(0)).isEqualTo(READ_FREQ_FRAME);
    }

    @Test
    public void onDisconnecting_isIdempotent() {
        // MainViewModel.connectRig calls baseRig.onDisconnecting() before
        // replacing the rig instance, and the test's own tearDown calls it
        // again — cancelling an already-cancelled Timer is fine, but the null-
        // out means a second call would NPE without the null-check the
        // implementation added. Belt-and-braces: verify we can call it twice.
        rig.onDisconnecting();
        rig.onDisconnecting();
    }

    /** Counts how many of {@code sent}'s payloads exactly equal {@code frame}. */
    private static int countMatching(List<byte[]> sent, byte[] frame) {
        int n = 0;
        for (byte[] b : sent) {
            if (b.length != frame.length) continue;
            boolean same = true;
            for (int i = 0; i < b.length; i++) {
                if (b[i] != frame[i]) { same = false; break; }
            }
            if (same) n++;
        }
        return n;
    }

    /**
     * Minimal {@link BaseRigConnector} that records every {@link #sendData}
     * payload for assertion and lets the test flip {@link #isConnected()}
     * without a real transport. No superclass wiring is used beyond what the
     * base constructor sets up.
     */
    private static final class CapturingConnector extends BaseRigConnector {
        final List<byte[]> sent = new ArrayList<>();
        boolean connected = false;
        /** Simulates a transport that throws an unchecked exception mid-write. */
        boolean throwOnSend = false;

        CapturingConnector() {
            super(0 /* controlMode — unused by this test */);
        }

        @Override
        public synchronized void sendData(byte[] data) {
            if (throwOnSend) {
                throw new IllegalStateException("transport exploded");
            }
            // Copy so a caller reusing its buffer can't retroactively change
            // what we recorded.
            byte[] copy = new byte[data.length];
            System.arraycopy(data, 0, copy, 0, data.length);
            sent.add(copy);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }
}
