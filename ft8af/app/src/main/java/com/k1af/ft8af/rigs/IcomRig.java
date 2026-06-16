package com.k1af.ft8af.rigs;

/**
 * IcomRig is a generic Icom rig control class. For WiFi mode, actual control is via IComWifiConnector (extends WifiConnector).
 * IComWifiConnector contains IComWifiRig for specific rig operations.
 */

import android.util.Log;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.ft8transmit.GenerateFT8;
import com.k1af.ft8af.icom.IComPacketTypes;
import com.k1af.ft8af.ui.ToastMessage;

import java.util.Timer;
import java.util.TimerTask;

public class IcomRig extends BaseRig {
    private static final String TAG = "IcomRig";

    private final int ctrAddress = 0xE0;//receive address, default 0xE0; rig reply can also be 0x00
    private byte[] dataBuffer = new byte[0];//data buffer
    private int alc = 0;
    private int swr = 0;
    private boolean alcMaxAlert = false;
    private boolean swrAlert = false;
    private Timer meterTimer;//Timer for querying meter

    private boolean oldVersion = false;//for older rigs that may not support SWR query
    //private boolean isPttOn = false;

    @Override
    public void setPTT(boolean on) {
        super.setPTT(on);
        //isPttOn = on;
        alcMaxAlert = false;
        swrAlert = false;
        if (on) {
            //fix connection mode: 0x03=WLAN, 0x01=USB, 0x02=USB+MIC, ensuring audio can be sent to rig
            if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
                sendCivData(IcomRigConstant.setConnectorDataMode(ctrAddress, getCivAddress(), (byte) 0x03));
            } else if (GeneralVariables.connectMode == ConnectMode.USB_CABLE) {
                sendCivData(IcomRigConstant.setConnectorDataMode(ctrAddress, getCivAddress(), (byte) 0x01));
            } else {
                sendCivData(IcomRigConstant.setConnectorDataMode(ctrAddress, getCivAddress(), (byte) 0x02));
            }
        }

        if (getConnector() != null) {
            if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
                getConnector().setPttOn(on);
                return;
            }

