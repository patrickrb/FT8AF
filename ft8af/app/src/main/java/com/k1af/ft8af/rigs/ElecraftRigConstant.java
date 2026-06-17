package com.k1af.ft8af.rigs;

import android.annotation.SuppressLint;

public class ElecraftRigConstant {
    private static final String TAG = "ElecraftRigConstant";
    //LSB:0,USB:1,AM:2,CW:3,RTTY:4,FM:5,WFM:6,CW_R:7,RTTY_R:8,DV:17
    public static final int LSB = 0x01;
    public static final int USB = 0x02;
    public static final int CW = 0x03;
    public static final int FM = 0x04;
    public static final int AM = 0x05;
    public static final int DATA = 0x06;
    public static final int CW_R = 0x07;
    public static final int DATA_R = 0x08;
    public static final int swr_alert_max=30;//equivalent to 3.0

    //PTT state

    //command set
    private static final String PTT_ON = "TX;";
    private static final String PTT_OFF = "RX;";
    private static final String USB_MODE = "MD2;";
    private static final String DATA_R_MODE = "MD6;";//TODO---need to add DATA_R mode command here
    private static final String READ_FREQ = "FA;";
    private static final String READ_SWR = "SWR;";







    public static String getModeStr(int mode) {
        switch (mode) {
            case LSB:
                return "LSB";
            case USB:
                return "USB";
            case CW:
                return "CW";
            case FM:
                return "FM";
            case AM:
                return "AM";
            case CW_R:
                return "CW_R";
            case DATA:
                return "DATA";
            case DATA_R:
                return "DATA_R";
            default:
                return "UNKNOWN";
        }
    }


    public static byte[] setPTTState(boolean on) {
        if (on) {
            return PTT_ON.getBytes();
        } else {
            return PTT_OFF.getBytes();
        }

    }

    public static byte[] setOperationUSBMode() {
        return USB_MODE.getBytes();
    }


    @SuppressLint("DefaultLocale")
    public static byte[] setOperationFreq11Byte(long freq) {//for KENWOOD TS590
        return String.format("FA%011d;",freq).getBytes();
    }



    public static byte[] setReadOperationFreq(){
        return READ_FREQ.getBytes();
    }

    public static byte[] setReadMetersSWR(){
        return READ_SWR.getBytes();
    }

}
