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

    /**
     * How often the frequency-poll timer asks the rig for its current dial. Sized
     * to the same 2 s cadence Yaesu38Rig / KenwoodTS590Rig / Elecraft use: fast
     * enough that a user turning the VFO on the rig sees the app follow within
     * a slot, slow enough that even a chatty CI-V stream stays comfortably below
     * the rig's command rate. See {@link #startReadFreqTimer}.
     */
    static final long READ_FREQ_PERIOD_MS = 2000;
    /**
     * Small delay before the first frequency poll so a fresh connect can finish
     * its USB-mode + set-frequency handshake ({@code setOperationBand}, ~800 ms
     * from onConnected) before we start reading — otherwise the very first
     * observed frequency could be the rig's power-on value, not the one we just
     * asserted. See {@link #startReadFreqTimer}.
     */
    static final long READ_FREQ_START_DELAY_MS = 2000;

    private final int ctrAddress = 0xE0;//receive address, default 0xE0; rig reply can also be 0x00
    private byte[] dataBuffer = new byte[0];//data buffer
    private int alc = 0;
    private int swr = 0;
    private boolean alcMaxAlert = false;
    private boolean swrAlert = false;
    private Timer meterTimer;//Timer for querying meter
    private Timer readFreqTimer;//Timer for polling frequency (rig->app dial follow)

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
    public boolean supportsAtuTune() {
        return true;
    }

    @Override
    public void startAtuTune() {
        sendCivData(IcomRigConstant.startAtuTune(ctrAddress, getCivAddress()));
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            getConnector().sendData(IcomRigConstant.setOperationFrequency(ctrAddress
                    , getCivAddress(), getFreq()));
        }
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

        // Append to any partial command buffered from a previous callback, then
        // process every complete command (each ending in 0xFD) and keep only the
        // trailing incomplete bytes for next time. See CivFrameSplitter for why the
        // previous hand-rolled reassembly dropped fragments and injected stray bytes.
        CivFrameSplitter.Result result = CivFrameSplitter.split(dataBuffer, data);
        for (byte[] command : result.commands) {
            analysisCommand(command);
        }
        dataBuffer = result.remainder;
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

    /**
     * Poll the rig for its current dial every {@link #READ_FREQ_PERIOD_MS} so the
     * app follows the operator turning the VFO on the rig itself (issue #753
     * follow-up: after the CI-V address hex fix the app could command the rig,
     * but rig&rarr;app dial updates still didn't land — every other CAT rig in
     * this codebase runs its own frequency poll and IcomRig was the odd one out
     * relying solely on {@link CatLiveness}'s 3 s liveness poll, which stops
     * hard on an 8 s quiet timeout).
     *
     * <p>Suppressed while transmitting: an unsolicited CI-V read during TX can
     * clobber the meter poll's SWR/ALC reads that {@link #startMeterTimer} runs
     * at 500 ms, and a rig can't turn its VFO while keyed anyway. Follows the
     * {@link ReadTaskAction} pattern already used by
     * {@link Yaesu38Rig} / {@link KenwoodTS590Rig} / {@link ElecraftRig}.
     */
    public void startReadFreqTimer() {
        readFreqTimer = new Timer();
        readFreqTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                runReadFreqTick();
            }
        }, READ_FREQ_START_DELAY_MS, READ_FREQ_PERIOD_MS);
    }

    /**
     * One tick of the frequency-poll timer, factored out so the decision path
     * can be exercised from tests without waiting on wall-clock time. Package-
     * private for the same reason.
     */
    void runReadFreqTick() {
        switch (ReadTaskAction.decide(isConnected(), isPttOn())) {
            case SKIP:
                return;
            case READ_METERS:
                //Meter reads are handled by the dedicated meter timer (500 ms
                //cadence); don't duplicate them here.
                return;
            case READ_FREQ:
                readFreqFromRig();
                break;
        }
    }

    @Override
    public void onDisconnecting() {
        // Cancel our timers so a reconnect (which builds a fresh IcomRig via
        // MainViewModel.connectRig -> baseRig.onDisconnecting) doesn't leak the
        // previous instance's polling task or double-poll after re-connect.
        if (readFreqTimer != null) {
            readFreqTimer.cancel();
            readFreqTimer.purge();
            readFreqTimer = null;
        }
        if (meterTimer != null) {
            meterTimer.cancel();
            meterTimer.purge();
            meterTimer = null;
        }
    }


    public String getFrequencyStr() {
        return BaseRigOperation.getFrequencyStr(getFreq());
    }


    public IcomRig(int civAddress, boolean newRig) {
        Log.d(TAG, "IcomRig: Create.");
        this.oldVersion = !newRig;//some older rigs do not support SWR query
        setCivAddress(civAddress);
        startMeterTimer();
        startReadFreqTimer();
    }
}
