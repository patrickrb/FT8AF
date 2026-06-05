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
    private final UtcTimer utcTimer;
    //private HamRecorder hamRecorder;
    private final OnFt8Listen onFt8Listen;// event triggered when listening starts and decoding finishes
    //private long band;
    public MutableLiveData<Long> decodeTimeSec = new MutableLiveData<>();// decode duration
    public long timeSec=0;// decode duration

    private OnWaveDataListener onWaveDataListener;


    private DatabaseOpr db;

    private final A91List a91List = new A91List();// a91 list


    static {
        System.loadLibrary("ft8cn");
    }

    public interface OnWaveDataListener {
        void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone);
    }

    public FT8SignalListener(DatabaseOpr db, OnFt8Listen onFt8Listen) {
        //this.hamRecorder = hamRecorder;
        this.onFt8Listen = onFt8Listen;
        this.db = db;

        // Create action trigger, synchronized with UTC time, on a 15-second cycle.
        // DoOnSecTimer is the event triggered at the start of each cycle. 150 means 15 seconds.
        utcTimer = new UtcTimer(FT8Common.FT8_SLOT_TIME_M, false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {// clock info when not triggered
            }

            @Override
            public void doOnSecTimer(long utc) {// triggered at the specified interval
                Log.d(TAG, String.format("Recording triggered, %d", utc));
                runRecorde(utc);
            }
        });
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
            // on the next slot. Off = full 15s window (max sensitivity).
            int recordMs = GeneralVariables.earlyDecode
                    ? FT8Common.FT8_EARLY_DECODE_MILLISECOND
                    : FT8Common.FT8_SLOT_TIME_MILLISECOND;
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
                long ft8Decoder = InitDecoder(utc, FT8Common.SAMPLE_RATE
                        , voiceData.length, true);
//                        , tempData.length, true);
                DecoderMonitorPressFloat(voiceData, ft8Decoder);// load audio data
//                DecoderMonitorPressFloat(tempData, ft8Decoder);// load audio data


                ArrayList<Ft8Message> allMsg = new ArrayList<>();
//                ArrayList<Ft8Message> msgs = runDecode(utc, voiceData,false);
                ArrayList<Ft8Message> msgs = runDecode(ft8Decoder, utc, false);
                addMsgToList(allMsg, msgs);
                timeSec = System.currentTimeMillis() - time;
                decodeTimeSec.postValue(timeSec);// decode elapsed time
                if (onFt8Listen != null) {
                    onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, false);
                }


                if (GeneralVariables.deepDecodeMode) {// enter deep decode mode
                    //float[] newSignal=tempData;
                    msgs = runDecode(ft8Decoder, utc, true);
                    addMsgToList(allMsg, msgs);
                    timeSec = System.currentTimeMillis() - time;
                    decodeTimeSec.postValue(timeSec);// decode elapsed time
                    if (onFt8Listen != null) {
                        onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, true);
                    }

                    do {
                        if (timeSec > FT8Common.DEEP_DECODE_TIMEOUT) break;// timeout check: if exceeding a certain time (7 sec), skip signal subtraction
                        // subtract decoded signals
                        ReBuildSignal.subtractSignal(ft8Decoder, a91List);

                        // perform another decode pass
                        msgs = runDecode(ft8Decoder, utc, true);
                        addMsgToList(allMsg, msgs);
                        timeSec = System.currentTimeMillis() - time;
                        decodeTimeSec.postValue(timeSec);// decode elapsed time
                        if (onFt8Listen != null) {
                            onFt8Listen.afterDecode(utc, averageOffset(allMsg), UtcTimer.sequential(utc), msgs, true);
                        }

                    } while (msgs.size() > 0 );

                }
                // Moved to finalize() method
                DeleteDecoder(ft8Decoder);

                Log.d(TAG, String.format("Decode took: %d ms", System.currentTimeMillis() - time));

            }
        }).start();
    }


    private ArrayList<Ft8Message> runDecode(long ft8Decoder, long utc, boolean isDeep) {
        ArrayList<Ft8Message> ft8Messages = new ArrayList<>();
        Ft8Message ft8Message = new Ft8Message(FT8Common.FT8_MODE);

        ft8Message.utcTime = utc;
        ft8Message.band = GeneralVariables.band;
        a91List.clear();

        setDecodeMode(ft8Decoder, isDeep);// set iteration count; isDeep==true increases iterations

        int num_candidates = DecoderFt8FindSync(ft8Decoder);// up to 120 candidates
        //long startTime = System.currentTimeMillis();
        for (int idx = 0; idx < num_candidates; ++idx) {
            //todo should add timeout calculation
            try {// protect against decode failure
                if (DecoderFt8Analysis(idx, ft8Decoder, ft8Message)) {

                    if (ft8Message.isValid) {
                        Ft8Message msg = new Ft8Message(ft8Message);// using msg here because some hashed callsigns will replace <...>
                        byte[] a91 = DecoderGetA91(ft8Decoder);
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
}
