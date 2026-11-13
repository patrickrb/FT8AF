package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.connector.BaseRigConnector;
import com.k1af.ft8af.database.ControlMode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public class XieGuG106RigTest {
    private static final int G106_CIV = 0x70;

    /** Records every CAT PTT frame; can park one keepalive call before it writes. */
    private static final class RecordingConnector extends BaseRigConnector {
        final List<String> frames = Collections.synchronizedList(new ArrayList<>());
        volatile CountDownLatch parkNextOn;
        final CountDownLatch parked = new CountDownLatch(1);

        RecordingConnector() {
            super(ControlMode.CAT);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void setPttOn(byte[] command) {
            boolean on = command[command.length - 2] == IcomRigConstant.PTT_ON;
            CountDownLatch park = parkNextOn;
            if (on && park != null) {
                parkNextOn = null;
                parked.countDown();
                try {
                    park.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            frames.add(on ? "ON" : "OFF");
        }
    }

    private XieGuG106Rig rig;
    private RecordingConnector connector;
    private int pttChangedCount;

    @Before
    public void setUp() {
        rig = new XieGuG106Rig(G106_CIV);
        // Stop the real poll Timer so only the test drives the keepalive.
        rig.onDisconnecting();
        connector = new RecordingConnector();
        rig.setConnector(connector);
        rig.setControlMode(ControlMode.CAT);
        rig.setOnRigStateChanged(new OnRigStateChanged() {
            @Override public void onDisconnected() { }
            @Override public void onConnected() { }
            @Override public void onPttChanged(boolean isOn) { pttChangedCount++; }
            @Override public void onFreqChanged(long freq) { }
            @Override public void onRunError(String message) { }
        });
    }

    @Test
    public void catPttOnNeedsKeepAlive() {
        assertThat(XieGuG106Rig.needsCatPttKeepAlive(true, ControlMode.CAT)).isTrue();
    }

    @Test
    public void receiveAndHardwarePttDoNotNeedKeepAlive() {
        assertThat(XieGuG106Rig.needsCatPttKeepAlive(false, ControlMode.CAT)).isFalse();
        assertThat(XieGuG106Rig.needsCatPttKeepAlive(true, ControlMode.RTS)).isFalse();
        assertThat(XieGuG106Rig.needsCatPttKeepAlive(true, ControlMode.DTR)).isFalse();
    }

    @Test
    public void keepAliveRunsSafelyBeforeTwoSecondRadioTimeout() {
        assertThat(XieGuG106Rig.PTT_KEEPALIVE_MS).isLessThan(2_000L);
    }

    @Test
    public void keepAliveResendsFrameWithoutTouchingPttState() {
        rig.setPTT(true);
        rig.onPollingWhileTransmitting();
        rig.onPollingWhileTransmitting();

        assertThat(connector.frames).containsExactly("ON", "ON", "ON").inOrder();
        assertThat(rig.isPttOn()).isTrue();
        // Only the real key-up notified listeners; the refreshes are wire-only.
        assertThat(pttChangedCount).isEqualTo(1);
    }

    @Test
    public void keepAliveAfterUnkeyNeverReKeys() {
        rig.setPTT(true);
        rig.setPTT(false);
        rig.onPollingWhileTransmitting();

        assertThat(connector.frames).containsExactly("ON", "OFF").inOrder();
        assertThat(rig.isPttOn()).isFalse();
    }

    /**
     * The keepalive (poll Timer thread) has already seen "PTT on" when the TX
     * thread unkeys. Its PTT-on frame must not reach the wire after PTT-off,
     * or the radio is keyed again after the over ended.
     */
    @Test
    public void keepAliveRacingUnkeyCannotLeaveRadioKeyed() throws Exception {
        rig.setPTT(true);
        CountDownLatch release = new CountDownLatch(1);
        connector.parkNextOn = release;

        Thread keepAlive = new Thread(rig::onPollingWhileTransmitting);
        keepAlive.start();
        assertThat(connector.parked.await(5, TimeUnit.SECONDS)).isTrue();

        Thread unkey = new Thread(() -> rig.setPTT(false));
        unkey.start();
        // Give the unkey every chance to overtake the parked keepalive.
        unkey.join(300);
        release.countDown();
        keepAlive.join(5_000);
        unkey.join(5_000);

        assertThat(connector.frames.get(connector.frames.size() - 1)).isEqualTo("OFF");
        assertThat(rig.isPttOn()).isFalse();
    }

    @Test
    public void hardwarePttGetsNoCatKeepAlive() {
        rig.setControlMode(ControlMode.RTS);
        rig.setPTT(true);
        rig.onPollingWhileTransmitting();

        assertThat(connector.frames).isEmpty();
    }

    /** A duplicate id makes MainViewModel's rig switch fail to compile or pick the wrong driver. */
    @Test
    public void instructionSetIdsAreUnique() throws IllegalAccessException {
        Map<Integer, String> seen = new HashMap<>();
        for (Field f : InstructionSet.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (!Modifier.isStatic(mod) || !Modifier.isFinal(mod) || f.getType() != int.class) {
                continue;
            }
            int id = f.getInt(null);
            String previous = seen.put(id, f.getName());
            assertThat(previous == null ? null : previous + " and " + f.getName() + " share " + id)
                    .isNull();
        }
        assertThat(seen.get(InstructionSet.XIEGU_G106)).isEqualTo("XIEGU_G106");
    }
}
