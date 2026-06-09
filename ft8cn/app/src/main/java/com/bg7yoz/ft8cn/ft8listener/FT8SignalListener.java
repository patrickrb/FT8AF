package com.bg7yoz.ft8cn.ft8listener;
/**
 * Class for listening to audio. Listening cycles are controlled by the UtcTimer clock,
 * and audio data is read through the OnWaveDataListener interface.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.ModeProfile;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;
import com.bg7yoz.ft8cn.timer.OnUtcTimer;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.wave.OnGetVoiceDataDone;
import com.bg7yoz.ft8cn.wave.WaveFileReader;
import com.bg7yoz.ft8cn.wave.WaveFileWriter;

import java.util.ArrayList;

public class FT8SignalListener {
    private static final String TAG = "FT8SignalListener";
    // Not final: rebuildTimer() recreates it when the operating mode (and thus cycle
    // length) changes. See rebuildTimer().
    private UtcTimer utcTimer;
    //private HamRecorder hamRecorder;
    private final OnFt8Listen onFt8Listen;// event triggered when listening starts and decoding finishes
    //private long band;
    public MutableLiveData<Long> decodeTimeSec = new MutableLiveData<>();// decode duration
    public long timeSec=0;// decode duration

    private OnWaveDataListener onWaveDataListener;


    private DatabaseOpr db;

    private final A91List a91List = new A91List();// a91 list


    static {
        try {
            System.loadLibrary("ft8cn");
            // The FT2 decoder JNI entry points (InitDecoderFt2 etc.) live in ft8af_usb (our
            // from-source ft8_lib build), not the prebuilt ft8cn. Load it so they resolve when
            // the user operates in FT2 mode. Idempotent if GenerateFT8 already loaded it.
            System.loadLibrary("ft8af_usb");
        } catch (UnsatisfiedLinkError e) {
            // Best-effort load (mirrors GenerateFT8): JVM unit tests don't have the native
            // libs on java.library.path. The native decode methods throw if actually invoked
            // without the library; class init must not crash.
            Log.w(TAG, "native library not loaded: " + e.getMessage());
        }
    }

    public interface OnWaveDataListener {
        void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone);
    }

    public FT8SignalListener(DatabaseOpr db, OnFt8Listen onFt8Listen) {
        //this.hamRecorder = hamRecorder;
        this.onFt8Listen = onFt8Listen;
        this.db = db;

        // Create action trigger, synchronized with UTC time, on the current mode's cycle
        // (FT8 = 15s/150, FT4 = 7.5s/75). DoOnSecTimer fires at the start of each cycle.
        utcTimer = new UtcTimer(GeneralVariables.currentMode().slotTenths, false, makeTimerCallback());
    }

    /** The cycle-trigger callback, shared between the constructor and {@link #rebuildTimer}. */
    private OnUtcTimer makeTimerCallback() {
        return new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {// clock info when not triggered
            }

            @Override
            public void doOnSecTimer(long utc) {// triggered at the specified interval
                Log.d(TAG, String.format("Recording triggered, %d", utc));
                runRecorde(utc);
            }
        };
    }

    /**
     * Recreate the cycle timer for a new operating mode. {@link UtcTimer}'s period is fixed
     * at construction, so switching from FT8 (15s) to FT4 (7.5s) means tearing down the old
     * timer and building a fresh one. Preserves the listening state.
     */
    public void rebuildTimer(ModeProfile mode) {
        boolean wasListening = utcTimer.isRunning();
        utcTimer.delete();
        utcTimer = new UtcTimer(mode.slotTenths, false, makeTimerCallback());
        if (wasListening) {
            utcTimer.start();
        }
    }

    public void startListen() {
        utcTimer.start();
    }

    public void stopListen() {
        utcTimer.stop();
    }

    public boolean isListening() {
        return utcTimer.isRunning();
    }

    /**
     * Get the current time offset, including both the overall clock offset and this instance's offset.
     *
     * @return int
     */
    public int time_Offset() {
        return utcTimer.getTime_sec() + UtcTimer.delay;
    }

    /**
     * Record audio. Recording runs in the background using multiple threads and automatically
     * generates a temporary WAV file. There are two callbacks: one for when recording starts
     * and one for when it ends. When recording ends, the decoder is activated.
     *
     * @param utc the current UTC time for decoding
     */
    private void runRecorde(long utc) {
        Log.d(TAG, "Starting recording...");

        if (onWaveDataListener != null) {
            // Fast turnaround: collect a shorter window so decoding finishes (and CQ
            // decodes appear) ~1s before the cycle boundary, leaving time to answer
            // on the next slot. Off = full slot window (max sensitivity).
            ModeProfile mode = GeneralVariables.currentMode();
            int recordMs = GeneralVariables.earlyDecode
                    ? mode.earlyDecodeMillis
                    : mode.slotMillis;
            onWaveDataListener.getVoiceData(recordMs, true
                    , new OnGetVoiceDataDone() {
                        @Override
                        public void onGetDone(float[] data) {
                            Log.d(TAG, String.format("Starting decode...###, data length: %d",data.length));
                            decodeFt8(utc, data);
                        }
                    });
        }
    }

    public void decodeFt8(long utc, float[] voiceData) {

        // Test code below -------------------------
//        String fileName = getCacheFileName("test_01.wav");
//        Log.e(TAG, "onClick: fileName:" + fileName);
//        WaveFileReader reader = new WaveFileReader(fileName);
//        int data[][] = reader.getData();
        //----------------------------------------------------------

        new Thread(new Runnable() {
            @Override
            public void run() {
                long time = System.currentTimeMillis();
                if (onFt8Listen != null) {
                    onFt8Listen.beforeListen(utc);
                }

//                float[] tempData = ints2floats(data);


                /// Read audio data and perform preprocessing
                // Note: decoding must complete within one cycle, otherwise a new decode cycle will begin
                // FT2 receive uses the from-source decoder (ft8af_usb); FT8/FT4 use the prebuilt.
                // All the native ops are dispatched through the *Decode helpers on this flag.
                final boolean ft2 = GeneralVariables.currentMode().usesFt2Decoder();
                long ft8Decoder = initDecoder(utc, voiceData.length, ft2);
//                        , tempData.length, true);
                pressFloatDecode(voiceData, ft8Decoder, ft2);// load audio data
//                DecoderMonitorPressFloat(tempData, ft8Decoder);// load audio data


                ArrayList<Ft8Message> allMsg = new ArrayList<>();
//                ArrayList<Ft8Message> msgs = runDecode(utc, voiceData,false);
                ArrayList<Ft8Message> msgs = runDecode(ft8Decoder, utc, false, ft2);
                addMsgToList(allMsg, msgs);
                timeSec = System.currentTimeMillis() - time;
                decodeTimeSec.postValue(timeSec);// decode elapsed time
                if (onFt8Listen != null) {
                    onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, false);
                }


                if (GeneralVariables.deepDecodeMode) {// enter deep decode mode
                    //float[] newSignal=tempData;
                    msgs = runDecode(ft8Decoder, utc, true, ft2);
                    addMsgToList(allMsg, msgs);
                    timeSec = System.currentTimeMillis() - time;
                    decodeTimeSec.postValue(timeSec);// decode elapsed time
                    if (onFt8Listen != null) {
                        onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, true);
                    }

                    do {
                        if (timeSec > FT8Common.DEEP_DECODE_TIMEOUT) break;// timeout check: if exceeding a certain time (7 sec), skip signal subtraction
                        // subtract decoded signals
                        subtractDecode(ft8Decoder, a91List, ft2);

                        // perform another decode pass
                        msgs = runDecode(ft8Decoder, utc, true, ft2);
                        addMsgToList(allMsg, msgs);
                        timeSec = System.currentTimeMillis() - time;
                        decodeTimeSec.postValue(timeSec);// decode elapsed time
                        if (onFt8Listen != null) {
                            onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, true);
                        }

                    } while (msgs.size() > 0 );

                }
                // Moved to finalize() method
                deleteDecoder(ft8Decoder, ft2);

                Log.d(TAG, String.format("Decode took: %d ms", System.currentTimeMillis() - time));

            }
        }).start();
    }


    private ArrayList<Ft8Message> runDecode(long ft8Decoder, long utc, boolean isDeep, boolean ft2) {
        ArrayList<Ft8Message> ft8Messages = new ArrayList<>();
        Ft8Message ft8Message = new Ft8Message(GeneralVariables.operatingMode);

        ft8Message.utcTime = utc;
        ft8Message.band = GeneralVariables.band;
        a91List.clear();

        setDeepDecode(ft8Decoder, isDeep, ft2);// set iteration count; isDeep==true increases iterations

        int num_candidates = findSyncDecode(ft8Decoder, ft2);// up to 120 candidates
        //long startTime = System.currentTimeMillis();
        for (int idx = 0; idx < num_candidates; ++idx) {
            //todo should add timeout calculation
            try {// protect against decode failure
                if (analysisDecode(idx, ft8Decoder, ft8Message, ft2)) {

                    if (ft8Message.isValid) {
                        Ft8Message msg = new Ft8Message(ft8Message);// using msg here because some hashed callsigns will replace <...>
                        byte[] a91 = getA91Decode(ft8Decoder, ft2);
                        a91List.add(a91, ft8Message.freq_hz, ft8Message.time_sec);

                        if (checkMessageSame(ft8Messages, msg)) {
                            continue;
                        }

                        msg.isWeakSignal = isDeep;// whether it is a weak signal
                        ft8Messages.add(msg);

                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "run: " + e.getMessage());
            }

        }


        return ft8Messages;
    }

    // ---- Decoder backend dispatch -----------------------------------------------------
    // FT2 receive runs on the from-source decoder (ft8af_usb, *Ft2 entry points); FT8/FT4
    // run on the prebuilt (ft8cn). The decode loop above stays backend-agnostic by routing
    // every native op through these helpers on the per-cycle `ft2` flag
    // (= ModeProfile.usesFt2Decoder()).

    private long initDecoder(long utc, int numSamples, boolean ft2) {
        if (ft2) {
            return InitDecoderFt2(utc, FT8Common.SAMPLE_RATE, numSamples);
        }
        return InitDecoder(utc, FT8Common.SAMPLE_RATE, numSamples,
                GeneralVariables.currentMode().isFt8);
    }

    private void pressFloatDecode(float[] data, long decoder, boolean ft2) {
        if (ft2) DecoderFt2MonitorPressFloat(data, decoder);
        else DecoderMonitorPressFloat(data, decoder);
    }

    private void setDeepDecode(long decoder, boolean isDeep, boolean ft2) {
        if (ft2) setDecodeModeFt2(decoder, isDeep);
        else setDecodeMode(decoder, isDeep);
    }

    private int findSyncDecode(long decoder, boolean ft2) {
        return ft2 ? DecoderFt2FindSync(decoder) : DecoderFt8FindSync(decoder);
    }

    private boolean analysisDecode(int idx, long decoder, Ft8Message msg, boolean ft2) {
        return ft2 ? DecoderFt2Analysis(idx, decoder, msg)
                : DecoderFt8Analysis(idx, decoder, msg);
    }

    private byte[] getA91Decode(long decoder, boolean ft2) {
        return ft2 ? DecoderFt2GetA91(decoder) : DecoderGetA91(decoder);
    }

    private void deleteDecoder(long decoder, boolean ft2) {
        if (ft2) DeleteDecoderFt2(decoder);
        else DeleteDecoder(decoder);
    }

    private void subtractDecode(long decoder, A91List list, boolean ft2) {
        if (ft2) ReBuildSignal.subtractSignalFt2(decoder, list);
        else ReBuildSignal.subtractSignal(decoder, list);
    }

    /**
     * Calculate the average time offset value.
     *
     * @param messages message list
     * @return offset value
     */
    private float averageOffset(ArrayList<Ft8Message> messages) {
        if (messages.size() == 0) return 0f;
        float dt = 0;
        //int dtAverage = 0;
        for (Ft8Message msg : messages) {
            dt += msg.time_sec;
        }
        return dt / messages.size();
    }

    /**
     * Add messages to the list.
     *
     * @param allMsg message list
     * @param newMsg new messages
     */
    private void addMsgToList(ArrayList<Ft8Message> allMsg, ArrayList<Ft8Message> newMsg) {
        for (int i = newMsg.size() - 1; i >= 0; i--) {
            if (checkMessageSame(allMsg, newMsg.get(i))) {
                newMsg.remove(i);
            } else {
                allMsg.add(newMsg.get(i));
            }
        }
    }

    /**
     * Check if the same message content already exists in the message list.
     *
     * @param ft8Messages message list
     * @param ft8Message  message
     * @return boolean
     */
    private boolean checkMessageSame(ArrayList<Ft8Message> ft8Messages, Ft8Message ft8Message) {
        for (Ft8Message msg : ft8Messages) {
            if (msg.getMessageText().equals(ft8Message.getMessageText())) {
                if (msg.snr < ft8Message.snr) {
                    msg.snr = ft8Message.snr;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    protected void finalize() throws Throwable {
        //DeleteDecoder(ft8Decoder);
        super.finalize();
    }

    public OnWaveDataListener getOnWaveDataListener() {
        return onWaveDataListener;
    }

    public void setOnWaveDataListener(OnWaveDataListener onWaveDataListener) {
        this.onWaveDataListener = onWaveDataListener;
    }


    public String getCacheFileName(String fileName) {
        return GeneralVariables.getMainContext().getCacheDir() + "/" + fileName;
    }

    public float[] ints2floats(int data[][]) {
        float temp[] = new float[data[0].length];
        for (int i = 0; i < data[0].length; i++) {
            temp[i] = data[0][i] / 32768.0f;
        }
        return temp;
    }

    public int[] floats2ints(float data[]) {
        int temp[] = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            temp[i] = (int) (data[i] * 32767.0f);
        }
        return temp;
    }

    /**
     * Decode step 1: initialize the decoder and get the decoder address.
     *
     * @param utcTime     UTC time
     * @param sampleRat   sample rate, 12000
     * @param num_samples length of buffer data
     * @param isFt8       whether it is an FT8 signal
     * @return the decoder address
     */
    public native long InitDecoder(long utcTime, int sampleRat, int num_samples, boolean isFt8);

    /**
     * Decode step 2: read WAV data.
     *
     * @param buffer  WAV data buffer
     * @param decoder decoder data address
     */
    public native void DecoderMonitorPress(int[] buffer, long decoder);

    public native void DecoderMonitorPressFloat(float[] buffer, long decoder);


    /**
     * Decode step 3: synchronize data.
     *
     * @param decoder decoder address
     * @return number of candidate signals
     */
    public native int DecoderFt8FindSync(long decoder);

    /**
     * Decode step 4: analyze and extract messages (must be called in a loop).
     *
     * @param idx        index of the candidate signal
     * @param decoder    decoder address
     * @param ft8Message the decoded message
     * @return boolean
     */
    public native boolean DecoderFt8Analysis(int idx, long decoder, Ft8Message ft8Message);

    /**
     * Final decode step: delete the decoder data.
     *
     * @param decoder decoder data address
     */
    public native void DeleteDecoder(long decoder);

    public native void DecoderFt8Reset(long decoder, long utcTime, int num_samples);

    public native byte[] DecoderGetA91(long decoder);// get the a91 data of the current message

    public native void setDecodeMode(long decoder, boolean isDeep);// set decode mode: isDeep=true for multi-iteration, =false for fast iteration

    // ---- FT2 decoder (from-source ft8_lib in libft8af_usb.so) -------------------------
    // Distinct entry points so they never collide with the prebuilt's InitDecoder/etc.
    // Protocol is fixed to FT2 (0.024s symbol period); see cpp/ft8cn_glue/ft2_decode_jni.cpp.
    public native long InitDecoderFt2(long utcTime, int sampleRate, int num_samples);

    public native void DecoderFt2MonitorPressFloat(float[] buffer, long decoder);

    public native int DecoderFt2FindSync(long decoder);

    public native boolean DecoderFt2Analysis(int idx, long decoder, Ft8Message ft8Message);

    public native byte[] DecoderFt2GetA91(long decoder);

    public native void DeleteDecoderFt2(long decoder);

    public native void setDecodeModeFt2(long decoder, boolean isDeep);
}
