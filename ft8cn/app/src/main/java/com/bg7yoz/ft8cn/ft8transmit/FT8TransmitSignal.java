package com.bg7yoz.ft8cn.ft8transmit;
/**
 * Class related to transmit signals. Includes the automated QSO process analysis.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.bg7yoz.ft8cn.wave.UsbAudioDevice;
import com.bg7yoz.ft8cn.wave.UsbAudioNative;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.log.QSLRecord;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.ui.ToastMessage;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FT8TransmitSignal {
    private static final String TAG = "FT8TransmitSignal";

    private boolean transmitFreeText = false;
    private String freeText = "FREE TEXT";

    private final DatabaseOpr databaseOpr;// configuration info and related data database
    private TransmitCallsign toCallsign;// target callsign
    public MutableLiveData<TransmitCallsign> mutableToCallsign = new MutableLiveData<>();

    private int functionOrder = 6;
    public MutableLiveData<Integer> mutableFunctionOrder = new MutableLiveData<>();// command order change
    private volatile boolean activated = false;// whether in transmit-ready mode
    public MutableLiveData<Boolean> mutableIsActivated = new MutableLiveData<>();
    public volatile int sequential;// transmit sequence
    public MutableLiveData<Integer> mutableSequential = new MutableLiveData<>();
    private volatile boolean pendingUserCQ = false;
    private volatile boolean isTransmitting = false;
    public MutableLiveData<Boolean> mutableIsTransmitting = new MutableLiveData<>();// whether currently transmitting
    public MutableLiveData<String> mutableTransmittingMessage = new MutableLiveData<>();// current message content
    // Posts a tick (the QSO end timestamp) every time a QSO completes successfully — used to drive
    // UI celebrations. Observers should treat the value as a one-shot signal (consume + ignore prior).
    public MutableLiveData<Long> mutableQsoCompletedAt = new MutableLiveData<>();

    //public MutableLiveData<Integer> currentOrder = new MutableLiveData<>();// current command to transmit

    //********************************************
    // Information below is used for saving QSL data
    private long messageStartTime = 0;// message start time
    private long messageEndTime = 0;// message end time
    private String toMaidenheadGrid = "";// target grid info
    private int sendReport = 0;// report I sent to the other party
    private int sentTargetReport = -100;//


    private int receivedReport = 0;// report I received
    private int receiveTargetReport = -100;// signal report sent to the other party
    //********************************************
    private final OnTransmitSuccess onTransmitSuccess;// typically used for saving QSL data


    // to prevent playback interruption, variables must not be local to methods
    private AudioAttributes attributes = null;
    private AudioFormat myFormat = null;
    private AudioTrack audioTrack = null;

    // Milliseconds we are late into the current cycle; leading audio is clipped
    // so the TX still finishes on the cycle boundary. Set just before playback.
    private volatile int lateStartSkipMs = 0;

    public UtcTimer utcTimer;


    public ArrayList<FunctionOfTransmit> functionList = new ArrayList<>();
    public MutableLiveData<ArrayList<FunctionOfTransmit>> mutableFunctions = new MutableLiveData<>();

    // Caller queue: stations that called us while we're in an active QSO
    private static final int MAX_QUEUE_SIZE = 10;
    private final ArrayList<QueuedCaller> callerQueue = new ArrayList<>();
    public MutableLiveData<ArrayList<QueuedCaller>> mutableCallerQueue = new MutableLiveData<>();

    private MeterProtectionController meterProtectionController;// ALC auto-volume + SWR halt

    public void setMeterProtectionController(MeterProtectionController controller) {
        this.meterProtectionController = controller;
    }

    private final OnDoTransmitted onDoTransmitted;// typically used for opening/closing PTT
    private final ExecutorService doTransmitThreadPool = Executors.newCachedThreadPool();
    private final DoTransmitRunnable doTransmitRunnable = new DoTransmitRunnable(this);

    static {
        System.loadLibrary("ft8cn");
    }

    /**
     * Constructor for the transmit module. Requires two callbacks: one for transmitting
     * (two actions for opening/closing PTT), and one for success (saving QSL data).
     *
     * @param databaseOpr       database
     * @param doTransmitted     callback for before/after transmit
     * @param onTransmitSuccess callback for successful transmit
     */
    public FT8TransmitSignal(DatabaseOpr databaseOpr
            , OnDoTransmitted doTransmitted, OnTransmitSuccess onTransmitSuccess) {
        this.onDoTransmitted = doTransmitted;// event for opening/closing PTT
        this.onTransmitSuccess = onTransmitSuccess;// event for saving QSL data
        this.databaseOpr = databaseOpr;

        setTransmitting(false);
        setActivated(false);


        // observe volume setting changes
        GeneralVariables.mutableVolumePercent.observeForever(new Observer<Float>() {
            @Override
            public void onChanged(Float aFloat) {
                if (audioTrack != null) {
                    audioTrack.setVolume(aFloat);
                }
            }
        });

        utcTimer = new UtcTimer(FT8Common.FT8_SLOT_TIME_M, false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {

            }

            //@RequiresApi(api = Build.VERSION_CODES.N)
            @Override
            public void doOnSecTimer(long utc) {
                // stop if auto-supervision timeout exceeded
                if (GeneralVariables.isLaunchSupervisionTimeout()) {
                    setActivated(false);
                    return;
                }
                if (UtcTimer.getNowSequential() == sequential && activated) {
                    if (GeneralVariables.myCallsign.length() < 3) {
                        // my callsign is invalid, cannot transmit!
                        ToastMessage.show(GeneralVariables.getStringFromResource(R.string.callsign_error));
                        return;
                    }
                    doTransmit();// transmit action follows precise timing; delay is the audio signal delay
                }
            }
        });

        utcTimer.start();

    }

    /**
     * Transmit immediately.
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    public void transmitNow() {
        if (GeneralVariables.myCallsign.length() < 3) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.callsign_error));
            return;
        }
        ToastMessage.show(String.format(GeneralVariables.getStringFromResource(R.string.adjust_call_target)
                , toCallsign.callsign));

        // reset signal report related values
        resetTargetReport();

        if (UtcTimer.getNowSequential() == sequential) {
            long msInCycle = UtcTimer.getSystemTime() % 15000;
            int tolerance = GeneralVariables.lateStartTolerance;
            if (tolerance < 0) tolerance = 0;
            if (tolerance > 4000) tolerance = 4000;
            if (msInCycle < tolerance) {
                setTransmitting(false);
                doTransmit();
            }
        }
    }

    // transmit signal
    //@RequiresApi(api = Build.VERSION_CODES.N)
    public void doTransmit() {
        if (!activated) {
            return;
        }
        // check if it is a blacklisted frequency (WSPR-2 frequency); frequency = rig frequency + audio frequency
        if (BaseRigOperation.checkIsWSPR2(
                GeneralVariables.band + Math.round(GeneralVariables.getBaseFrequency()))) {
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(R.string.use_wspr2_error)
                    , BaseRigOperation.getFrequencyAllInfo(GeneralVariables.band)));
            setActivated(false);
            return;
        }
        Log.d(TAG, "doTransmit: Starting transmit...");
        doTransmitThreadPool.execute(doTransmitRunnable);

        mutableFunctions.postValue(functionList);
    }

    /**
     * Set up the call and generate the transmit message list.
     *
     * @param transmitCallsign target callsign
     * @param functionOrder    command order
     * @param toMaidenheadGrid target grid
     */
    @SuppressLint("DefaultLocale")
    //@RequiresApi(api = Build.VERSION_CODES.N)
    public void setTransmit(TransmitCallsign transmitCallsign
            , int functionOrder, String toMaidenheadGrid) {

        messageStartTime = 0;// reset start time

        Log.d(TAG, "Preparing transmit data...");
        if (GeneralVariables.checkFun1(toMaidenheadGrid)) {
            this.toMaidenheadGrid = toMaidenheadGrid;
        } else {
            this.toMaidenheadGrid = "";
        }
        mutableToCallsign.postValue(transmitCallsign);// set the call target (includes report, sequence, frequency, callsign)
        toCallsign = transmitCallsign;// set the call target
        //mutableToCallsign.postValue(toCallsign);// set the call target

        if (functionOrder == -1) {// this is a reply message
            // at this point toMaidenheadGrid is extraInfo
            this.functionOrder = GeneralVariables.checkFunOrderByExtraInfo(toMaidenheadGrid) + 1;
            if (this.functionOrder == 6) {// if already at 73, switch to message 1
                this.functionOrder = 1;
            }
        } else {
            this.functionOrder = functionOrder;// current command sequence number
        }

        if (transmitCallsign.frequency == 0) {
            transmitCallsign.frequency = GeneralVariables.getBaseFrequency();
        }
        if (GeneralVariables.synFrequency) {// if same-frequency mode, match target callsign frequency
            setBaseFrequency(transmitCallsign.frequency);
        }

        sequential = (toCallsign.sequential + 1) % 2;// transmit sequence
        mutableSequential.postValue(sequential);// notify transmit sequence change
        generateFun();
        mutableFunctionOrder.postValue(functionOrder);

    }

    @SuppressLint("DefaultLocale")
    public void setBaseFrequency(float freq) {
        GeneralVariables.setBaseFrequency(freq);
        // write to database
        databaseOpr.writeConfig("freq", String.format("%.0f", freq), null);
    }

    /**
     * Generate the corresponding message based on the message number.
     *
     * @param order message number
     * @return FT8 message
     */
    public Ft8Message getFunctionCommand(int order) {
        switch (order) {
            // transmit mode 1: BG7YOY BG7YOZ OL50
            case 1:
                resetTargetReport();// reset the signal report record for the other party to -100
                return new Ft8Message(1, 0, toCallsign.callsign, GeneralVariables.myCallsign
                        , GeneralVariables.getMyMaidenhead4Grid());
            // transmit mode 2: BG7YOY BG7YOZ -10
            case 2:
                sentTargetReport = toCallsign.snr;

                return new Ft8Message(1, 0, toCallsign.callsign
                        , GeneralVariables.myCallsign, toCallsign.getSnr());
            // transmit mode 3: BG7YOY BG7YOZ R-10
            case 3:
                sentTargetReport = toCallsign.snr;
                return new Ft8Message(1, 0, toCallsign.callsign
                        , GeneralVariables.myCallsign, "R" + toCallsign.getSnr());
            // transmit mode 4: BG7YOY BG7YOZ RRR
            case 4:
                return new Ft8Message(1, 0, toCallsign.callsign
                        , GeneralVariables.myCallsign, "RR73");
            // transmit mode 5: BG7YOY BG7YOZ 73
            case 5:
                return new Ft8Message(1, 0, toCallsign.callsign
                        , GeneralVariables.myCallsign, "73");
            // transmit mode 6: CQ BG7YOZ OL50
            case 6:
                resetTargetReport();// reset sent and received signal report records to -100
                Ft8Message msg = new Ft8Message(1, 0, "CQ", GeneralVariables.myCallsign
                        , GeneralVariables.getMyMaidenhead4Grid());
                msg.modifier = GeneralVariables.toModifier;
                return msg;
        }

        return new Ft8Message("CQ", GeneralVariables.myCallsign
                , GeneralVariables.getMyMaidenhead4Grid());
    }

    /**
     * Generate the command sequence.
     */
    public void generateFun() {
        //ArrayList<FunctionOfTransmit> functions = new ArrayList<>();
        GeneralVariables.noReplyCount = 0;
        functionList.clear();
        for (int i = 1; i <= 6; i++) {
            if (functionOrder == 6) {// if current command is 6 (CQ), generate only one message
                functionList.add(new FunctionOfTransmit(6, getFunctionCommand(6), false));
                break;
            } else {
                functionList.add(new FunctionOfTransmit(i, getFunctionCommand(i), false));
            }
        }
        mutableFunctions.postValue(functionList);
        setCurrentFunctionOrder(functionOrder);// set current message
    }

    /**
     * Convert 32-bit float to 16-bit integer for maximum compatibility;
     * some sound cards do not support 32-bit float.
     *
     * @param buffer 32-bit float audio
     * @return 16-bit integer
     */
    private short[] float2Short(float[] buffer) {
        short[] temp = new short[buffer.length + 8];// extra 8 zero-padded samples for QP-7C RP2040 audio detection compatibility
        for (int i = 0; i < buffer.length; i++) {
            float x = buffer[i];
            if (x > 1.0)
                x = 1.0f;
            else if (x < -1.0)
                x = -1.0f;
            temp[i] = (short) (x * 32767.0);
        }
        return temp;
    }

    private void playFT8Signal(Ft8Message msg) {

        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {// network mode does not play audio locally
            Log.d(TAG, "playFT8Signal: Entering network transmit mode, waiting for audio to send.");


            if (onDoTransmitted != null) {// process audio data for ICOM network mode transmission
                onDoTransmitted.onTransmitByWifi(msg);
            }


            long now = System.currentTimeMillis();
            while (isTransmitting) {// wait for audio packets to finish sending before exiting, to trigger afterTransmitting
                try {
                    Thread.sleep(1);
                    long current = System.currentTimeMillis() - now;
                    if (current > 13100) {// actual transmit duration
                        isTransmitting = false;
                        break;
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            Log.d(TAG, "playFT8Signal: Exiting network audio transmission.");
            afterPlayAudio();
            return;
        }

        // enter CAT serial port audio transmission mode
        // 2023-08-16 Modification submitted by DS1UFX (based on v0.9), for (tr)uSDX audio over CAT support.
        if (GeneralVariables.controlMode == ControlMode.CAT) {
            Log.d(TAG, "playFT8Signal: try to transmit over CAT");

            if (onDoTransmitted != null) {// process audio data for truSDX CAT mode transmission
                if (onDoTransmitted.supportTransmitOverCAT()) {
                    onDoTransmitted.onTransmitOverCAT(msg);

                    long now = System.currentTimeMillis();
                    while (isTransmitting) {// wait for audio packets to finish before exiting, to trigger afterTransmitting
                        try {
                            Thread.sleep(1);
                            long current = System.currentTimeMillis() - now;
                            if (current > 13000) {// actual transmit duration
                                isTransmitting = false;
                                break;
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    Log.d(TAG, "playFT8Signal: transmitting over CAT is finished.");
                    afterPlayAudio();
                    return;
                }
            }
        }


        // enter sound card mode
        float[] buffer;
        buffer = GenerateFT8.generateFt8(msg, GeneralVariables.getBaseFrequency()
                , GeneralVariables.audioSampleRate);
        if (buffer == null) {
            afterPlayAudio();
            return;
        }

        // USB audio output path. Logged so a dev-screen reader can see
        // which branch was taken — the difference between TX going out
        // the USB radio (this branch) vs. Android's default sink (the
        // AudioTrack branch below) was the whole reason for #84, and we
        // currently have no way to tell from debug.log which one fired.
        GeneralVariables.fileLog(String.format(
                "playFT8Signal: TX path branch: audioOutputDeviceId=%d "
                        + "usbAudioOutputVidPid=%04X:%04X",
                GeneralVariables.audioOutputDeviceId,
                GeneralVariables.usbAudioOutputVendorId,
                GeneralVariables.usbAudioOutputProductId));
        if (GeneralVariables.audioOutputDeviceId == -1
                && GeneralVariables.usbAudioOutputVendorId != 0) {
            GeneralVariables.fileLog("playFT8Signal: using USB audio (direct) output");
            playViaUsbAudio(buffer);
            return;
        }
        GeneralVariables.fileLog(
                "playFT8Signal: using AudioTrack output (Android default sink)");

        Log.d(TAG, String.format("playFT8Signal: Preparing sound card playback... bit depth: %s, sample rate: %d"
                , GeneralVariables.audioOutput32Bit ? "Float32" : "Int16"
                , GeneralVariables.audioSampleRate));
        attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        //myFormat = new AudioFormat.Builder().setSampleRate(FT8Common.SAMPLE_RATE)
        myFormat = new AudioFormat.Builder().setSampleRate(GeneralVariables.audioSampleRate)
                .setEncoding(GeneralVariables.audioOutput32Bit ? // float vs integer
                        AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
        int mySession = 0;
        audioTrack = new AudioTrack(attributes, myFormat
                , GeneralVariables.audioOutput32Bit ? GeneralVariables.audioSampleRate * 15 * 4
                : GeneralVariables.audioSampleRate * 15 * 2// float vs integer
                , AudioTrack.MODE_STATIC
                , mySession);

        // set preferred output device
        if (GeneralVariables.audioOutputDeviceId > 0) {
            AudioDeviceInfo deviceInfo = findAudioDeviceById(
                    GeneralVariables.audioOutputDeviceId, AudioManager.GET_DEVICES_OUTPUTS);
            audioTrack.setPreferredDevice(deviceInfo); // null resets to default
        }

        // Skip leading samples if we started transmitting late, so audio still ends on the cycle boundary.
        int skipSamples = (lateStartSkipMs * GeneralVariables.audioSampleRate) / 1000;
        if (skipSamples < 0) skipSamples = 0;
        if (skipSamples >= buffer.length) skipSamples = 0;
        int playLength = buffer.length - skipSamples;

        // distinguish between 32-bit float and integer
        int writeResult;
        if (GeneralVariables.audioOutput32Bit) {
            writeResult = audioTrack.write(buffer, skipSamples, playLength
                    , AudioTrack.WRITE_NON_BLOCKING);
        } else {
            short[] audio_data = float2Short(buffer);
            writeResult = audioTrack.write(audio_data, skipSamples, playLength
                    , AudioTrack.WRITE_NON_BLOCKING);
        }

        if (playLength > writeResult) {
            Log.e(TAG, String.format("Playback buffer insufficient: %d--->%d", playLength, writeResult));
        }

        // check write result; if abnormal, release resources immediately
        if (writeResult == AudioTrack.ERROR_INVALID_OPERATION
                || writeResult == AudioTrack.ERROR_BAD_VALUE
                || writeResult == AudioTrack.ERROR_DEAD_OBJECT
                || writeResult == AudioTrack.ERROR) {
            // abnormal condition
            Log.e(TAG, String.format("Playback error: %d", writeResult));
            afterPlayAudio();
            return;
        }
        audioTrack.setNotificationMarkerPosition(playLength);
        audioTrack.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onMarkerReached(AudioTrack audioTrack) {
                afterPlayAudio();
            }

            @Override
            public void onPeriodicNotification(AudioTrack audioTrack) {

            }
        });
        if (audioTrack != null) {
            audioTrack.play();
            audioTrack.setVolume(GeneralVariables.volumePercent);// set playback volume
        }
    }

    /**
     * Play FT8 signal through USB audio device.
     */
    private void playViaUsbAudio(float[] buffer) {
        GeneralVariables.fileLog(String.format(
                "playViaUsbAudio: start, VID=%04X PID=%04X samples=%d rate=%d",
                GeneralVariables.usbAudioOutputVendorId,
                GeneralVariables.usbAudioOutputProductId,
                buffer.length, GeneralVariables.audioSampleRate));

        Context context = GeneralVariables.getMainContext();
        if (context == null) {
            GeneralVariables.fileLog("playViaUsbAudio: ABORT no main context");
            afterPlayAudio();
            return;
        }

        UsbDevice device = UsbAudioDevice.findDeviceByVidPid(context,
                GeneralVariables.usbAudioOutputVendorId,
                GeneralVariables.usbAudioOutputProductId);
        if (device == null) {
            GeneralVariables.fileLog(String.format(
                    "playViaUsbAudio: ABORT USB audio output device not found "
                            + "by VID:PID %04X:%04X",
                    GeneralVariables.usbAudioOutputVendorId,
                    GeneralVariables.usbAudioOutputProductId));
            afterPlayAudio();
            return;
        }

        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            GeneralVariables.fileLog("playViaUsbAudio: ABORT UsbManager is null");
            afterPlayAudio();
            return;
        }
        if (!usbManager.hasPermission(device)) {
            GeneralVariables.fileLog(
                    "playViaUsbAudio: ABORT no USB permission for output device "
                            + "(re-pick (USB direct) in Settings to re-grant)");
            afterPlayAudio();
            return;
        }

        UsbAudioDevice usbDev = new UsbAudioDevice();
        if (!usbDev.open(context, device)) {
            GeneralVariables.fileLog(
                    "playViaUsbAudio: ABORT UsbAudioDevice.open() failed "
                            + "(descriptor parse or claimInterface failed)");
            afterPlayAudio();
            return;
        }

        if (!usbDev.hasOutput()) {
            GeneralVariables.fileLog(
                    "playViaUsbAudio: ABORT device has no output endpoint");
            usbDev.close();
            afterPlayAudio();
            return;
        }

        if (!usbDev.activateOutput(48000)) {
            GeneralVariables.fileLog(
                    "playViaUsbAudio: ABORT activateOutput(48000) failed "
                            + "(alt-setting select or rate setup failed)");
            usbDev.close();
            afterPlayAudio();
            return;
        }
        GeneralVariables.fileLog(
                "playViaUsbAudio: device opened, output activated at 48000 Hz");

        // Skip leading samples if we started transmitting late, so audio still ends on the cycle boundary.
        int skipSamples = (lateStartSkipMs * GeneralVariables.audioSampleRate) / 1000;
        if (skipSamples < 0) skipSamples = 0;
        if (skipSamples >= buffer.length) skipSamples = 0;
        int playLength = buffer.length - skipSamples;

        // Apply volume
        float[] volumeAdjusted = new float[playLength];
        for (int i = 0; i < playLength; i++) {
            volumeAdjusted[i] = buffer[skipSamples + i] * GeneralVariables.volumePercent;
        }

        GeneralVariables.fileLog(String.format(
                "playViaUsbAudio: calling writeAudio playLength=%d rate=%d",
                playLength, GeneralVariables.audioSampleRate));
        boolean success = usbDev.writeAudio(volumeAdjusted, GeneralVariables.audioSampleRate);
        GeneralVariables.fileLog(
                "playViaUsbAudio: writeAudio returned " + (success ? "OK" : "FAILED"));

        usbDev.close();
        afterPlayAudio();
    }

    /**
     * Actions after audio playback completes, including the onAfterTransmit callback for closing PTT.
     */
    private void afterPlayAudio() {
        if (onDoTransmitted != null) {
            onDoTransmitted.onAfterTransmit(getFunctionCommand(functionOrder), functionOrder);
        }
        // Notify meter protection controller that the TX cycle ended (between-cycle ALC adjust)
        if (meterProtectionController != null) {
            meterProtectionController.onTxCycleEnd();
        }
        isTransmitting = false;
        mutableIsTransmitting.postValue(false);
        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
    }

    // actions when QSO completes successfully
    private void doComplete() {
        messageEndTime = UtcTimer.getSystemTime();// get the end time

        // if the other party has no grid, look it up from the historical callsign-grid mapping
        toMaidenheadGrid = GeneralVariables.getGridByCallsign(toCallsign.callsign, databaseOpr);

        if (messageStartTime == 0) {// if start time is missing, use current time
            messageStartTime = UtcTimer.getSystemTime();
        }


        // look up signal report from history
        // processing signal reports here because saved reports often differ from actual QSO reports
        // iterate through received signal reports from the other party
        for (int i = GeneralVariables.transmitMessages.size() - 1; i >= 0; i--) {
            Ft8Message message = GeneralVariables.transmitMessages.get(i);
            if ((GeneralVariables.checkFun3(message.extraInfo)
                    || GeneralVariables.checkFun2(message.extraInfo))
                    && (message.callsignFrom.equals(toCallsign.callsign)
                    && GeneralVariables.checkIsMyCallsign(message.callsignTo))) {
                    //&& message.callsignTo.equals(GeneralVariables.myCallsign))) {
                receiveTargetReport = getReportFromExtraInfo(message.extraInfo);
                break;
            }
        }
        // iterate through signal reports I sent to the other party
        for (int i = GeneralVariables.transmitMessages.size() - 1; i >= 0; i--) {
            Ft8Message message = GeneralVariables.transmitMessages.get(i);
            if ((GeneralVariables.checkFun3(message.extraInfo)
                    || GeneralVariables.checkFun2(message.extraInfo))
                    && (message.callsignTo.equals(toCallsign.callsign)
                    && GeneralVariables.checkIsMyCallsign(message.callsignFrom))) {
                    //&& message.callsignFrom.equals(GeneralVariables.myCallsign))) {
                sentTargetReport = getReportFromExtraInfo(message.extraInfo);
                break;
            }
        }


        messageEndTime = UtcTimer.getSystemTime();
        if (onDoTransmitted != null) {// for saving QSO records
            onTransmitSuccess.doAfterTransmit(new QSLRecord(
                    messageStartTime,
                    messageEndTime,
                    GeneralVariables.myCallsign,
                    GeneralVariables.getMyMaidenhead4Grid(),
                    toCallsign.callsign,
                    toMaidenheadGrid,
                    sentTargetReport != -100 ? sentTargetReport : sendReport,
                    receiveTargetReport != -100 ? receiveTargetReport : receivedReport,// if signal report for the other party is not -100, use the sent signal report record
                    "FT8",
                    GeneralVariables.band,
                    Math.round(GeneralVariables.getBaseFrequency())
            ));

            GeneralVariables.addQSLCallsign(toCallsign.callsign);// add successfully contacted callsign to the list
            ToastMessage.show(String.format("QSO : %s , at %s", toCallsign.callsign
                    , BaseRigOperation.getFrequencyAllInfo(GeneralVariables.band)));
            mutableQsoCompletedAt.postValue(messageEndTime);// signal UI celebration
        }

    }

    /**
     * Set the current transmit command order.
     *
     * @param order order
     */
    public void setCurrentFunctionOrder(int order) {
        functionOrder = order;
        for (int i = 0; i < functionList.size(); i++) {
            functionList.get(i).setCurrentOrder(order);
        }
        if (order == 1) {
            resetTargetReport();// reset signal reports
        }
        if (order == 4 || order == 5) {
            updateQSlRecordList(order, toCallsign);
        }
        mutableFunctions.postValue(functionList);
    }


    /**
     * When the target is a compound callsign (non-standard), JTDX reply may be shortened.
     *
     * @param fromCall the other party's callsign
     * @param toCall   my target callsign
     * @return true/false
     */
    private boolean checkCallsignIsCallTo(String fromCall, String toCall) {
        if (toCall.contains("/")) {// when the callsign contains a slash, JTDX strips characters after /
            return toCall.contains(fromCall);
        } else {
            return fromCall.equals(toCall);
        }
    }

    /**
     * Check the count of the target callsign in the "from" field of messages.
     * Returns 0 if the target callsign is calling me, >1 if the target is calling someone else.
     *
     * @param messages message list
     * @return 0: target is calling me, 1: no messages from the target, >1: target is calling others
     */
    private int checkTargetCallMe(ArrayList<Ft8Message> messages) {
        int fromCount = 1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Ft8Message ft8Message = messages.get(i);
            if (ft8Message.getSequence() == sequential) continue;// skip messages in the same sequence
            if (toCallsign == null) {
                continue;
            }
            //if (ft8Message.getCallsignTo().equals(GeneralVariables.myCallsign)
            if (GeneralVariables.checkIsMyCallsign(ft8Message.getCallsignTo())
                    && checkCallsignIsCallTo(ft8Message.getCallsignFrom(), toCallsign.callsign)) {
                return 0;
            }
            if (checkCallsignIsCallTo(ft8Message.getCallsignFrom(), toCallsign.callsign)) {
                fromCount++;// counter for when "from" is the target callsign
            }
        }
        return fromCount;
    }

    /**
     * Check for the reply message sequence number from the other party in this message list.
     * Returns -1 if not found.
     *
     * @param messages message list
     * @return message sequence number
     */
    private int checkFunctionOrdFromMessages(ArrayList<Ft8Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Ft8Message ft8Message = messages.get(i);
            if (ft8Message.getSequence() == sequential) continue;// skip messages in the same sequence
            if (toCallsign == null) {
                continue;
            }
            // this is call info between both parties
            //if (ft8Message.getCallsignTo().equals(GeneralVariables.myCallsign)
            if (GeneralVariables.checkIsMyCallsign(ft8Message.getCallsignTo())
                    && checkCallsignIsCallTo(ft8Message.getCallsignFrom(), toCallsign.callsign)) {
                //--TODO ---- check if start time is 0; if so, fill in the start time since some calls skip step 1

                // check if this is the signal report from the other party
                if (GeneralVariables.checkFun3(ft8Message.extraInfo)
                        || GeneralVariables.checkFun2(ft8Message.extraInfo)) {
                    // extract signal report from message; if invalid (-100), use the message's report
                    receivedReport = getReportFromExtraInfo(ft8Message.extraInfo);
                    receiveTargetReport = receivedReport;// save the signal report from the other party
                    if (receivedReport == -100) {// if invalid, use the message's report
                        receivedReport = ft8Message.report;
                    }
                }
                sendReport = messages.get(i).snr;// save the received signal

                int order = GeneralVariables.checkFunOrder(ft8Message);// check the message sequence number
                if (order != -1) return order;// successfully parsed the sequence number
            }
        }

        return -1;// no matching message found
    }

    /**
     * Get the signal report from the other party from the extra info. Returns -100 on failure.
     *
     * @param extraInfo extra info
     * @return signal report
     */
    private int getReportFromExtraInfo(String extraInfo) {
        String s = extraInfo.replace("R", "").trim();
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -100;
        }
    }

    /**
     * Check if the message should be excluded:
     * 1. Same transmit sequence
     * 2. Not on the same band
     * 3. Callsign prefix is in the exclusion list
     *
     * @param msg message
     * @return true/false
     */
    private boolean isExcludeMessage(Ft8Message msg) {
        return msg.getSequence() == sequential || msg.band != GeneralVariables.band
                || GeneralVariables.checkIsExcludeCallsign(msg.callsignFrom);
    }

    /**
     * Check if anyone is CQing me, or if a watched callsign is CQing.
     *
     * @param messages message list
     * @return false = no matching messages, true = matching messages found
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    private boolean checkCQMeOrFollowCQMessage(ArrayList<Ft8Message> messages) {
        return checkCQMeOrFollowCQMessage(messages, false);
    }

    private boolean checkCQMeOrFollowCQMessage(ArrayList<Ft8Message> messages, boolean suppressHunt) {
        // these messages are freshly decoded
        // both loops check for CQ-me messages. The first loop prioritizes checking for my target callsign,
        // to prevent replying inconsistently when multiple targets are calling me.
        // check CQ-me, not 73, and is my call target
        for (int i = messages.size() - 1; i >= 0; i--) {// check if anyone is CQing me (TO:ME, not 73)
            Ft8Message msg = messages.get(i);
            if (isExcludeMessage(msg)) continue;// check if this is an excluded message
            if (toCallsign == null) break;

            //if (msg.getCallsignTo().equals(GeneralVariables.myCallsign)
            if (GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())
                    && msg.getCallsignFrom().equals(toCallsign.callsign)//todo test compound callsign case
                    && !GeneralVariables.checkFun5(msg.extraInfo)) {// CQ me, not 73, sender is my watched target
                // before setting transmit, determine message sequence to avoid starting from the beginning
                setTransmit(new TransmitCallsign(msg.i3, msg.n3, msg.getCallsignFrom(), msg.freq_hz
                                , msg.getSequence(), msg.snr)
                        , GeneralVariables.checkFunOrder(msg) + 1
                        , msg.extraInfo);
                return true;
            }
        }

        // check CQ me, not 73
        for (int i = messages.size() - 1; i >= 0; i--) {// check if anyone is CQing me (TO:ME, not 73)
            Ft8Message msg = messages.get(i);
            if (isExcludeMessage(msg)) continue;// check if this is an excluded message
            //if ((msg.getCallsignTo().equals(GeneralVariables.myCallsign)
            if ((GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())
                    && !GeneralVariables.checkFun5(msg.extraInfo))) {// CQ me, not 73

                // Guard: if we're in an active QSO (not CQ), queue the caller instead of switching
                if (functionOrder != 6 && toCallsign != null && toCallsign.haveTargetCallsign()
                        && !msg.getCallsignFrom().equals(toCallsign.callsign)) {
                    enqueueCaller(msg);
                    continue;
                }

                // before setting transmit, determine message sequence to avoid starting from the beginning
                setTransmit(new TransmitCallsign(msg.i3, msg.n3, msg.getCallsignFrom(), msg.freq_hz
                                , msg.getSequence(), msg.snr)
                        , GeneralVariables.checkFunOrder(msg) + 1
                        , msg.extraInfo);
                return true;
            }
        }


        // exit if both auto-call modes are off: Hunt (auto-answer any CQ) and
        // auto-call of followed callsigns. These are now independent — Hunt no
        // longer requires autoCallFollow — so the on-screen HUNT toggle is
        // authoritative on its own.
        // suppressHunt: Auto-CQ forces pure CQ — answer direct callers (loops
        // above) but never auto-follow another station's CQ.
        if (suppressHunt || (!GeneralVariables.autoFollowCQ && !GeneralVariables.autoCallFollow)) {
            return false;
        }

        if (toCallsign == null) {
            return false;
        }
        // when there is already a target callsign, do not react to watched callsigns
        if (toCallsign.haveTargetCallsign()) {
            return false;
        }

        // Auto-answer / auto-call is secondary priority, after stations calling us
        // directly (the two loops above). Scan ONLY this cycle's fresh decodes —
        // never the long-lived transmitMessages history. Scanning history would
        // re-select a CQ heard minutes ago (including the very station the operator
        // just abandoned by pressing CQ), which made auto-answer "randomly" lock
        // onto an inactive station and call it forever. Live decodes only, matching
        // the "calling me" loops.
        for (int i = messages.size() - 1; i >= 0; i--) {
            Ft8Message msg = messages.get(i);
            if (isExcludeMessage(msg)) continue;// check if this is an excluded message

            // is CQing, not already worked, not myself, and either Hunt mode is on
            // (auto-answer any CQ) or this is a followed callsign we auto-call
            if (msg.checkIsCQ()// is CQing
                    && (GeneralVariables.autoFollowCQ// Hunt: auto-answer any CQ
                    || (GeneralVariables.autoCallFollow
                    && GeneralVariables.callsignInFollow(msg.getCallsignFrom())))// followed callsign
                    // skip directional CQs aimed at a different DXCC/continent (opt-in)
                    && (!GeneralVariables.respectDirectionalCQ
                    || GeneralVariables.directionalCQIsForMe(msg.callsignTo))
                    && !GeneralVariables.checkQSLCallsign(msg.getCallsignFrom())// not previously contacted successfully
                    && !GeneralVariables.checkIsMyCallsign(msg.callsignFrom)) {// not myself

                resetTargetReport();
                setTransmit(new TransmitCallsign(msg.i3, msg.n3, msg.getCallsignFrom(), msg.freq_hz
                        , msg.getSequence(), msg.snr), 1, msg.extraInfo);

                return true;
            }
        }

        return false;

    }


    public void updateQSlRecordList(int order, TransmitCallsign toCall) {
        if (toCall == null) return;
        if (toCall.callsign.equals("CQ")) return;

        QSLRecord record = GeneralVariables.qslRecordList.getRecordByCallsign(toCall.callsign);
        if (record == null) {
            toMaidenheadGrid = GeneralVariables.getGridByCallsign(toCallsign.callsign, databaseOpr);
            record = GeneralVariables.qslRecordList.addQSLRecord(new QSLRecord(
                    messageStartTime,
                    messageEndTime,
                    GeneralVariables.myCallsign,
                    GeneralVariables.getMyMaidenhead4Grid(),
                    toCallsign.callsign,
                    toMaidenheadGrid,
                    sentTargetReport != -100 ? sentTargetReport : sendReport,
                    receiveTargetReport != -100 ? receiveTargetReport : receivedReport,// if signal report is not -100, use the sent signal report record
                    "FT8",
                    GeneralVariables.band,
                    Math.round(GeneralVariables.getBaseFrequency()
                    )));
        }
        // update content based on message sequence
        switch (order) {
            case 1:// update grid and other party's message SNR
                record.setToMaidenGrid(toMaidenheadGrid);
                record.setSendReport(sentTargetReport != -100 ? sentTargetReport : sendReport);
                GeneralVariables.qslRecordList.deleteIfSaved(record);
                break;

            case 2:// update returned signal report from the other party
            case 3:
                record.setSendReport(sentTargetReport != -100 ? sentTargetReport : sendReport);
                record.setReceivedReport(receiveTargetReport != -100 ? receiveTargetReport : receivedReport);
                GeneralVariables.qslRecordList.deleteIfSaved(record);
                break;

            // when in RR73 or 73 state, save the log
            case 4:
            case 5:
                if (!record.saved) {
                    doComplete();// save to database
                    record.saved = true;
                }

                break;
        }

    }

    /**
     * Entry point for changing the transmit program based on decoded messages from the watch list.
     *
     * @param msgList message list
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    public void parseMessageToFunction(ArrayList<Ft8Message> msgList) {
        if (GeneralVariables.myCallsign.length() < 3) {
            return;
        }
        if (msgList.size() == 0) return;// no messages to parse, return

        if (msgList.get(0).getSequence() == sequential) {
            return;
        }
        ArrayList<Ft8Message> messages = new ArrayList<>(msgList);// prevent thread conflicts


        int newOrder = checkFunctionOrdFromMessages(messages);// check reply message sequence from the other party; -1 means not received
        if (newOrder != -1) {// if there is a message sequence, reply received; reset error counter
            GeneralVariables.noReplyCount = 0;
        }

        // update the QSO list; if not already recorded, save it
        updateQSlRecordList(newOrder, toCallsign);

        // FT8 protocol: when we receive RR73/RRR (order 4), always reply with
        // 73 (order 5) before completing the QSO. This handler fires before
        // the completion check so that we never skip the 73 reply.
        if (newOrder == 4) {
            functionOrder = 5;
            mutableFunctions.postValue(functionList);
            mutableFunctionOrder.postValue(functionOrder);
            setCurrentFunctionOrder(functionOrder);
            return;
        }

        // determine QSO success: other party replied 73 (5) || I am at 73 (5) and other party did not reply (-1)
        // or I am at RR73 (4) and no-reply threshold reached with no-reply limit enabled
        // or I am at RR73 (4) and the other party started calling someone else, to prevent RR73 deadlock
        if (newOrder == 5// target replied 73 to me
                || (functionOrder == 5 && newOrder == -1)// QSO success: other party replied 73 (5) || I am at 73 (5) and no reply (-1)
                || (functionOrder == 4 &&
                (GeneralVariables.noReplyCount > GeneralVariables.noReplyLimit * 2)
                && (GeneralVariables.noReplyLimit > 0)) // or I am at RR73 (4), reached no-reply threshold, with no-reply limit enabled

                || (functionOrder == 4 && checkTargetCallMe(messages) > 1)// or I am at RR73 (4) and target started calling others (>1 means target is calling others)

                || (functionOrder == 4 && (GeneralVariables.noReplyCount > 20)
                && (GeneralVariables.noReplyLimit == 0))// when no-reply is set to "ignore" and I am at RR73 (4), reset after 20 no-replies to prevent RR73 deadlock

        ) {
            // enter CQ state
            resetToCQ();

            // Auto-CQ: refresh the watchdog on each completed QSO so the run
            // keeps going without a re-tap. An idle CQ (no answers) still times out.
            if (GeneralVariables.autoCQAfterQSO) {
                GeneralVariables.resetLaunchSupervision();
            }

            // try queued callers first, then answer direct callers. When Auto-CQ
            // is on, suppress Hunt so we stay on our run frequency calling CQ
            // instead of chasing someone else's CQ.
            if (!dequeueNextCaller()) {
                checkCQMeOrFollowCQMessage(messages, GeneralVariables.autoCQAfterQSO);
            }
            setCurrentFunctionOrder(functionOrder);// set current message
            mutableFunctionOrder.postValue(functionOrder);
            return;
        }


        if (newOrder != -1) {//message received but QSO not yet complete
            // originally newOrder == 1, but sometimes the other party sends a signal report directly, i.e. message 2
            if (newOrder == 1 || newOrder == 2) {// this is the first reply from the other party
                resetTargetReport();// reset the signal reports
                generateFun();
            }

            functionOrder = newOrder + 1;// execute the next message in sequence
            mutableFunctions.postValue(functionList);
            mutableFunctionOrder.postValue(functionOrder);
            setCurrentFunctionOrder(functionOrder);// set current message
            return;
        }


        // at this point I am not yet in message 6 state; check if anyone is calling me
        // 2022-09-22 if someone is calling me or auto-follow is active, set up a new transmit message list
        if (!pendingUserCQ && checkCQMeOrFollowCQMessage(messages)) {
            return;
        }


        // at this point, no reply messages were received
        // if I am in CQ state, newOrder must be -1
        if (functionOrder == 6) {// I am in CQ state
            if (pendingUserCQ) {
                pendingUserCQ = false;
            } else if (!dequeueNextCaller()) {
                checkCQMeOrFollowCQMessage(messages);
            }
            return;
        }


        // at this point, no reply; increment error count (weak signal detection does not count as no-reply)
        if (!messages.get(0).isWeakSignal) {
            GeneralVariables.noReplyCount++;
        }
        // if no-reply limit exceeded, reset to CQ state
        if ((GeneralVariables.noReplyCount > GeneralVariables.noReplyLimit) && (GeneralVariables.noReplyLimit > 0)) {
            // try queued callers first, then check watched messages, then fall back to CQ
            if (!dequeueNextCaller()) {
                if (!getNewTargetCallsign(messages)) {//check CQ messages in watch list; returns true if a new target is found
                    functionOrder = 6;
                    toCallsign.callsign = "CQ";
                }
            }
            generateFun();
            setCurrentFunctionOrder(functionOrder);// set current message
            mutableToCallsign.postValue(toCallsign);
            mutableFunctionOrder.postValue(functionOrder);

        }

    }

    /**
     * Check watch list for active CQ messages that are not my current target callsign.
     *
     * @param messages watched message list
     * @return true if new target found, false otherwise
     */
    public boolean getNewTargetCallsign(ArrayList<Ft8Message> messages) {
        if (toCallsign == null) return false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Ft8Message ft8Message = messages.get(i);
            if (ft8Message.band != GeneralVariables.band) {//ignore messages not on the same band
                continue;
            }
            // not CQ, ignore
            if (!ft8Message.checkIsCQ()) {
                continue;
            }
            // not the current target callsign, and no previous successful QSO
            if ((!ft8Message.getCallsignFrom().equals(toCallsign.callsign)
                    && (!GeneralVariables.checkQSLCallsign(ft8Message.getCallsignFrom())))) //no previous successful QSO
            {
                functionOrder = 1;
                toCallsign.callsign = ft8Message.getCallsignFrom();
                return true;
            }


        }
        return false;
    }

    /**
     * Get the target callsign string for UI display.
     * @return callsign string or null if no target
     */
    public String getToCallsignString() {
        return toCallsign != null ? toCallsign.callsign : null;
    }

    public boolean isSynFrequency() {
        return GeneralVariables.synFrequency;
    }


    public boolean isActivated() {
        return activated;
    }

    public void setActivated(boolean activated) {
        // Block activation if SWR lockout is active
        if (activated && meterProtectionController != null
                && meterProtectionController.isSwrLocked()) {
            ToastMessage.show("TX blocked — SWR lockout active. Dismiss the lockout banner first.");
            return;
        }
        this.activated = activated;
        if (!this.activated) {//force stop transmitting
            setTransmitting(false);
            clearCallerQueue();
        }
        mutableIsActivated.postValue(activated);
    }

    public boolean isTransmitting() {
        return isTransmitting;
    }

    public void setTransmitting(boolean transmitting) {
        if (GeneralVariables.myCallsign.length() < 3 && transmitting) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.callsign_error));
            return;
        }

        if (!transmitting) {//stop transmitting
            // Abort any in-progress USB-audio write so TX audio stops immediately.
            // The native/fallback writers run on the transmit worker thread and
            // would otherwise drain the full ~12.6s message; this signals them to
            // bail out. When the writer returns, playViaUsbAudio's afterPlayAudio()
            // drops PTT. No-op when not transmitting over USB audio.
            UsbAudioNative.cancelWrite();
            if (audioTrack != null) {
                if (audioTrack.getState() != AudioTrack.STATE_UNINITIALIZED) {
                    audioTrack.pause();
                }
                if (onDoTransmitted != null) {//notify that transmitting has stopped
                    onDoTransmitted.onAfterTransmit(getFunctionCommand(functionOrder), functionOrder);
                }
                // Release the paused track: its completion marker won't fire once
                // paused, so afterPlayAudio() would never run for this branch and
                // the track would leak until the next TX overwrites the field.
                audioTrack.release();
                audioTrack = null;
            }
        }

        mutableIsTransmitting.postValue(transmitting);
        isTransmitting = transmitting;
    }

    /**
     * Reset transmit sequence to 6; the timing sequence will also change.
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    public void restTransmitting() {
        if (GeneralVariables.myCallsign.length() < 3) {
            return;
        }
        clearCallerQueue();
        //must determine my callsign type to set i3n3 !!!
        int i3 = GenerateFT8.checkI3ByCallsign(GeneralVariables.myCallsign);
        setTransmit(new TransmitCallsign(i3, 0, "CQ", UtcTimer.getNowSequential())
                , 6, "");

    }

    /**
     * Reset the signal report records for the other party to -100.
     */
    public void resetTargetReport() {
        receiveTargetReport = -100;
        sentTargetReport = -100;
    }

    /**
     * Reset transmit sequence to 6 without changing the timing sequence.
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    public void resetToCQ() {
        resetTargetReport();
        if (toCallsign == null) {
            //must determine my callsign type to set i3n3 !!!
            int i3 = GenerateFT8.checkI3ByCallsign(GeneralVariables.myCallsign);
            setTransmit(new TransmitCallsign(i3, 0, "CQ", sequential)
                    , 6, "");
        } else {
            functionOrder = 6;
            toCallsign.callsign = "CQ";
            mutableToCallsign.postValue(toCallsign);// set the call target
            generateFun();
        }
    }

    /**
     * Called when the user explicitly presses CQ. Clears the caller queue and
     * sets a flag so that the first decode cycle after pressing CQ will not
     * auto-follow or dequeue — the CQ message transmits cleanly first.
     */
    public void userResetToCQ() {
        resetToCQ();
        clearCallerQueue();
        pendingUserCQ = true;
    }

    /**
     * Force-log the current QSO and move on.
     * Called when the user taps "LOG" to skip waiting for a 73 reply.
     *
     * @param nextCallsign if non-null, dequeue and start this specific caller
     *                     instead of the head of the queue.
     */
    public void forceLogAndMoveOn(String nextCallsign) {
        // Ensure QSO is saved (no-op if already saved at function order 4/5)
        updateQSlRecordList(4, toCallsign);

        // Mirror the normal QSO-completion path from parseMessageToFunction
        resetToCQ();

        if (GeneralVariables.autoCQAfterQSO) {
            GeneralVariables.resetLaunchSupervision();
        }

        if (nextCallsign != null) {
            dequeueSpecificCaller(nextCallsign);
        } else if (!dequeueNextCaller()) {
            // No queued callers — stay on CQ
        }

        setCurrentFunctionOrder(functionOrder);
        mutableFunctionOrder.postValue(functionOrder);
    }

    /** Convenience overload: log and move on to the head of the queue. */
    public void forceLogAndMoveOn() {
        forceLogAndMoveOn(null);
    }

    // ==================== Caller Queue Methods ====================

    /**
     * Add a caller to the queue. Deduplicates by callsign (updates SNR/freq if already present).
     * Skips callers that are excluded, already QSL'd, or our own callsign.
     */
    public void enqueueCaller(Ft8Message msg) {
        String callsign = msg.getCallsignFrom();
        if (callsign == null || callsign.isEmpty()) return;
        if (GeneralVariables.checkIsMyCallsign(callsign)) return;
        if (GeneralVariables.checkIsExcludeCallsign(callsign)) return;
        if (GeneralVariables.checkQSLCallsign(callsign)) return;

        synchronized (callerQueue) {
            // Update existing entry if callsign already queued
            for (int i = 0; i < callerQueue.size(); i++) {
                if (callerQueue.get(i).callsign.equals(callsign)) {
                    QueuedCaller existing = callerQueue.get(i);
                    existing.snr = msg.snr;
                    existing.queuedTimeMs = System.currentTimeMillis();
                    mutableCallerQueue.postValue(new ArrayList<>(callerQueue));
                    return;
                }
            }
            // Add new entry if not at capacity
            if (callerQueue.size() < MAX_QUEUE_SIZE) {
                callerQueue.add(new QueuedCaller(
                        callsign, msg.freq_hz, msg.getSequence(), msg.snr,
                        msg.i3, msg.n3, msg.extraInfo));
                mutableCallerQueue.postValue(new ArrayList<>(callerQueue));
            }
        }
    }

    /**
     * Dequeue the next caller and start a QSO with them via setTransmit().
     * Skips stale or excluded callers. Returns true if a caller was started.
     */
    public boolean dequeueNextCaller() {
        synchronized (callerQueue) {
            while (!callerQueue.isEmpty()) {
                QueuedCaller caller = callerQueue.remove(0);
                mutableCallerQueue.postValue(new ArrayList<>(callerQueue));

                // Skip if caller is now excluded or already worked
                if (GeneralVariables.checkIsExcludeCallsign(caller.callsign)) continue;
                if (GeneralVariables.checkQSLCallsign(caller.callsign)) continue;

                // Start QSO with this caller
                resetTargetReport();
                setTransmit(new TransmitCallsign(caller.i3, caller.n3, caller.callsign,
                                caller.frequency, caller.sequential, caller.snr),
                        GeneralVariables.checkFunOrderByExtraInfo(caller.extraInfo) + 1,
                        caller.extraInfo);
                return true;
            }
        }
        return false;
    }

    /**
     * Remove a specific caller from the queue by callsign and start a QSO with them.
     * Returns true if the caller was found and started.
     */
    public boolean dequeueSpecificCaller(String callsign) {
        if (callsign == null || callsign.isEmpty()) return false;
        synchronized (callerQueue) {
            for (int i = 0; i < callerQueue.size(); i++) {
                if (callerQueue.get(i).callsign.equals(callsign)) {
                    QueuedCaller caller = callerQueue.remove(i);
                    mutableCallerQueue.postValue(new ArrayList<>(callerQueue));

                    resetTargetReport();
                    setTransmit(new TransmitCallsign(caller.i3, caller.n3, caller.callsign,
                                    caller.frequency, caller.sequential, caller.snr),
                            GeneralVariables.checkFunOrderByExtraInfo(caller.extraInfo) + 1,
                            caller.extraInfo);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Clear the entire caller queue.
     */
    public void clearCallerQueue() {
        synchronized (callerQueue) {
            callerQueue.clear();
            mutableCallerQueue.postValue(new ArrayList<>(callerQueue));
        }
    }

    // ==================== End Caller Queue Methods ====================

    /**
     * Set transmit time delay; this delay also provides decoding time for the previous cycle.
     *
     * @param sec milliseconds
     */
    public void setTimer_sec(int sec) {
        utcTimer.setTime_sec(sec);
    }

    public boolean isTransmitFreeText() {
        return transmitFreeText;
    }

    public void setFreeText(String freeText) {
        this.freeText = freeText;
    }

    public void setTransmitFreeText(boolean transmitFreeText) {
        this.transmitFreeText = transmitFreeText;
        if (transmitFreeText) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.trans_free_text_mode));
        } else {
            ToastMessage.show((GeneralVariables.getStringFromResource(R.string.trans_standard_messge_mode)));
        }
    }


    /**
     * Find AudioDeviceInfo by device ID.
     */
    private static AudioDeviceInfo findAudioDeviceById(int deviceId, int deviceType) {
        Context context = GeneralVariables.getMainContext();
        if (context == null) return null;
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return null;
        AudioDeviceInfo[] devices = audioManager.getDevices(deviceType);
        for (AudioDeviceInfo device : devices) {
            if (device.getId() == deviceId) {
                return device;
            }
        }
        return null;
    }

    private static class DoTransmitRunnable implements Runnable {
        FT8TransmitSignal transmitSignal;

        public DoTransmitRunnable(FT8TransmitSignal transmitSignal) {
            this.transmitSignal = transmitSignal;
        }

        @SuppressLint("DefaultLocale")
        @Override
        public void run() {
            //todo may need modification here: maintain a list recording each callsign, grid, time, and band
            if (transmitSignal.functionOrder == 1 || transmitSignal.functionOrder == 2) {// when message is at 1 or 2, QSO has started
                transmitSignal.messageStartTime = UtcTimer.getSystemTime();
            }
            if (transmitSignal.messageStartTime == 0) {// if no start time, use current time
                transmitSignal.messageStartTime = UtcTimer.getSystemTime();
            }

            // for displaying the message content to be transmitted
            Ft8Message msg;
            if (transmitSignal.transmitFreeText) {
                msg = new Ft8Message("CQ", GeneralVariables.myCallsign, transmitSignal.freeText);
                msg.i3 = 0;
                msg.n3 = 0;
            } else {
                msg = transmitSignal.getFunctionCommand(transmitSignal.functionOrder);
            }
            msg.modifier = GeneralVariables.toModifier;

            if (transmitSignal.onDoTransmitted != null) {
                // handle PTT and other events here
                transmitSignal.onDoTransmitted.onBeforeTransmit(msg, transmitSignal.functionOrder);
            }

            transmitSignal.isTransmitting = true;
            transmitSignal.mutableIsTransmitting.postValue(true);


            transmitSignal.mutableTransmittingMessage.postValue(String.format(" (%.0fHz) %s"
                    , GeneralVariables.getBaseFrequency()
                    , msg.getMessageText()));
            // generate signal
//            float[] buffer=GenerateFT8.generateFt8(msg, GeneralVariables.getBaseFrequency());
//            if (buffer==null) {
//                return;
//            }

            // radio actions may have a delay, so timing may not be perfectly accurate
            try {// give the radio a response time
                Thread.sleep(GeneralVariables.pttDelay);// response time after PTT command, default 100ms
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // Compute how late we are *vs. when FT8 audio should start*, and only
            // clip leading audio if we'd overrun the 15-second cycle otherwise.
            //
            // FT8 = 79 GFSK symbols at 0.16s each = 12.64s of audio. The spec
            // expects audio to start ~+0.5s into each 15s cycle, so any start
            // between 0 and (15.00 - 12.64) = 2.36s into the cycle fits without
            // clipping. The original `msLate = position % 15000` treated every
            // millisecond past the cycle boundary as lateness — so a normal
            // on-time TX that fires ~500-800ms into the cycle (totally fine)
            // would chop 500-800ms off the *start* of the audio buffer, which
            // is exactly where the leading Costas sync array lives (symbols
            // 0-6, the first 1.12s). Receivers couldn't lock, and the signal
            // came across as audible-but-undecodable.
            //
            // New behavior: skip only the excess past 2.36s. On-time and
            // mildly-late TXs send the full waveform; only genuinely late
            // starts (over ~2.4s into cycle) start clipping leading audio.
            int msIntoCycle = (int) (UtcTimer.getSystemTime() % 15000);
            int msMaxStart = 15000 - 12640; // 2360ms — last moment we can start without overrunning
            int msLate = msIntoCycle - msMaxStart;
            if (msLate < 0) msLate = 0;
            if (msLate >= 15000) msLate = 14999;
            transmitSignal.lateStartSkipMs = msLate;
            if (msLate > 100) {
                Log.d(TAG, String.format("Late start: skipping %d ms of leading audio", msLate));
                ToastMessage.show(String.format("Late start: −%d ms", msLate));
            }

//            if (transmitSignal.onDoTransmitted != null) {//process audio data for ICOM network mode transmission
//                transmitSignal.onDoTransmitted.onAfterGenerate(buffer);
//            }
            // play audio
            //transmitSignal.playFT8Signal(buffer);
            transmitSignal.playFT8Signal(msg);
        }
    }
}
