package com.k1af.ft8af.rigs;

import android.util.Log;

public class IcomRigConstant {
    private static final String TAG = "IcomRigConstant";
    //LSB:0,USB:1,AM:2,CW:3,RTTY:4,FM:5,WFM:6,CW_R:7,RTTY_R:8,DV:17
    public static final int LSB = 0;
    public static final int USB = 1;
    public static final int AM = 2;
    public static final int CW = 3;
    public static final int RTTY = 4;
    public static final int FM = 5;
    public static final int WFM = 6;
    public static final int CW_R = 7;
    public static final int RTTY_R = 8;
    public static final int DV = 0x17;
    public static final int UNKNOWN = -1;


    public static final int swr_alert_max=120;//equivalent to 3.0
    public static final int alc_alert_max=120;//shows red on meter when exceeded

    //XieGu 6100 ALC raw value (0-255): 127+/-50 is optimal linearity range, mapped to 0-120 linear range = 36.17 to 83.19
    public static final int xiegu_alc_alert_max=84;//shows red on meter when exceeded
    public static final int xiegu_alc_alert_min=36;//shows red on meter when below



    //PTT state
    public static final int PTT_ON = 1;
    public static final int PTT_OFF = 0;

    //command set
    public static final byte CMD_RESULT_OK = (byte) 0xfb;//
    public static final byte CMD_RESULT_FAILED = (byte) 0xfa;//

    public static final byte[] SEND_FREQUENCY_DATA = {0x00};//send frequency data
    public static final byte CMD_SEND_FREQUENCY_DATA = 0x00;//send frequency data

    public static final byte[] SEND_MODE_DATA = {0x01};//send mode data
    public static final byte CMD_SEND_MODE_DATA = 0x01;//send mode data

    public static final byte[] READ_BAND_EDGE_DATA = {0x02};//read frequency band edge
    public static final byte CMD_READ_BAND_EDGE_DATA = 0x02;//read frequency band edge

    public static final byte[] READ_OPERATING_FREQUENCY = {0x03};//read operating frequency
    public static final byte CMD_READ_OPERATING_FREQUENCY = 0x03;//read operating frequency

    public static final byte[] READ_OPERATING_MODE = {0x04};//read operating mode
    public static final byte CMD_READ_OPERATING_MODE = 0x04;//read operating mode

    public static final byte[] SET_OPERATING_FREQUENCY = {0x05};//set operating frequency
    public static final byte CMD_SET_OPERATING_FREQUENCY = 0x05;//set operating frequency

    public static final byte[] SET_OPERATING_MODE = {0x06};//set operating mode
    public static final byte CMD_SET_OPERATING_MODE = 0x06;//set operating mode

    public static final byte CMD_READ_METER = 0x15;//read meter
    public static final byte CMD_READ_METER_SWR = 0x12;//read meter sub-command, SWR meter
    public static final byte CMD_READ_METER_ALC = 0x13;//read meter sub-command, ALC meter
    public static final byte CMD_CONNECTORS = 0x1A;//Connector setting, read
    public static final byte CMD_CONNECTORS_DATA_MODE = 0x05;//Connector setting, read
    public static final int CMD_CONNECTORS_DATA_WLAN_LEVEL = 0x050117;//Connector setting, read





    public static final byte CMD_COMMENT_1A = 0x1A;//1A command
    public static final byte[] SET_READ_PTT_STATE = {0x1A, 0x00, 0x48};//read or set PTT state, not recommended

    public static final byte[] READ_TRANSCEIVER_STATE = {0x1A, 0x00, 0x48};//read rig transmit state
    public static final byte[] SET_TRANSCEIVER_STATE_ON = {0x1C, 0x00, 0x01};//set rig to transmit state TX
    public static final byte[] SET_TRANSCEIVER_STATE_OFF = {0x1C, 0x00, 0x00};//set rig to receive state RX
    public static final byte[] READ_TRANSMIT_FREQUENCY = {0x1C, 0x03};//read rig transmit frequency

    public static String getModeStr(int mode) {
        switch (mode) {
            case LSB:
                return "LSB";
            case USB:
                return "USB";
            case AM:
                return "AM";
            case CW:
                return "CW";
            case RTTY:
                return "RTTY";
            case FM:
                return "FM";
            case CW_R:
                return "CW_R";
            case RTTY_R:
                return "RTTY_R";
            case DV:
                return "DV";
            default:
                return "UNKNOWN";
        }
    }


