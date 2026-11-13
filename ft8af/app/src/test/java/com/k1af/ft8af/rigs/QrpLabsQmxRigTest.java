package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.connector.BaseRigConnector;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The QMX entry (issue #945) reuses the TS-590 CAT path but must put the radio in
 * its digital mode ({@code MD6;}): the TS-590's {@code MD2;} selects USB voice on
 * SSB-capable QMX firmware. Robolectric is needed because the TS-590 constructor
 * posts its VFO command to the main Looper.
 */
@RunWith(RobolectricTestRunner.class)
public class QrpLabsQmxRigTest {

    @Test
    public void setUsbModeToRig_sendsDigitalModeNotUsbVoice() {
        CapturingConnector connector = new CapturingConnector();
        QrpLabsQmxRig rig = new QrpLabsQmxRig();
        try {
            rig.setConnector(connector);
            rig.setUsbModeToRig();
            assertThat(connector.sentStrings()).containsExactly("MD6;");
        } finally {
            rig.onDisconnecting();
        }
    }

    @Test
    public void ts590Rig_stillSendsUsbMode() {
        // The QDX / TS-480 / TS-590 entries keep MD2; — only the QMX changes.
        CapturingConnector connector = new CapturingConnector();
        KenwoodTS590Rig rig = new KenwoodTS590Rig();
        try {
            rig.setConnector(connector);
            rig.setUsbModeToRig();
            assertThat(connector.sentStrings()).containsExactly("MD2;");
        } finally {
            rig.onDisconnecting();
        }
    }

    @Test
    public void setFreqToRig_usesTs590Framing() {
        CapturingConnector connector = new CapturingConnector();
        QrpLabsQmxRig rig = new QrpLabsQmxRig();
        try {
            rig.setConnector(connector);
            rig.setFreq(14_074_000L);
            connector.sent.clear();
            rig.setFreqToRig();
            assertThat(connector.sentStrings()).containsExactly("FA00014074000;");
        } finally {
            rig.onDisconnecting();
        }
    }

    @Test
    public void setUsbModeToRig_withoutConnector_isANoOp() {
        QrpLabsQmxRig rig = new QrpLabsQmxRig();
        try {
            rig.setUsbModeToRig(); // must not throw
        } finally {
            rig.onDisconnecting();
        }
    }

    @Test
    public void getName_identifiesTheQmx() {
        QrpLabsQmxRig rig = new QrpLabsQmxRig();
        try {
            assertThat(rig.getName()).isEqualTo("QRP Labs QMX");
        } finally {
            rig.onDisconnecting();
        }
    }

    private static final class CapturingConnector extends BaseRigConnector {
        final List<byte[]> sent = new ArrayList<>();

        CapturingConnector() {
            super(0 /* controlMode — unused by this test */);
        }

        @Override
        public synchronized void sendData(byte[] data) {
            sent.add(data.clone());
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        synchronized List<String> sentStrings() {
            List<String> out = new ArrayList<>();
            for (byte[] b : sent) {
                out.add(new String(b, StandardCharsets.US_ASCII));
            }
            return out;
        }
    }
}
