package com.k1af.ft8af.database;

/**
 * Control mode
 * @author BGY70Z
 * @date 2023-03-20
 */
public class ControlMode {
    public static final int VOX=0;
    public static final int CAT=1;
    //public static final int NETWORK=2;
    public static final int BLUETOOTH=3;
    public static final int RTS=4;//CI-V commands are still effective
    public static final int DTR=5;//CI-V commands are still effective

    public static String getControlModeStr(int mode){
        switch (mode){
            case VOX:
                return "VOX";
            case CAT:
                return "CAT";
            //case NETWORK:
            //    return "WIFI";
            case BLUETOOTH:
                return "Bluetooth";
            case RTS:
                return "RTS";
            case DTR:
                return "DTR";
        }
        return "";
    }
}