    public static byte[] setPTTState(int ctrAddr, int rigAddr, int state) {
        //1C command, e.g. PTT ON:FE FE A1 E0 1C 00 01 FD
        byte[] data = new byte[8];
        data[0] = (byte) 0xfe;
        data[1] = (byte) 0xfe;
        data[2] = (byte) rigAddr;
        data[3] = (byte) ctrAddr;
        data[4] = (byte) 0x1c;//main command code
        data[5] = (byte) 0x00;//sub-command code
        data[6] = (byte) state;//state 01=tx 00=rx
        data[7] = (byte) 0xfd;
        return data;
    }

    /**
     * Start the internal antenna tuner (CI-V command 0x1C, sub 0x01,
     * value 0x02 = start tuning). The rig keys its own low-power carrier and
     * stops by itself; rigs without an ATU reply NG (0xFA) and do nothing.
     *
     * @param ctrAddr controller address
     * @param rigAddr rig address
     * @return command data packet
     */
    public static byte[] startAtuTune(int ctrAddr, int rigAddr) {
        byte[] data = new byte[8];
        data[0] = (byte) 0xfe;
        data[1] = (byte) 0xfe;
        data[2] = (byte) rigAddr;
        data[3] = (byte) ctrAddr;
        data[4] = (byte) 0x1c;//main command code
        data[5] = (byte) 0x01;//sub-command code: antenna tuner
        data[6] = (byte) 0x02;//02=start tuning (00=off, 01=on)
        data[7] = (byte) 0xfd;
        return data;
    }

    /**
     * Read SWR meter
     *
     * @param ctrAddr controller address
     * @param rigAddr rig address
     * @return command data packet
     */
    public static byte[] getSWRState(int ctrAddr, int rigAddr) {
        //1C command, e.g. PTT ON:FE FE A1 E0 15 12 FD
        byte[] data = new byte[7];
        data[0] = (byte) 0xfe;
        data[1] = (byte) 0xfe;
        data[2] = (byte) rigAddr;
        data[3] = (byte) ctrAddr;
        data[4] = (byte) CMD_READ_METER;//main command code
        data[5] = (byte) CMD_READ_METER_SWR;//sub-command code SWR
        data[6] = (byte) 0xfd;
        return data;
    }

    /**
     * Read ALC meter
     *
     * @param ctrAddr controller address
     * @param rigAddr rig address
     * @return command data packet
     */
    public static byte[] getALCState(int ctrAddr, int rigAddr) {
        //1C command, e.g. PTT ON:FE FE A1 E0 15 12 FD
        byte[] data = new byte[7];
        data[0] = (byte) 0xfe;
        data[1] = (byte) 0xfe;
        data[2] = (byte) rigAddr;
        data[3] = (byte) ctrAddr;
        data[4] = (byte) CMD_READ_METER;//main command code
        data[5] = (byte) CMD_READ_METER_ALC;//sub-command code ALC
        data[6] = (byte) 0xfd;
        return data;
    }

    public static byte[] getConnectorWLanLevel(int ctrAddr, int rigAddr){
        //1A command, e.g. DATA MODE=WLAN:FE FE A1 E0 1A 05 01 17 FD
        byte[] data = new byte[9];
        data[0] = (byte) 0xfe;
        data[1] = (byte) 0xfe;
        data[2] = (byte) rigAddr;
        data[3] = (byte) ctrAddr;
        data[4] = (byte) CMD_CONNECTORS;//main command code 1A
        data[5] = (byte) CMD_CONNECTORS_DATA_MODE;//WLan level
        data[6] = (byte) 0x01;
        data[7] = (byte) 0x17;
        data[8] = (byte) 0xfd;
        return data;
    }

    public static byte[] setConnectorWLanLevel(int ctrAddr, int rigAddr,int level){
        //1A command, e.g. DATA MODE=WLAN: FE FE A1 E0 1A 05 01 17 FD
        byte[] data = new byte[11];
        data[0] = (byte) 0xfe;
        data[1] = (byte) 0xfe;
        data[2] = (byte) rigAddr;
        data[3] = (byte) ctrAddr;
        data[4] = (byte) CMD_CONNECTORS;//main command code 1A
        data[5] = (byte) CMD_CONNECTORS_DATA_MODE;//sub-command code
        data[6] = (byte) 0x01;
        data[7] = (byte) 0x17;
        data[8] = (byte)  (level >> 8 & 0xff);
        data[9] = (byte) (level &0xff);
        data[10] = (byte) 0xfd;
        return data;
    }

