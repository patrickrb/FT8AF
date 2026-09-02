package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.connector.BaseRigConnector;
import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.database.ControlMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The dial poll must never send a frequency read while the rig is keyed, and
 * "keyed" has to include the moments the PTT command itself is on its way out.
 * {@code IcomRig.setPTT} used to publish {@code isPttOn()} first and dispatch
 * second, so a tick landing between the flag going false and the PTT-off
 * command reaching the wire saw "not transmitting" and read the dial ahead of
 * the unkey (Copilot review on #789). These tests make the connector fire a
 * settled tick <em>during</em> the PTT dispatch — the Timer thread landing
 * mid-command, made deterministic — and check what went out, and in what order.
 *
 * <p>Robolectric because {@code setPTT} reads {@code GeneralVariables}.
 */
@RunWith(RobolectricTestRunner.class)
public class IcomRigPttPollOrderingTest {

    private static final int IC705_CIV = 0xA4;
    private static final long T0 = 1_700_000_000_000L;
    private static final byte[] READ_FREQ_FRAME = {
            (byte) 0xFE, (byte) 0xFE, (byte) 0xA4, (byte) 0xE0, (byte) 0x03, (byte) 0xFD
    };

    /** Records every send, and runs a settled poll tick inside each PTT dispatch. */
    private final class InterleavingConnector extends BaseRigConnector {
        final List<String> events = new ArrayList<>();
        long now = T0 + IcomRig.READ_FREQ_CONNECT_SETTLE_MS;

        InterleavingConnector() {
            super(ControlMode.CAT);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public synchronized void sendData(byte[] data) {
            events.add(Arrays.equals(data, READ_FREQ_FRAME) ? "READ_FREQ" : "civ");
        }

        @Override
        public void setPttOn(byte[] command) {
            events.add("PTT-command");
            tickDuringDispatch();
        }

        @Override
        public void setPttOn(boolean on) {
            events.add("PTT-command");
            tickDuringDispatch();
        }

        private void tickDuringDispatch() {
            now += 3_000;
            rig.runReadFreqTick(now, 0L);
        }
    }

    private InterleavingConnector connector;
    private IcomRig rig;
    private int savedConnectMode;

    @Before
    public void setUp() {
        savedConnectMode = GeneralVariables.connectMode;
        GeneralVariables.connectMode = ConnectMode.USB_CABLE;
        connector = new InterleavingConnector();
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
        rig.setControlMode(ControlMode.CAT);
        // Take and outlive the settle window so a tick is otherwise free to poll.
        rig.runReadFreqTick(T0, 0L);
        rig.runReadFreqTick(connector.now, 0L);
        assertThat(connector.events).containsExactly("READ_FREQ");
        connector.events.clear();
    }

    @After
    public void tearDown() {
        rig.onDisconnecting();
        GeneralVariables.connectMode = savedConnectMode;
    }

    @Test
    public void tickDuringKeyDown_doesNotReadTheDial() {
        rig.setPTT(true);

        assertThat(connector.events).contains("PTT-command");
        assertThat(connector.events).doesNotContain("READ_FREQ");
        assertThat(rig.isPttOn()).isTrue();
    }

    @Test
    public void tickDuringKeyUp_doesNotReadTheDialAheadOfTheUnkey() {
        rig.setPTT(true);
        connector.events.clear();

        rig.setPTT(false);

        // The tick that fired while the PTT-off command was being dispatched
        // must have stayed quiet: the flag is still "keyed" until the command
        // is on the wire.
        assertThat(connector.events).containsExactly("PTT-command");
        assertThat(rig.isPttOn()).isFalse();

        // Once the unkey is out, polling resumes.
        connector.now += 3_000;
        rig.runReadFreqTick(connector.now, 0L);
        assertThat(connector.events).containsExactly("PTT-command", "READ_FREQ").inOrder();
    }

    @Test
    public void flagIsPublishedEvenIfTheUnkeyDispatchThrows() {
        // The finally in setPTT: a transport failure on the way out must not
        // leave the rig marked keyed forever (which would silence polling).
        rig.setPTT(true);
        rig.setConnector(new BaseRigConnector(ControlMode.CAT) {
            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public void setPttOn(byte[] command) {
                throw new IllegalStateException("transport exploded");
            }
        });
        rig.setControlMode(ControlMode.CAT);
        try {
            rig.setPTT(false);
        } catch (IllegalStateException expected) {
            // propagated, as before
        }
        assertThat(rig.isPttOn()).isFalse();
    }
}
