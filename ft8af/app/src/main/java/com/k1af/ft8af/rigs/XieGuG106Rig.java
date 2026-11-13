package com.k1af.ft8af.rigs;

import com.k1af.ft8af.connector.BaseRigConnector;
import com.k1af.ft8af.database.ControlMode;

/**
 * Xiegu G106/G106C CI-V driver.
 *
 * <p>The G106 releases CAT-controlled PTT after roughly two seconds unless the
 * command is refreshed. FT8 transmission lasts 12.64 seconds, so repeat the
 * PTT-on frame once per second while transmitting. RTS and DTR keying do not
 * need this workaround.</p>
 *
 * <p>The keepalive runs on the poll Timer thread while {@link #setPTT} runs on
 * the TX thread. Both take {@link #pttLock}, so a keepalive that has already
 * seen "PTT on" cannot put its PTT-on frame on the wire after the PTT-off
 * frame (which would re-key the radio after the over ended). The keepalive
 * only resends the frame; it never writes the PTT state or fires PTT
 * listeners.</p>
 */
public class XieGuG106Rig extends XieGuRig {
    static final long PTT_KEEPALIVE_MS = 1_000L;

    private final Object pttLock = new Object();

    public XieGuG106Rig(int civAddress) {
        super(civAddress, PTT_KEEPALIVE_MS);
    }

    static boolean needsCatPttKeepAlive(boolean pttOn, int controlMode) {
        return pttOn && controlMode == ControlMode.CAT;
    }

    @Override
    public void setPTT(boolean on) {
        synchronized (pttLock) {
            super.setPTT(on);
        }
    }

    @Override
    protected void onPollingWhileTransmitting() {
        synchronized (pttLock) {
            BaseRigConnector connector = getConnector();
            if (connector != null && needsCatPttKeepAlive(isPttOn(), getControlMode())) {
                // Runs on XieGuRig's existing polling timer, keeping all serial
                // commands on the same ordered path as its meter/frequency polls.
                connector.setPttOn(catPttFrame(true));
            }
        }
    }

    @Override
    public String getName() {
        return "XIEGU G106 series";
    }
}