    //set data communication mode
    public static byte[] setConnectorDataMode(int ctrAddr, int rigAddr,byte mode){
        //1A command, e.g. DATA MODE=WLAN: FE FE A1 E0 1A 05 01 19 FD
        byte[] data = new byte[10];
        data[0] = (byte) 0xfe;
        data[1] = (byte) 0xfe;
        data[2] = (byte) rigAddr;
        data[3] = (byte) ctrAddr;
        data[4] = (byte) CMD_CONNECTORS;//main command code 1A
        data[5] = (byte) CMD_CONNECTORS_DATA_MODE;//sub-command code
        data[6] = (byte) 0x01;//
        data[7] = (byte) 0x19;//
        data[8] = (byte) mode;//data connection mode
        data[9] = (byte) 0xfd;
        return data;
    }
    public static byte[] setOperationMode(int ctrAddr, int rigAddr, int mode) {
        //06 command, e.g. USB=01:FE FE A1 E0 06 01 FD
        byte[] data = new byte[8];
        data[0] = (byte) 0xfe;
        data[1] = (byte) 0xfe;
        data[2] = (byte) rigAddr;
        data[3] = (byte) ctrAddr;
        data[4] = (byte) 0x06;//command code
        data[5] = (byte) mode;//USB=01
        data[6] = (byte) 0x01;//fil1
        data[7] = (byte) 0xfd;
        return data;
    }

    public static byte[] setOperationDataMode(int ctrAddr, int rigAddr, int mode) {
        //26 command, e.g. USB-D=01:FE FE A1 E0 26 01 01 01 FD
        byte[] data = new byte[10];
        data[0] = (byte) 0xfe;
        data[1] = (byte) 0xfe;
        data[2] = (byte) rigAddr;//70
        data[3] = (byte) ctrAddr;//E0
        data[4] = (byte) 0x26;//command code
        data[5] = (byte) 0x00;//command code
        data[6] = (byte) mode;//USB=01
        data[7] = (byte) 0x01;//data mode
        data[8] = (byte) 0x01;//fil1
        data[9] = (byte) 0xfd;
        return data;
    }

    public static byte[] setReadFreq(int ctrAddr, int rigAddr) {
        //06 command, e.g. USB=01:FE FE A1 E0 06 01 FD
        byte[] data = new byte[6];
        data[0] = (byte) 0xfe;
        data[1] = (byte) 0xfe;
        data[2] = (byte) rigAddr;
        data[3] = (byte) ctrAddr;
        data[4] = (byte) 0x03;//command code
        data[5] = (byte) 0xfd;
        return data;
    }


    public static byte[] setOperationFrequency(int ctrAddr, int rigAddr, long freq) {
        //05 command, e.g. 14.074M:FE FE A4 E0 05 00 40 07 14 00 FD
        byte[] data = new byte[11];
        data[0] = (byte) 0xfe;
        data[1] = (byte) 0xfe;
        data[2] = (byte) rigAddr;
        data[3] = (byte) ctrAddr;
        data[4] = (byte) 0x05;//command code
        data[5] = (byte) (((byte) (freq % 100 / 10) << 4) + (byte) (freq % 10));
        data[6] = (byte) (((byte) (freq % 10000 / 1000) << 4) + (byte) (freq % 1000 / 100));
        data[7] = (byte) (((byte) (freq % 1000000 / 100000) << 4) + (byte) (freq % 100000 / 10000));
        data[8] = (byte) (((byte) (freq % 100000000 / 10000000) << 4) + (byte) (freq % 10000000 / 1000000));
        data[9] = (byte) (((byte) (freq / 1000000000) << 4) + (byte) (freq % 1000000000 / 100000000));
        data[10] = (byte) 0xfd;

        Log.d(TAG, "setOperationFrequency: " + BaseRig.byteToStr(data));
        return data;
    }

    public static int twoByteBcdToInt(byte[] data) {
        if (data == null || data.length < 2) return 0;
        return (int) (data[1] & 0x0f)//ones digit
                + ((int) (data[1] >> 4) & 0xf) * 10//tens digit
                + (int) (data[0] & 0x0f) * 100//hundreds
                + ((int) (data[0] >> 4) & 0xf) * 1000;//thousands

    }
    public static int twoByteBcdToIntBigEnd(byte[] data) {
        if (data == null || data.length < 2) return 0;
        return (int) (data[0] & 0x0f)//ones digit
                + ((int) (data[0] >> 4) & 0xf) * 10//tens digit
                + (int) (data[1] & 0x0f) * 100//hundreds
                + ((int) (data[1] >> 4) & 0xf) * 1000;//thousands

    }
}
