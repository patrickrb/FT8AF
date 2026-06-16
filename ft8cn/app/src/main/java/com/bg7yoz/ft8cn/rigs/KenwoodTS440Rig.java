package com.bg7yoz.ft8cn.rigs;

/**
 * KENWOOD TS-440S. The original Kenwood CAT protocol and the direct ancestor of the
 * TS-570/590 command set: 11-digit FAxxxxxxxxxxx; frequency, MD2; for USB, FR0; to
 * select VFO-A, and plain TX;/RX; for PTT. That PTT form matches KenwoodTS570Rig
 * (the TS-590 sends TX1;), so we reuse the TS-570 behaviour and only change the name.
 *
 * The TS-440 has no DATA mode; FT8 runs in USB. Outbound CAT reporting is limited on
 * this rig, but the app only needs to set frequency/mode/PTT — any missing readback
 * (FA;, RM;) is harmless. CAT runs at 4800 baud via the IF-232C interface.
 */
public class KenwoodTS440Rig extends KenwoodTS570Rig {
    @Override
    public String getName() {
        return "KENWOOD TS-440";
    }
}
