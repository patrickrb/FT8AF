package com.bg7yoz.ft8cn.rigs;

/**
 * Lab599 Discovery TX-500. CAT-identical to KENWOOD TS-2000, except it is placed in
 * DATA mode (MD6;) instead of USB (MD2;) so the rig takes audio from the USB line
 * input rather than the microphone. Issue #108.
 */
public class DiscoveryTX500Rig extends KenwoodTS2000Rig {
    @Override
    public void setUsbModeToRig() {
        if (getConnector() != null) {
            getConnector().sendData(KenwoodTK90RigConstant.setTS2000OperationDataMode());
        }
    }

    @Override
    public String getName() {
        return "Discovery TX-500";
    }
}
