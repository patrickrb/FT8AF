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

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.ModeProfile;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.ft8signal.FT8Package;
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

    // Set by setTransmitting(false)/STOP to abort the chunked MODE_STREAM
    // AudioTrack playback loop mid-buffer. The worker thread owns the track's
    // stop/release (see playFT8Signal); the UI thread only flips this flag and
    // pauses+flushes the track for immediate silence, avoiding a release race
    // against the worker blocked in write(). Reset at the start of each cycle's
    // sound-card playback.
    private volatile boolean txAudioCancelled = false;

    public UtcTimer utcTimer;


    public ArrayList<FunctionOfTransmit> functionList = new ArrayList<>();
    public MutableLiveData<ArrayList<FunctionOfTransmit>> mutableFunctions = new MutableLiveData<>();

    // Failsafe caps that apply even when noReplyLimit is set to "ignore" (0), so
    // a run never wedges. Calling states (order 1-3): give up on a station that
    // never answers instead of transmitting at it forever. RR73 (order 4): the
    // QSO is already logged, so only repeat RR73 a few times for the partner's
    // benefit, then return to CQ / next caller.
    private static final int NO_REPLY_GIVEUP_CYCLES = 4;
    private static final int RR73_GIVEUP_CYCLES = 3;

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
        try {
            System.loadLibrary("ft8cn");
        } catch (UnsatisfiedLinkError e) {
            // Best-effort load: JVM unit tests don't have libft8cn.so on
            // java.library.path. The native methods themselves will throw if
            // actually invoked without the library; the pure-Java helpers on
            // this class (applyVolume, float2Short) stay available either way.
            Log.w(TAG, "ft8cn native library not loaded: " + e.getMessage());
        }
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


        // TX volume is applied live, mid-transmission: the chunked MODE_STREAM AudioTrack
        // loop re-reads GeneralVariables.volumePercent for each chunk (see playFT8Signal),
        // and the USB-direct path applies gain live via UsbAudioNative.setTxVolume. A slider
        // change therefore takes effect within the current cycle, so no per-cycle setVolume()
        // observer is needed here.

        // Cycle timer on the current mode's period (FT8 = 15s/150, FT4 = 7.5s/75).
        utcTimer = new UtcTimer(GeneralVariables.currentMode().slotTenths, false, makeTimerCallback());

        utcTimer.start();

    }

    /** The cycle-trigger callback, shared between the constructor and {@link #rebuildTimer}. */
    private OnUtcTimer makeTimerCallback() {
        return new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {

            }

            //@RequiresApi(api = Build.VERSION_CODES.N)
            @Override
            public void doOnSecTimer(long utc) {
                // Hunt (auto-answer) is armed via setActivated(true) so it CAN reply, but it
                // only answers other stations' CQs — it never calls CQ itself. While no
                // caller is locked yet it's just listening, so keep the TX watchdog fresh;
                // otherwise an idle hunt would auto-stop after the timeout even though it's
                // behaving correctly. A stuck *active* QSO still ages the watchdog (this is
                // false once a real target is locked).
                if (isHuntListeningIdle(GeneralVariables.autoFollowCQ, functionOrder, toCallsign)) {
                    GeneralVariables.resetLaunchSupervision();
                }
                // stop if auto-supervision timeout exceeded
                if (GeneralVariables.isLaunchSupervisionTimeout()) {
                    setActivated(false);
                    return;
                }
                if (UtcTimer.getNowSequential() == sequential && activated) {
                    // Idle hunt: stay silent this slot and keep listening rather than keying
                    // up a CQ. Once parseMessageToFunction locks a caller (order advances,
                    // target becomes a real callsign) this is false and the reply transmits.
                    if (isHuntListeningIdle(GeneralVariables.autoFollowCQ, functionOrder, toCallsign)) {
                        return;
                    }
                    if (GeneralVariables.myCallsign.length() < 3) {
                        // my callsign is invalid, cannot transmit!
                        ToastMessage.show(GeneralVariables.getStringFromResource(R.string.callsign_error));
                        return;
                    }
                    doTransmit();// transmit action follows precise timing; delay is the audio signal delay
                }
            }
        };
    }

    /**
     * Recreate the cycle timer for a new operating mode. {@link UtcTimer}'s period is fixed
     * at construction, so a mode change (FT8 15s -> FT4 7.5s) requires a fresh timer. The
     * timer is always restarted; callers should ensure we are not mid-transmit first.
     */
    public void rebuildTimer(ModeProfile mode) {
        utcTimer.delete();
        utcTimer = new UtcTimer(mode.slotTenths, false, makeTimerCallback());
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
            long msInCycle = UtcTimer.getSystemTime() % GeneralVariables.currentMode().slotMillis;
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
        // Record exactly what goes on the air this cycle, alongside the existing
        // serial.send TX1/TX0 keying lines, so the QSO trace shows the message text.
        GeneralVariables.fileLog("QSO: TX slot=" + sequential + " order=" + functionOrder
                + " msg=[" + getFunctionCommand(functionOrder).getMessageText() + "]");
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

        GeneralVariables.fileLog("QSO: setTransmit to=" + toCallsign.callsign
                + " order=" + this.functionOrder + " slot=" + sequential);
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
     * Scale a slice of the generated waveform by the TX volume, returning a new
     * buffer of exactly {@code playLength} samples.
     *
     * <p>This is the single source of truth for TX level. Both output paths use
     * it: the AudioTrack path (because {@code AudioTrack.setVolume()} is a no-op
     * when the track is routed to a USB Audio Class device, so the gain must be
     * baked into the samples) and the USB-direct path. Pure function, no Android
     * or native dependencies — covered by unit tests.
     *
     * @param source     full generated waveform
     * @param skipSamples leading samples to drop (late-start trim); clamped to 0
     * @param playLength number of samples to emit (source.length - skipSamples)
     * @param volume     gain 0.0-1.0; 0 yields digital silence
     * @return new float[playLength] of scaled samples
     */
    static float[] applyVolume(float[] source, int skipSamples, int playLength, float volume) {
        if (skipSamples < 0) skipSamples = 0;
        float[] out = new float[playLength];
        for (int i = 0; i < playLength; i++) {
            out[i] = source[skipSamples + i] * volume;
        }
        return out;
    }

    /**
     * Convert 32-bit float to 16-bit integer for maximum compatibility;
     * some sound cards do not support 32-bit float.
     *
     * @param buffer 32-bit float audio
     * @return 16-bit integer
     */
    static short[] float2Short(float[] buffer) {
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

    /**
     * Convert the first {@code length} float samples to 16-bit, with no trailing
     * zero padding. Used by the chunked MODE_STREAM playback loop, which writes
     * many chunks per cycle; the 8-sample QP-7C pad must be appended once at the
     * very end of the message, not after every chunk (see {@link #float2Short}).
     * Pure function — covered by unit tests.
     *
     * @param buffer source samples
     * @param length number of samples to convert (≤ buffer.length)
     * @return new short[length] of clipped, scaled samples
     */
    static short[] floatToInt16NoPad(float[] buffer, int length) {
        short[] out = new short[length];
        for (int i = 0; i < length; i++) {
            float x = buffer[i];
            if (x > 1.0f) x = 1.0f;
            else if (x < -1.0f) x = -1.0f;
            out[i] = (short) (x * 32767.0);
        }
        return out;
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

        // Fresh cancel state for this cycle's playback (consumed by the chunked
        // AudioTrack loop below; the USB path has its own cancel via
        // UsbAudioNative.resetCancel/cancelWrite).
        txAudioCancelled = false;

        // USB audio output path. Logged so a dev-screen reader can see
        // which branch was taken — the difference between TX going out
        // the USB radio (this branch) vs. Android's default sink (the
        // AudioTrack branch below) was the whole reason for #84, and we
        // currently have no way to tell from debug.log which one fired.
        GeneralVariables.fileLog(String.format(
                "playFT8Signal: TX path branch: audioOutputDeviceId=%d "
                        + "usbAudioOutputVidPid=%04X:%04X volume=%.0f%% autoVolume=%b",
                GeneralVariables.audioOutputDeviceId,
                GeneralVariables.usbAudioOutputVendorId,
                GeneralVariables.usbAudioOutputProductId,
                GeneralVariables.volumePercent * 100f,
                GeneralVariables.autoVolumeEnabled));
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

        int encoding = GeneralVariables.audioOutput32Bit
                ? AudioFormat.ENCODING_PCM_FLOAT : AudioFormat.ENCODING_PCM_16BIT;
        myFormat = new AudioFormat.Builder().setSampleRate(GeneralVariables.audioSampleRate)
                .setEncoding(encoding)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();

        // MODE_STREAM (was MODE_STATIC) with a deliberately SMALL buffer so we can
        // apply TX volume live: we feed the generated waveform out in ~50ms chunks,
        // re-reading volumePercent for each chunk, and a blocking write only commits
        // a chunk once the device has room. The buffer depth (~200ms) bounds how much
        // audio is already queued at the old level, i.e. the worst-case latency from
        // a slider move to the level changing on air — instant enough to pull drive
        // down and protect the rig mid-over, which baking volume into a one-shot
        // MODE_STATIC write could not do (the slider only took effect next cycle).
        int bytesPerSample = GeneralVariables.audioOutput32Bit ? 4 : 2;
        int targetBufBytes = (GeneralVariables.audioSampleRate / 5) * bytesPerSample; // ~200ms mono
        int minBuf = AudioTrack.getMinBufferSize(GeneralVariables.audioSampleRate,
                AudioFormat.CHANNEL_OUT_MONO, encoding);
        int bufBytes = Math.max(targetBufBytes, minBuf > 0 ? minBuf : targetBufBytes);
        int mySession = 0;
        audioTrack = new AudioTrack(attributes, myFormat, bufBytes,
                AudioTrack.MODE_STREAM, mySession);

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

        // Keep the track at unity: TX level is carried in the sample values (we scale
        // each chunk below). On routes where AudioTrack.setVolume() works (phone
        // speaker) this avoids double-attenuation; on routes where it's a no-op (USB
        // Audio Class sound cards delegating level to device hardware) the in-sample
        // gain is what actually controls the rig's drive.
        audioTrack.play();
        audioTrack.setVolume(1.0f);

        // Stream the waveform in chunks, re-reading volumePercent each chunk so a
        // slider move ramps the on-air level within ~1 chunk + buffer depth.
        final int chunkSamples = Math.max(1, GeneralVariables.audioSampleRate / 20); // ~50ms
        int framesWritten = 0;
        boolean writeError = false;
        int offset = 0;
        while (offset < playLength) {
            if (txAudioCancelled || !isTransmitting) break;
            int chunkLen = Math.min(chunkSamples, playLength - offset);
            // applyVolume reads volumePercent fresh for this chunk (live gain) and
            // performs the late-start slice at skipSamples + offset.
            float[] chunk = applyVolume(buffer, skipSamples + offset, chunkLen,
                    GeneralVariables.volumePercent);

            int writeResult;
            if (GeneralVariables.audioOutput32Bit) {
                writeResult = audioTrack.write(chunk, 0, chunkLen, AudioTrack.WRITE_BLOCKING);
            } else {
                short[] audio_data = floatToInt16NoPad(chunk, chunkLen);
                writeResult = audioTrack.write(audio_data, 0, chunkLen, AudioTrack.WRITE_BLOCKING);
            }

            if (writeResult < 0) {
                Log.e(TAG, String.format("Playback error: %d", writeResult));
                writeError = true;
                break;
            }
            framesWritten += writeResult;
            offset += chunkLen;
        }

        // Append the 8-sample zero pad once at the end (QP-7C RP2040 audio
        // detection compatibility), only for the int16 path — matches the old
        // float2Short() behavior. Skipped if we errored or were cancelled.
        if (!writeError && !txAudioCancelled && isTransmitting
                && !GeneralVariables.audioOutput32Bit) {
            short[] pad = new short[8];
            int padResult = audioTrack.write(pad, 0, pad.length, AudioTrack.WRITE_BLOCKING);
            if (padResult > 0) framesWritten += padResult;
        }

        // Blocking writes return once data is *buffered*, not played. Wait for the
        // tail (up to bufBytes) to actually drain before releasing, so we don't
        // truncate the end of the message. A STOP cancel skips the wait (the UI
        // thread has already paused+flushed for immediate silence).
        if (!writeError && !txAudioCancelled) {
            while (isTransmitting && !txAudioCancelled) {
                int head = audioTrack.getPlaybackHeadPosition();
                if (head >= framesWritten) break;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        // Worker-thread-owned teardown (drops PTT + releases the track). The UI
        // thread never releases the streaming track — it only flips txAudioCancelled
        // and pauses/flushes — so there's no release race against this write loop.
        afterPlayAudio();
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

        // Pass the full-scale slice; TX volume is applied live as the buffer
        // drains (native scales each sample in its write loop, the UsbRequest
        // fallback scales per chunk), so the slider attenuates the in-progress
        // TX within tens of ms instead of next cycle. applyVolume at unity here
        // just performs the late-start slice.
        float[] unscaled = applyVolume(buffer, skipSamples, playLength, 1.0f);

        GeneralVariables.fileLog(String.format(
                "playViaUsbAudio: calling writeAudio playLength=%d rate=%d",
                playLength, GeneralVariables.audioSampleRate));
        boolean success = usbDev.writeAudio(unscaled, GeneralVariables.audioSampleRate);
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
                    GeneralVariables.currentMode().displayName,
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

    /**
     * Whether Hunt (auto-answer) is armed but still idle — i.e. we are in the CQ baseline
     * state ({@code functionOrder == 6}, target null/"CQ") with no station locked to answer.
     *
     * <p>Hunt arms the sequencer with {@code setActivated(true)} so it can reply, but it
     * answers other stations' CQs and must never call CQ itself. In this idle state the
     * transmit slot stays silent and we just keep listening; once
     * {@link #checkCQMeOrFollowCQMessage} locks a caller (order advances past 6 and the
     * target becomes a real callsign) this returns false and the reply transmits normally.
     * When Hunt is off ({@code autoFollowCQ == false}) this is always false, so a normal
     * user CQ run is unaffected.
     *
     * @param autoFollowCQ  the Hunt flag (GeneralVariables.autoFollowCQ)
     * @param functionOrder the current TX sequence step (6 == CQ baseline)
     * @param toCallsign    the current TX target, if any
     */
    static boolean isHuntListeningIdle(boolean autoFollowCQ, int functionOrder,
                                       TransmitCallsign toCallsign) {
        if (!autoFollowCQ) return false;
        if (functionOrder != 6) return false;
        // "no target" == null or the CQ placeholder (see TransmitCallsign.haveTargetCallsign).
        return toCallsign == null || !toCallsign.haveTargetCallsign();
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
                    && mayAutoCall(GeneralVariables.autoFollowCQ, GeneralVariables.autoCallFollow,
                    GeneralVariables.callsignInFollow(msg.getCallsignFrom()))// Hunt or followed callsign
                    // skip directional CQs aimed at a different DXCC/continent (opt-in)
                    && (!GeneralVariables.respectDirectionalCQ
                    || GeneralVariables.directionalCQIsForMe(msg.callsignTo))
                    && !GeneralVariables.checkQSLCallsign(msg.getCallsignFrom())// not previously contacted successfully
                    && !GeneralVariables.checkIsMyCallsign(msg.callsignFrom)) {// not myself

                GeneralVariables.fileLog("QSO: auto-follow CQ from " + msg.getCallsignFrom()
                        + " (Hunt=" + GeneralVariables.autoFollowCQ + ")");
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
                    GeneralVariables.currentMode().displayName,
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
            GeneralVariables.fileLog("QSO: skip cycle, newest decode in own slot " + sequential);
            return;
        }
        ArrayList<Ft8Message> messages = new ArrayList<>(msgList);// prevent thread conflicts

        if (GeneralVariables.houndMode) {// DXpedition Hound: dedicated QSO handler
            handleHoundCycle(messages);
            return;
        }

        int newOrder = checkFunctionOrdFromMessages(messages);// check reply message sequence from the other party; -1 means not received
        // Per-cycle spine of the QSO trace: current state, what (if anything) the
        // other party sent us this cycle, who we're working, and the no-reply count.
        GeneralVariables.fileLog(String.format("QSO: cycle slot=%d order=%d newOrder=%d to=%s noReply=%d/%d",
                sequential, functionOrder, newOrder,
                toCallsign != null ? toCallsign.callsign : "null",
                GeneralVariables.noReplyCount, GeneralVariables.noReplyLimit));
        if (newOrder != -1) {// if there is a message sequence, reply received; reset error counter
            GeneralVariables.noReplyCount = 0;
        }

        // update the QSO list; if not already recorded, save it
        updateQSlRecordList(newOrder, toCallsign);

        // FT8 protocol: when we receive RR73/RRR (order 4), always reply with
        // 73 (order 5) before completing the QSO. This handler fires before
        // the completion check so that we never skip the 73 reply.
        if (newOrder == 4) {
            GeneralVariables.fileLog("QSO: rx RR73 from "
                    + (toCallsign != null ? toCallsign.callsign : "?") + " -> reply 73");
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

                || (functionOrder == 4 && (GeneralVariables.noReplyCount > RR73_GIVEUP_CYCLES)
                && (GeneralVariables.noReplyLimit == 0))// when no-reply is "ignore" and I am at RR73 (4): the QSO is already logged, so reset after a few unacknowledged RR73s instead of holding the run frequency for minutes

        ) {
            GeneralVariables.fileLog("QSO: complete with "
                    + (toCallsign != null ? toCallsign.callsign : "?")
                    + " (order=" + functionOrder + " newOrder=" + newOrder + ") -> reset to CQ");
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

            GeneralVariables.fileLog("QSO: advance order " + functionOrder + "->" + (newOrder + 1)
                    + " with " + (toCallsign != null ? toCallsign.callsign : "?"));
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
        GeneralVariables.fileLog(String.format("QSO: no-reply %d (order=%d to=%s)",
                GeneralVariables.noReplyCount, functionOrder,
                toCallsign != null ? toCallsign.callsign : "?"));

        // Give up the current target and fall back to CQ / next caller when:
        //  - the user-configured no-reply limit is hit (noReplyLimit > 0), OR
        //  - the limit is "ignore" (0) but we're calling a station (order 1-3)
        //    that never answers — a hard failsafe so we don't transmit at a
        //    no-reply station forever. (Order 4/RR73 has its own cap in the
        //    completion check above; order 5/73 already completes there.)
        boolean limitHit = (GeneralVariables.noReplyLimit > 0)
                && (GeneralVariables.noReplyCount > GeneralVariables.noReplyLimit);
        boolean failsafeHit = (GeneralVariables.noReplyLimit == 0)
                && (functionOrder >= 1 && functionOrder <= 3)
                && (GeneralVariables.noReplyCount > NO_REPLY_GIVEUP_CYCLES);
        if (limitHit || failsafeHit) {
            GeneralVariables.fileLog("QSO: give up calling " + toCallsign.callsign
                    + " (order=" + functionOrder + ", "
                    + (failsafeHit ? "failsafe" : "limit") + ") -> CQ/next");
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

    // ==================== FT8 DXpedition Hound ====================

    /**
     * Begin DXpedition Hound operation: call the Fox high (1000-4000 Hz) and let
     * {@link #handleHoundCycle} auto-QSY and sequence the QSO. The caller sets
     * {@code GeneralVariables.houndMode = true} first.
     *
     * @param foxCall    the Fox's base callsign
     * @param callFreqHz initial Hound TX audio frequency (1000-4000 Hz)
     */
    public void startHound(String foxCall, float callFreqHz) {
        int i3 = GenerateFT8.checkI3ByCallsign(GeneralVariables.myCallsign);
        // Seed the Fox callsign's hashes. DXpedition combo messages carry the Fox
        // only as a 10-bit hash, so without this the combo renders "<...>" in the
        // UI and handleHoundCycle can't tell whose combo it is. (No-op for a
        // compound Fox whose combo hashes its full call while the user enters the
        // base call — handleHoundCycle degrades gracefully in that case.)
        Ft8Message.hashList.addHash(FT8Package.getHash22(foxCall), foxCall);
        Ft8Message.hashList.addHash(FT8Package.getHash12(foxCall), foxCall);
        Ft8Message.hashList.addHash(FT8Package.getHash10(foxCall), foxCall);
        // Fox transmits in the even/1st slot (sequential 0); setTransmit derives
        // our slot as (fox.sequential + 1) % 2 = 1 (odd), which is where Hounds
        // are required to transmit.
        resetTargetReport();
        setTransmit(new TransmitCallsign(i3, 0, foxCall, callFreqHz, 0, 0), 1, "");
        setBaseFrequency(callFreqHz);// call Fox at the chosen high frequency
        setActivated(true);
        GeneralVariables.fileLog("HOUND: start, fox=" + foxCall
                + " callFreq=" + Math.round(callFreqHz) + "Hz slot=" + sequential);
    }

    /**
     * DXpedition Hound per-cycle handler. Reacts to the Fox's reply to us:
     * <ul>
     *   <li>RR73 to me (combo where I'm the acknowledged call, or an explicit
     *       standard RR73 from Fox) -&gt; log + complete.</li>
     *   <li>Report/invite to me (combo where I'm the invited second call, or a
     *       standard "&lt;me&gt; &lt;fox&gt; -rpt") -&gt; QSY to the frequency Fox
     *       called me on and reply "&lt;fox&gt; &lt;me&gt; R-rpt".</li>
     *   <li>Otherwise keep calling at the current order (grid-call or R+rpt).</li>
     * </ul>
     */
    private void handleHoundCycle(ArrayList<Ft8Message> messages) {
        if (toCallsign == null || !toCallsign.haveTargetCallsign()) return;
        String fox = toCallsign.callsign;

        for (int i = messages.size() - 1; i >= 0; i--) {
            Ft8Message msg = messages.get(i);
            if (msg.getSequence() == sequential) continue;// our own slot
            if (msg.band != GeneralVariables.band) continue;

            boolean combo = (msg.i3 == 0 && msg.n3 == 1);// Fox DXpedition combo
            boolean toMe = GeneralVariables.checkIsMyCallsign(msg.getCallsignTo());

            // A combo names the Fox only by a 10-bit hash. If that hash resolves
            // to a known callsign that isn't our Fox, it belongs to a different
            // Fox's pileup — skip it so we don't QSY/log against the wrong station.
            if (combo && !comboFromOurFox(msg, fox)) continue;

            // 1) RR73 to me => QSO complete & logged. Combo: I'm the acknowledged
            //    (first) call. Standard: an explicit RR73 addressed to me by Fox.
            if ((combo && toMe)
                    || (msg.i3 == 1 && toMe
                        && checkCallsignIsCallTo(msg.getCallsignFrom(), fox)
                        && GeneralVariables.checkFun4(msg.extraInfo))) {
                GeneralVariables.fileLog("HOUND: RR73 from " + fox + " -> complete");
                houndComplete();
                return;
            }

            // 2) Fox reported / invited me => reply R+rpt at Fox's frequency.
            //    Combo: I'm the invited second call (dx_call_to2), report = msg.report.
            //    Standard: "<me> <fox> -rpt" or "<me> <fox> R-rpt".
            String invited = msg.dx_call_to2 == null ? ""
                    : msg.dx_call_to2.replace("<", "").replace(">", "");
            boolean invitedByCombo = combo && GeneralVariables.checkIsMyCallsign(invited);
            boolean reportStd = msg.i3 == 1 && toMe
                    && checkCallsignIsCallTo(msg.getCallsignFrom(), fox)
                    && (GeneralVariables.checkFun2(msg.extraInfo)
                        || GeneralVariables.checkFun3(msg.extraInfo));
            if (invitedByCombo || reportStd) {
                int rpt = invitedByCombo ? msg.report : getReportFromExtraInfo(msg.extraInfo);
                if (rpt != -100) {
                    receivedReport = rpt;
                    receiveTargetReport = rpt;
                }
                toCallsign.snr = msg.snr;// Fox's SNR as I hear it -> goes in my R+rpt
                setBaseFrequency(msg.freq_hz);// auto-QSY to where Fox called me
                functionOrder = 3;// "<fox> <me> R-rpt"
                generateFun();
                setCurrentFunctionOrder(functionOrder);
                mutableFunctionOrder.postValue(functionOrder);
                GeneralVariables.fileLog(String.format(
                        "HOUND: report from %s rpt=%d -> QSY %.0fHz, send R%s",
                        fox, rpt, msg.freq_hz, toCallsign.getSnr()));
                return;
            }
        }
        // Nothing relevant this cycle: keep calling at the current order.
    }

    /** Hound QSO complete: log it, fire the celebration, and stop transmitting. */
    private void houndComplete() {
        if (toCallsign == null) return;
        doComplete();// saves the QSL record + posts mutableQsoCompletedAt
        setActivated(false);// worked the Fox; stop calling
        GeneralVariables.fileLog("HOUND: QSO logged with " + toCallsign.callsign);
    }

    /**
     * Whether a DXpedition combo can be attributed to our Fox. The combo names
     * the Fox only by a 10-bit hash; if that hash resolves to a known callsign
     * that doesn't match our Fox (base or compound), it's a different Fox's combo.
     * Returns true when the hash is unknown/unresolved, so we never reject a valid
     * QSO just because the Fox's (possibly compound) call hasn't been hashed yet.
     */
    private boolean comboFromOurFox(Ft8Message msg, String fox) {
        String resolved = Ft8Message.hashList
                .getCallsign(new long[]{msg.callFromHash10})
                .replace("<", "").replace(">", "");
        if (resolved.isEmpty() || resolved.equals("...")) return true;// unknown -> allow
        return resolved.equals(fox) || resolved.contains(fox) || fox.contains(resolved);
    }

    // ==================== End FT8 DXpedition Hound ====================

    /**
     * Whether we're allowed to auto-call a station that's calling CQ, given the auto-call
     * settings. Hunt ({@code autoFollowCQ}) answers any CQ; with Hunt off, auto-call-follow
     * answers only CQs from {@code isFollowed} callsigns; with both off we never auto-call.
     * Shared rule so the give-up fallback ({@link #getNewTargetCallsign}) and the live
     * auto-answer scan ({@link #checkCQMeOrFollowCQMessage}) agree on who we'll call.
     */
    static boolean mayAutoCall(boolean autoFollowCQ, boolean autoCallFollow, boolean isFollowed) {
        if (autoFollowCQ) return true;
        return autoCallFollow && isFollowed;
    }

    /**
     * Check watch list for active CQ messages that are not my current target callsign.
     *
     * @param messages watched message list
     * @return true if new target found, false otherwise
     */
    public boolean getNewTargetCallsign(ArrayList<Ft8Message> messages) {
        if (toCallsign == null) return false;
        // Only auto-pick a fresh CQ target when an auto-call mode is on. With Hunt
        // (autoFollowCQ) and auto-call-follow both off, a manually-started QSO that gets no
        // reply must fall back to idle/CQ — it must NOT start answering some other station's
        // CQ. (This is the give-up fallback after the no-reply limit; it previously chased
        // any CQ regardless of the Hunt toggle.)
        if (!GeneralVariables.autoFollowCQ && !GeneralVariables.autoCallFollow) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Ft8Message ft8Message = messages.get(i);
            if (ft8Message.band != GeneralVariables.band) {//ignore messages not on the same band
                continue;
            }
            // not CQ, ignore
            if (!ft8Message.checkIsCQ()) {
                continue;
            }
            // With Hunt off but auto-call-follow on, only auto-call *followed* callsigns —
            // mirror checkCQMeOrFollowCQMessage so the two paths agree on who we'll call.
            if (!mayAutoCall(GeneralVariables.autoFollowCQ, GeneralVariables.autoCallFollow,
                    GeneralVariables.callsignInFollow(ft8Message.getCallsignFrom()))) {
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
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_lockout_toast));
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

            // Abort the chunked MODE_STREAM AudioTrack playback. We must NOT release
            // the track here: the transmit worker thread may be blocked in a
            // WRITE_BLOCKING call, and releasing it underneath would race/crash.
            // Instead flip the cancel flag (the write loop checks it each chunk) and
            // pause()+flush() for immediate silence. flush() also discards the queued
            // buffer so a blocked write returns at once instead of deadlocking on a
            // full, paused buffer. The worker then breaks out and its afterPlayAudio()
            // does the actual stop()/release() + PTT drop.
            txAudioCancelled = true;
            AudioTrack t = audioTrack;
            if (t != null) {
                try {
                    if (t.getState() != AudioTrack.STATE_UNINITIALIZED
                            && t.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                        t.pause();
                        t.flush();
                    }
                } catch (IllegalStateException ignored) {
                    // Worker already released the track between our read and here.
                }
            }
            // Clear the "currently transmitting" message on TX end. It's only shown while
            // transmitting (banner), and clearing it means the next transmission's rising
            // edge starts from empty rather than the previous over's text — so the mini-log
            // logs each transmission's REAL message instead of, on a new-and-different over,
            // the stale prior one. (mutableTransmittingMessage is posted fresh at TX start.)
            mutableTransmittingMessage.postValue("");
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
     * Arm the sequencer for Hunt: enter the CQ baseline and clear the caller queue, but do
     * NOT set {@code pendingUserCQ}. Unlike {@link #userResetToCQ()} (a user-initiated CQ,
     * which deliberately skips one cycle so the CQ transmits cleanly first), Hunt should be
     * able to lock onto and answer a CQ on the very next decode cycle, so it must not
     * suppress the first {@code checkCQMeOrFollowCQMessage} pass.
     */
    public void armForHunt() {
        resetToCQ();
        clearCallerQueue();
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
                GeneralVariables.fileLog("QSO: enqueue caller " + callsign
                        + " (queue size " + callerQueue.size() + ")");
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
                GeneralVariables.fileLog("QSO: dequeue caller " + caller.callsign
                        + " (" + callerQueue.size() + " left in queue)");
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

            // Compute how late we are *vs. when the audio should start*, and only
            // clip leading audio if we'd overrun the cycle otherwise. The numbers
            // are per-mode (see ModeProfile): the slot length and the audio length.
            //
            // FT8 = 79 GFSK symbols at 0.16s each = 12.64s of audio in a 15s slot,
            // so the slack (last moment we can start without overrunning) is
            // 15.00 - 12.64 = 2.36s. FT4 = 105 symbols at 0.048s = 5.04s in a 7.5s
            // slot, slack 2.46s. Any start within the slack fits without clipping.
            //
            // The naive `msLate = position % slotMillis` would treat every
            // millisecond past the cycle boundary as lateness — so a normal
            // on-time TX that fires ~500-800ms into the cycle (totally fine)
            // would chop that many ms off the *start* of the audio buffer, which
            // is exactly where the leading Costas sync array lives. Receivers
            // couldn't lock, and the signal came across as audible-but-undecodable.
            //
            // New behavior: skip only the excess past the slack. On-time and
            // mildly-late TXs send the full waveform; only genuinely late starts
            // begin clipping leading audio.
            ModeProfile mode = GeneralVariables.currentMode();
            int msIntoCycle = (int) (UtcTimer.getSystemTime() % mode.slotMillis);
            int msMaxStart = mode.audioSlackMillis; // last moment we can start without overrunning
            int msLate = msIntoCycle - msMaxStart;
            if (msLate < 0) msLate = 0;
            if (msLate >= mode.slotMillis) msLate = mode.slotMillis - 1;
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
