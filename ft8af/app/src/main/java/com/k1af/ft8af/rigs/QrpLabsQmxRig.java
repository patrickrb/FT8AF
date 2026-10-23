package com.k1af.ft8af.rigs;

/**
 * QRP Labs QMX driven through its Kenwood TS-480/590 CAT emulation (issue #945).
 *
 * <p>Frequency, PTT, VFO and meter commands are the TS-590 ones inherited from
 * {@link KenwoodTS590Rig} (the same subset the QDX entry uses). The one
 * difference is the mode: the TS-590 path sends {@code MD2;}, which on the QDX
 * is harmless (it is digital-only) but on SSB-capable QMX firmware selects USB
 * voice. QRP Labs' QMX CAT manual lists {@code MD6;} as the digital mode, so
 * that is what this rig sends instead.
 */
public class QrpLabsQmxRig extends KenwoodTS590Rig {

    @Override
    public void setUsbModeToRig() {
        if (getConnector() != null) {
            getConnector().sendData(KenwoodTK90RigConstant.setTS2000OperationDataMode());
        }
    }

    @Override
    public String getName() {
        return "QRP Labs QMX";
    }
}
