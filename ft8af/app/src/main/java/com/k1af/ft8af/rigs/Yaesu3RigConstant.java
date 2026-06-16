package com.k1af.ft8af.rigs;

import android.annotation.SuppressLint;

public class Yaesu3RigConstant {
    private static final String TAG = "Yaesu3RigConstant";
    //LSB:0,USB:1,AM:2,CW:3,RTTY:4,FM:5,WFM:6,CW_R:7,RTTY_R:8,DV:17
    public static final int LSB = 0x01;
    public static final int USB = 0x02;
    public static final int CW = 0x03;
    public static final int FM = 0x04;
    public static final int AM = 0x05;
    public static final int RTTY = 0x06;
    public static final int CW_R = 0x07;
    public static final int DATA = 0x08;
    public static final int RTTY_R = 0x09;
    public static final int NONE = 0x0A;
    public static final int FM_N = 0x0B;
    public static final int DATA_R = 0x0C;
    public static final int AM_N = 0x0D;


    public static final int swr_39_alert_max=125;//equivalent to 3.0
    public static final int alc_39_alert_max=125;//shows red on meter when exceeded
    //PTT state

    //command set
    // MX1/MX0 is the Monitor function on FT-891/991, not PTT — using it
    // enabled the rig's speaker monitor but never keyed the transmitter,
    // so audio was sent to the data jack and nobody on the band heard it.
    // TX1/TX0 is the actual PTT command (rear/data PTT = TX1).
    private static final String PTT_ON = "TX1;";
    private static final String PTT_OFF = "TX0;";
    private static final String USB_MODE = "MD02;";
    private static final String USB_MODE_DATA = "MD09;";
    private static final String DATA_U_MODE = "MD0C;";
    private static final String WIDTH_MAX_SSB = "NA00;SH0120;"; // Narrow OFF + Width 3000 Hz (SSB index 20)
    private static final String WIDTH_MAX_DATA = "NA00;SH0117;"; // Narrow OFF + Width 3000 Hz (DATA index 17)
    private static final String READ_FREQ = "FA;";
    private static final String READ_39METER_ALC = "RM4;";//commands for 38 and 39 are the same
    private static final String READ_39METER_SWR = "RM6;";//commands for 38 and 39 are the same

    private static final String TX_ON = "TX1;";//for FT450 PTT
    private static final String TX_OFF = "TX0;";//for FT450 PTT






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
            case RTTY:
                return "RTTY";
            case CW_R:
                return "CW_R";
            case DATA:
                return "DATA";
            case RTTY_R:
                return "RTTY_R";
            case NONE:
                return "NONE";
            case FM_N:
                return "FM_N";
            case DATA_R:
                return "DATA_R";
            case AM_N:
                return "AM_N";
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
    //transmit command for YAESU 450
    public static byte[] setPTT_TX_On(boolean on) {//for FT450
        if (on) {
            return TX_ON.getBytes();
        } else {
            return TX_OFF.getBytes();
        }

    }
    public static byte[] setOperationUSBMode() {
        return USB_MODE.getBytes();
    }
    public static byte[] setOperationUSB_Data_Mode() {
        return DATA_U_MODE.getBytes();
    }

    public static byte[] setOperationDATA_U_Mode() {
        return DATA_U_MODE.getBytes();
    }
    public static byte[] setMaxSsbWidth() {
        return WIDTH_MAX_SSB.getBytes();
    }
    public static byte[] setMaxDataWidth() {
        return WIDTH_MAX_DATA.getBytes();
    }

    @SuppressLint("DefaultLocale")
    public static byte[] setOperationFreq11Byte(long freq) {//for KENWOOD TS590
        return String.format("FA%011d;",freq).getBytes();
    }

    @SuppressLint("DefaultLocale")
    public static byte[] setOperationFreq9Byte(long freq) {
        return String.format("FA%09d;",freq).getBytes();
    }
    @SuppressLint("DefaultLocale")
    public static byte[] setOperationFreq8Byte(long freq) {
        return String.format("FA%08d;",freq).getBytes();
    }
    public static byte[] setReadOperationFreq(){
        return READ_FREQ.getBytes();
    }

    public static byte[] setRead39Meters_ALC(){
        return READ_39METER_ALC.getBytes();
    }
    public static byte[] setRead39Meters_SWR(){
        return READ_39METER_SWR.getBytes();
    }


}
