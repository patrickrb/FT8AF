package com.k1af.ft8af.rigs;

import static com.k1af.ft8af.GeneralVariables.QUERY_FREQ_TIMEOUT;
import static com.k1af.ft8af.GeneralVariables.START_QUERY_FREQ_DELAY;

import android.util.Log;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.ft8transmit.GenerateFT8;
import com.k1af.ft8af.ui.ToastMessage;

import java.util.Timer;
import java.util.TimerTask;

public class XieGuRig extends BaseRig {
    private static final String TAG = "XieGu6100Rig";

    private final int ctrAddress = 0xE0;//receive address, default 0xE0; rig reply can also be 0x00
    private byte[] dataBuffer = new byte[0];//data buffer
    private int swr = 0;
    private int alc = 0;
    private boolean alcMaxAlert = false;
    private boolean alcMinAlert = false;
    private boolean swrAlert = false;
    private Timer readFreqTimer = new Timer();


    @Override
    public void onDisconnecting() {
        if (readFreqTimer != null) {
            readFreqTimer.cancel();
            readFreqTimer.purge();
            readFreqTimer = null;
        }
    }
    private TimerTask readTask() {
        return new TimerTask() {
            @Override
            public void run() {
                try {
                    if (!isConnected()) {
                        return; // skip this tick; timer stays alive for reconnect
                    }
                    if (isPttOn()) {
                        readSWRMeter();
                    } else {
                        readFreqFromRig();
                    }

                } catch (Exception e) {
                    Log.e(TAG, "readFreq or meter error:" + e.getMessage());
                }
            }
        };
    }


    @Override
    public void setPTT(boolean on) {
        super.setPTT(on);

        if (getConnector() != null) {
            switch (getControlMode()) {
                case ControlMode.CAT://via CAT command
                    getConnector().setPttOn(IcomRigConstant.setPTTState(ctrAddress, getCivAddress()
                            , on ? IcomRigConstant.PTT_ON : IcomRigConstant.PTT_OFF));
                    break;
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
            getConnector().sendData(IcomRigConstant.setOperationMode(ctrAddress
                    , getCivAddress(), 1));//usb=1
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
                long freqTemp = icomCommand.getFrequency(false);
                if (freqTemp >= 500000 && freqTemp <= 250000000) {//XieGu frequency range
                    setFreq(freqTemp);
                }
                break;
            case IcomRigConstant.CMD_SEND_MODE_DATA://received mode data
            case IcomRigConstant.CMD_READ_OPERATING_MODE:
                break;
            case IcomRigConstant.CMD_READ_METER://read meter//this command is only implemented in network mode; serial port support may be added later
                if (icomCommand.getSubCommand() == IcomRigConstant.CMD_READ_METER_SWR) {
                    //XieGu little-endian mode
                    int temp = IcomRigConstant.twoByteBcdToIntBigEnd(icomCommand.getData(true));
                    if (temp != 255) {
                        swr = temp;//
                    }
                }
                if (icomCommand.getSubCommand() == IcomRigConstant.CMD_READ_METER_ALC) {
                    //XieGu little-endian mode
                    int temp = IcomRigConstant.twoByteBcdToIntBigEnd(icomCommand.getData(true));
                    if (temp != 255) {
                        alc = temp;//
                    }
                }
                showAlert();//check if meter value is in alert range
                notifyMeterData(alc, swr);

                break;
        }
    }


    private void showAlert() {
        if ((swr >= IcomRigConstant.swr_alert_max)
                && GeneralVariables.swr_switch_on) {
            if (!swrAlert) {
                swrAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_high_alert));
            }
        } else {
            swrAlert = false;
        }

        //XieGu ALC value should be within specified range
        //ALC too high
        if ((alc > IcomRigConstant.xiegu_alc_alert_max)
                && GeneralVariables.alc_switch_on) {//ALC alert
            if (!alcMaxAlert) {
                alcMaxAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.alc_high_alert));
            }
        } else {
            alcMaxAlert = false;
        }
        //ALC too low
        if ((alc < IcomRigConstant.xiegu_alc_alert_min)
                && GeneralVariables.alc_switch_on) {//ALC alert
            if (!alcMinAlert) {
                alcMinAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.alc_low_alert));
            }
        } else {
            alcMinAlert = false;
        }
    }


    @Override
    public void onReceiveData(byte[] data) {
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

    private void readSWRMeter() {
        if (getConnector() != null) {
            getConnector().sendData(IcomRigConstant.getSWRState(ctrAddress, getCivAddress()));
            getConnector().sendData(IcomRigConstant.getALCState(ctrAddress, getCivAddress()));
        }
    }

    @Override
    public String getName() {
        return "XIEGU 6100 series";
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


    public String getFrequencyStr() {
        return BaseRigOperation.getFrequencyStr(getFreq());
    }

    public XieGuRig(int civAddress) {
        Log.d(TAG, "XieGuRig 6100: Create.");
        setCivAddress(civAddress);

        readFreqTimer.schedule(readTask(), START_QUERY_FREQ_DELAY, QUERY_FREQ_TIMEOUT);
        //readFreqTimer.schedule(readTask(),START_QUERY_FREQ_DELAY,1000);
    }
}