            switch (getControlMode()) {
                case ControlMode.CAT://via CIV command
                    getConnector().setPttOn(IcomRigConstant.setPTTState(ctrAddress, getCivAddress()
                            , on ? IcomRigConstant.PTT_ON : IcomRigConstant.PTT_OFF));
                    break;
                //case ControlMode.NETWORK:
                case ControlMode.RTS:
                case ControlMode.DTR:

                    getConnector().setPttOn(on);
                    break;
            }
        }
    }

    @Override
    public boolean isConnected() {
        if (getConnector() == null) {
            return false;
        }
        return getConnector().isConnected();
    }

    @Override
    public void setUsbModeToRig() {
        if (getConnector() != null) {
            //Since older ICOM rigs may not support USB-D, we first set USB mode, then switch to USB-D mode.
            // This way, if USB-D is not supported, the USB-D command is simply ignored and the rig stays in USB mode.
            //getConnector().sendData(IcomRigConstant.setOperationMode(ctrAddress
            // , getCivAddress(), IcomRigConstant.USB));//usb
            getConnector().sendData(IcomRigConstant.setOperationDataMode(ctrAddress
                    , getCivAddress(), IcomRigConstant.USB));//usb-d
        }
    }

    private void sendCivData(byte[] data) {
        if (getConnector() != null) {
            getConnector().sendData(data);
        }
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            getConnector().sendData(IcomRigConstant.setOperationFrequency(ctrAddress
                    , getCivAddress(), getFreq()));
        }
    }

    /**
     * Find the position of the command end marker. Returns -1 if not found.
     *
     * @param data data
     * @return position
     */
    private int getCommandEnd(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == (byte) 0xFD) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Find command header. Returns -1 if not found, otherwise returns position of first FE FE.
     *
     * @param data data
     * @return position
     */
    private int getCommandHead(byte[] data) {
        if (data.length < 2) return -1;
        for (int i = 0; i < data.length - 1; i++) {
            if (data[i] == (byte) 0xFE && data[i + 1] == (byte) 0xFE) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void sendWaveData(Ft8Message message) {//send audio data to rig, for network mode
        if (getConnector() != null) {//pass generated audio data to Connector
            float[] data = GenerateFT8.generateFt8(message, GeneralVariables.getBaseFrequency()
                    , 12000);//ICOM rig audio sample rate is 12000
            if (data == null) {
                setPTT(false);
                return;
            }
            getConnector().sendWaveData(data);
        }
    }

    private void analysisCommand(byte[] data) {
        int headIndex = getCommandHead(data);
        if (headIndex == -1) {//no command header found
            return;
        }
        IcomCommand icomCommand;
        if (headIndex == 0) {
            icomCommand = IcomCommand.getCommand(ctrAddress, getCivAddress(), data);
        } else {
            byte[] temp = new byte[data.length - headIndex];
            System.arraycopy(data, headIndex, temp, 0, temp.length);
            icomCommand = IcomCommand.getCommand(ctrAddress, getCivAddress(), temp);
        }
        if (icomCommand == null) {
            return;
        }

        //currently only responding to frequency and mode messages
        switch (icomCommand.getCommandID()) {

            case IcomRigConstant.CMD_SEND_FREQUENCY_DATA://received frequency data
            case IcomRigConstant.CMD_READ_OPERATING_FREQUENCY:
                //get frequency
                //ToastMessage.show(byteToStr(icomCommand.getData(false)));
                setFreq(icomCommand.getFrequency(false));
                break;
            case IcomRigConstant.CMD_SEND_MODE_DATA://received mode data
            case IcomRigConstant.CMD_READ_OPERATING_MODE:
                break;
            case IcomRigConstant.CMD_READ_METER://read meter//this command is only implemented in network mode; serial port support may be added later
                if (icomCommand.getSubCommand() == IcomRigConstant.CMD_READ_METER_ALC) {
                    alc = IcomRigConstant.twoByteBcdToInt(icomCommand.getData(true));
                }
                if (icomCommand.getSubCommand() == IcomRigConstant.CMD_READ_METER_SWR) {
                    swr = IcomRigConstant.twoByteBcdToInt(icomCommand.getData(true));
                }
                showAlert();//check if meter value is in alert range
                notifyMeterData(alc, swr);
                break;
            case IcomRigConstant.CMD_CONNECTORS:
                break;

        }
    }

    private void showAlert() {
        if ((swr >= IcomRigConstant.swr_alert_max) && GeneralVariables.swr_switch_on) {
            if (!swrAlert) {
                swrAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_high_alert));
            }
        } else {
            swrAlert = false;
        }
        if ((alc > IcomRigConstant.alc_alert_max) && GeneralVariables.alc_switch_on) {//ALC alert
            if (!alcMaxAlert) {
                alcMaxAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.alc_high_alert));
            }
        } else {
            alcMaxAlert = false;
        }

    }

    @Override
    public void onReceiveData(byte[] data) {
        //ToastMessage.show(byteToStr(data));

        int commandEnd = getCommandEnd(data);
        if (commandEnd <= -1) {//no command end marker
            byte[] temp = new byte[dataBuffer.length + data.length];
            System.arraycopy(dataBuffer, 0, temp, 0, dataBuffer.length);
            System.arraycopy(data, 0, temp, dataBuffer.length, data.length);
            dataBuffer = temp;
        } else {
            byte[] temp = new byte[dataBuffer.length + commandEnd + 1];
            System.arraycopy(dataBuffer, 0, temp, 0, dataBuffer.length);
            dataBuffer = temp;
            System.arraycopy(data, 0, dataBuffer, dataBuffer.length - commandEnd - 1, commandEnd + 1);
        }
        if (commandEnd != -1) {
            analysisCommand(dataBuffer);
        }
        dataBuffer = new byte[0];//clear buffer
        if (commandEnd <= -1 || commandEnd < data.length) {
            byte[] temp = new byte[data.length - commandEnd + 1];
            for (int i = 0; i < data.length - commandEnd - 1; i++) {
                temp[i] = data[commandEnd + i + 1];
            }
            dataBuffer = temp;
        }


    }

    @Override
    public void readFreqFromRig() {
        if (getConnector() != null) {
            getConnector().sendData(IcomRigConstant.setReadFreq(ctrAddress, getCivAddress()));
        }
    }

    @Override
    public String getName() {
        return "ICOM series";
    }

    public void startMeterTimer() {
        meterTimer = new Timer();
        meterTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (isPttOn() && !oldVersion) {//measure when PTT is pressed, and rig is not an old version
                    sendCivData(IcomRigConstant.getSWRState(ctrAddress, getCivAddress()));
                    sendCivData(IcomRigConstant.getALCState(ctrAddress, getCivAddress()));
                }
            }
        }, 0, IComPacketTypes.METER_TIMER_PERIOD_MS);
    }


    public String getFrequencyStr() {
        return BaseRigOperation.getFrequencyStr(getFreq());
    }


    public IcomRig(int civAddress, boolean newRig) {
        Log.d(TAG, "IcomRig: Create.");
        this.oldVersion = !newRig;//some older rigs do not support SWR query
        setCivAddress(civAddress);
        startMeterTimer();
    }
}
