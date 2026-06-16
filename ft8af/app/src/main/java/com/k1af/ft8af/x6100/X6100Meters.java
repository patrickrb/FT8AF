package com.k1af.ft8af.x6100;

import android.annotation.SuppressLint;
import android.util.Log;

import androidx.annotation.NonNull;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.flex.VITA;

public class X6100Meters {
    private final String TAG = "X6100Meters";
    public float sMeter;
    public float power;
    public float swr;
    public float alc;
    public float volt;
    public float max_power;
    public short tx_volume;
    public short af_level;//radio volume

    /*
    New version X6100 counting method
    S-Meter:
    0000=S0,0120=S9,0242=S9+60dB
    SWR-Meter:
    0000=1.0,0048=1.5,0080=2.0,0120=3.0
    Volt-Meter:
    0000=0V,0075=5V,0241=16V
     */

    public X6100Meters() {

    }

    public synchronized void update(byte[] meterData) {
        for (int i = 0; i < meterData.length / 4; i++) {
            short index = VITA.readShortDataBigEnd(meterData, i * 4);
            short value = VITA.readShortDataBigEnd(meterData, i * 4 + 2);
            setValues(index, value);
        }
    }

    /**
     * Convert S.Meter values from the X6100 to dBm values
     *
     * @param value the value
     * @return dBm value
     */
    public static float getMeter_dBm(float value) {
        if (value <= 0) {
            return -150f;
        } else if (value <= 120f) {
            return (value * 54f / 120f - 129f);
        } else if (value < 242) {
            return (value * 60f) / (242f - 120f) - 120f * 60f / (242f - 120f);

        } else {
            return 0;
        }
    }

    /**
     * Calculate the power supply voltage, 0~16V
     *
     * @param value the value
     * @return voltage
     */
    public static float getMeter_volt(float value) {
        if (value <= 75) {
            return value / 25f;
        } else {
            return (value - 75f) * 11 / 166f + 5;
        }
    }

    /**
     * Convert signal strength dBm value to meter S.Meter value
     *
     * @param fval dBm
     * @return meter value
     */
    private static float getMeters(float fval) {
        float val;
        if (fval < -123.0) {
            val = 0;
        } else if (fval <= -75.0f) { // S1~S9
            val = (129.0f + fval) * 120.0f / 54.0f;
        } else if (fval <= -15.0f) { // S9+10~60
            val = 120.0f + (75.0f + fval) * (242.0f - 120.0f) / 60.0f;
        } else {
            val = 242f;// max
        }
        return val;
    }

    /**
     * Algorithm for the X6100 to calculate 0~255 values corresponding to 1~infinity (25.5)
     *
     * @param fval raw SWR
     * @return converted value
     */
    private static float get6100SWR(float fval) {
        int val;
        if (fval <= 1.5f) {//SWR less than 1.5 (converted 0~48)
            val = Math.round((fval - 1.0f) * (48.0f / 0.5f));
        } else if (fval <= 2.0f) {//SWR between 1.5~2.0 (converted 49~80)
            val = Math.round((fval - 1.5f) * (80.0f - 48.0f) / 0.5f + 48.0f);
        } else if (fval <= 3.0) {//SWR between 2.0~3.0 (converted 81~120)
            val = Math.round((fval - 2.0f) * (120.0f - 80.0f) + 80.0f);
        } else {//SWR greater than 3.0~infinity (converted 121~255)
            val = Math.round((fval - 3.0f) * (255.0f - 120.0f) / (25.5f - 3.0f) + 120.0f);
        }
        //clamp value range
        if (val > 255) {
            val = 255;
        } else if (val < 0) {
            val = 0;
        }
        return val;
    }

    /**
     * Convert X6100 SWR (0~255) to actual SWR value
     * X6100 values are 0~255, divided into 4 segments corresponding to: 1~1.5, 1.5~2.0, 2.0~3.0, 3.0~infinity (actual value is 25.5)
     * 1~1.5 (0~48), 1.5~2.0 (48~80), 2.0~3.0 (80~120), 3.0~infinity (actual 25.5) (120~255)
     * @param fval the conversion value
     * @return the actual SWR value
     */
    private static float getSWR(float fval) {
        float val;
        if (fval <= 48.0f) {//1~1.5
            val = fval / 96.0f + 1;
        } else if (fval <= 80.0f) {//1.5~2.0
            val = (fval - 48.0f) / 64.0f + 1.5f;
        } else if (fval < 120.0f) {//2.0~3.0
            val = (fval - 80.0f) / 40.0f + 2.0f;
        } else {//3.0～∞(25.5)
            val = (fval - 120.0f) / 125.0f * (25.5f - 3.0f) + 3.0f;
        }
        return val;
    }


    private void setValues(short index, short value) {
        switch (index) {
            case 0://sMeter
                sMeter = value;
                break;
            case 1://power
                power = (25 / 255f) * value * 10;
                break;
            case 2://swr
                //X6100 values are 0~255, divided into 4 segments: 1~1.5, 1.5~2.0, 2.0~3.0, 3.0~infinity (actual value is 25.5)
                //1~1.5 (0~48), 1.5~2.0 (48~80), 2.0~3.0 (80~120), 3.0~infinity (120~255)
                swr = getSWR(value * 1.0f);
                break;
            case 3://ALC raw value is 0~255, sent value is the converted 0~120
                alc = 120-value * 1.0f;
                //Log.e(TAG,String.format("alc:%d",value));
                break;
            case 4:
                volt = getMeter_volt(value);
                break;
            case 5:
                max_power = value / 25.5f;
                //Log.e(TAG,String.format("max power:%d",value));
                break;
            case 6:
                tx_volume = value;
                break;
            case 7:
                af_level = value;
                break;
            default:
        }
    }

    @SuppressLint("DefaultLocale")
    @NonNull
    @Override
    public String toString() {
        //return String.format("S.Meter: %.1f dBm\nSWR: %s\nALC: %.1f\nVolt: %.1fV\nTX power: %.1f W\nMax tx power: %.1f\nTX volume:%d%%"
        return String.format(GeneralVariables.getStringFromResource(R.string.xiegu_meter_info)
                , getMeter_dBm(sMeter)
                , swr > 8 ? "∞" : String.format("%.1f", swr)
                , alc
                , volt
                , power
                , max_power
                , tx_volume
        );
        //"Signal strength: %.1f dBm\nSWR: %s\nALC: %.1f\nVoltage: %.1fV\nTX power: %.1f W\nMax TX power: %.1f\nTX volume:%d%%"
    }
}
