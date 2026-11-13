package com.k1af.ft8af.rigs;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.ft8transmit.MeterProtectionController;
import com.k1af.ft8af.ui.ToastMessage;

/**
 * Lab599 Discovery TX-500. CAT-identical to KENWOOD TS-2000, except it is placed in
 * DATA mode (MD6;) instead of USB (MD2;) so the rig takes audio from the USB line
 * input rather than the microphone. Issue #108.
 *
 * <p>Its SWR meter, however, does not follow the TS-2000/TS-590 RM layout, so meter
 * reading is handled here rather than inherited (issue #599):
 * <ul>
 *   <li>The rig must be switched to the SWR meter with {@code RM1;} before {@code RM;}
 *       returns a reading — the inherited path only ever sends {@code RM;}.</li>
 *   <li>The reply is {@code RM1vvvv}: the selector digit is at index 0 ({@code '1'} =
 *       SWR), not index 2, and the value is the four digits {@code substring(1,5)}
 *       (a raw 0000-0030 field), not the TS-590's layout.</li>
 *   <li>The 0-30 field maps to an SWR ratio via Lab599's published chart
 *       ({@link #tx500CatToSwrRatio(int)}); only that ratio, normalized the same way
 *       the SWR-halt threshold is stored, makes {@link MeterProtectionController} fire.</li>
 * </ul>
 */
public class DiscoveryTX500Rig extends KenwoodTS2000Rig {

    /**
     * Actual SWR the toast warning fires at (mirrors the TS-590's ~3.0:1 alert point).
     */
    private static final float TX500_SWR_ALERT_RATIO = 3.0f;

    private boolean swrAlert = false;

    @Override
    public void setUsbModeToRig() {
        if (getConnector() != null) {
            getConnector().sendData(KenwoodTK90RigConstant.setTS2000OperationDataMode());
        }
    }

    /**
     * The TX-500 needs the meter switched to SWR ({@code RM1;}) before {@code RM;}
     * returns an SWR reading; the inherited path only sends {@code RM;}.
     */
    @Override
    protected void sendMeterReadCommand() {
        getConnector().sendData(KenwoodTK90RigConstant.setTX500SelectSwrMeter());
        getConnector().sendData(KenwoodTK90RigConstant.setRead590Meters());
    }

    /**
     * Parse the TX-500's {@code RM1vvvv} SWR reply and feed the protection controller.
     * The TX-500 reports no ALC meter here, so ALC is passed as {@code -1} ("not
     * reported").
     */
    @Override
    protected void handleMeterReply(Yaesu3Command yaesu3Command) {
        if (!Yaesu3Command.isTX500MeterSWR(yaesu3Command)) {
            return; // not the SWR meter — ignore rather than mis-parse as TS-590
        }
        int normalizedSwr = tx500CatToNormalizedSwr(Yaesu3Command.getTX500MeterValue(yaesu3Command));
        showSwrAlert(normalizedSwr);
        notifyMeterData(-1, normalizedSwr);
    }

    private void showSwrAlert(int normalizedSwr) {
        boolean high = normalizedSwr >= MeterProtectionController.swrRatioToNormalized(TX500_SWR_ALERT_RATIO)
                && GeneralVariables.swr_switch_on;
        if (high) {
            if (!swrAlert) {
                swrAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_high_alert));
            }
        } else {
            swrAlert = false;
        }
    }

    /**
     * Map the TX-500's raw 0000-0030 SWR meter field to an actual SWR ratio.
     * Per Lab599's published value→SWR chart the relationship is linear:
     * {@code cat = 3 * (swr - 1)} (cat 6 → 3.0:1, cat 27 → 10:1), i.e.
     * {@code swr = 1 + cat / 3}.
     *
     * @param catValue raw 0-30 meter value
     * @return the corresponding SWR ratio (never below 1.0)
     */
    static float tx500CatToSwrRatio(int catValue) {
        if (catValue <= 0) return 1.0f;
        return 1.0f + catValue / 3.0f;
    }

    /**
     * Map the raw 0000-0030 field to a 0-255 SWR value on the same scale the SWR-halt
     * threshold is stored on ({@link MeterProtectionController#swrRatioToNormalized}),
     * so the configured halt ratio is meaningful for this rig.
     *
     * @param catValue raw 0-30 meter value
     * @return normalized 0-255 SWR value
     */
    static int tx500CatToNormalizedSwr(int catValue) {
        return MeterProtectionController.swrRatioToNormalized(tx500CatToSwrRatio(catValue));
    }

    @Override
    public String getName() {
        return "Discovery TX-500";
    }
}
