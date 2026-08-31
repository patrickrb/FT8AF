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
        rig = new IcomRig(IC705_CIV, true);
        rig.setConnector(connector);
    }

    @After
    public void tearDown() {
        // Cancel the two Timer threads the IcomRig constructor spins up so they
        // don't outlive the test — otherwise the JVM keeps running them until
        // Gradle's test worker shuts down, and their sendCivData spam pollutes
        // any concurrent test's captured bytes.
        rig.onDisconnecting();
    }

    @Test
    public void connectedAndPttOff_sendsReadFreqFrame() {
        connector.connected = true;
        // PTT off: rig.isPttOn() is false out of the constructor.

        rig.runReadFreqTick();

        assertThat(connector.sent).hasSize(1);
        assertThat(connector.sent.get(0)).isEqualTo(READ_FREQ_FRAME);
    }

    @Test
    public void disconnected_sendsNothing() {
        // A dropped Wi-Fi link or an unplugged USB cable must not spam the
        // absent connector with reads it can't deliver anyway.
        connector.connected = false;

        rig.runReadFreqTick();

        assertThat(connector.sent).isEmpty();
    }

    @Test
    public void connectedAndPttOn_sendsNothing() {
        // The 500 ms meter timer handles SWR/ALC reads during TX. This poll
        // deliberately skips: an unsolicited read-frequency mid-transmit can
        // arrive coalesced with a meter reply and confuse the parser, and the
        // rig can't turn its VFO while keyed anyway.
        connector.connected = true;
        rig.setPTT(true);

        rig.runReadFreqTick();

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

        CapturingConnector() {
            super(0 /* controlMode — unused by this test */);
        }

        @Override
        public synchronized void sendData(byte[] data) {
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
