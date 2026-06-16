package com.k1af.ft8af.wave;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
/**
 * Recording class. Implements audio recording via the AudioRecord object.
 * HamRecorder retrieves recording data through the listener class GetVoiceData. The HamRecorder instance has a listener list onGetVoiceList.
 * When recording data is available, HamRecorder triggers the OnReceiveData callback for each listener in the list.
 * The purpose of this class is to prevent FT8 recording timing issues caused by recording startup delays,
 * which could lead to overlapping AudioRecord instances or recordings shorter than a full cycle (15 seconds).
 * <p>
 * @author BG7YOZ
 * @date 2022-05-31
 */

public class HamRecorder {
    private static final String TAG = "HamRecorder";
    //private int bufferSize = 0;//minimum buffer size
    private static final int sampleRateInHz = 12000;//sampling rate
    private static final int channelConfig = AudioFormat.CHANNEL_IN_MONO; //mono
    //private static final int audioFormat = AudioFormat.ENCODING_PCM_16BIT; //quantization bit depth
    private static final int audioFormat = AudioFormat.ENCODING_PCM_FLOAT; //quantization bit depth

    //private AudioRecord audioRecord = null;//AudioRecord object
    private volatile boolean isRunning = false;//whether currently in recording state

    private final ArrayList<VoiceDataMonitor> voiceDataMonitorList = new ArrayList<>();//listener callback list, data is retrieved in listener callbacks
    private OnVoiceMonitorChanged onVoiceMonitorChanged=null;

    private boolean isMicRecord=true;
    private MicRecorder micRecorder=new MicRecorder();


    public HamRecorder(OnVoiceMonitorChanged onVoiceMonitorChanged){
        this.onVoiceMonitorChanged=onVoiceMonitorChanged;
    }


    public void setDataFromMic(){
        isMicRecord=true;
        startRecord();
    }
    public void setDataFromLan(){
        isMicRecord=false;
        micRecorder.stopRecord();
    }

    /**
     * Actions to perform when audio data is received
     * @param bufferLen length of the data
     * @param buffer data buffer
     */
    public void doOnWaveDataReceived(int bufferLen,float[] buffer){
        if (!isRunning) return;
        for (int i = 0; i < voiceDataMonitorList.size(); i++) {
            //invoke each listener's callback, providing data to the callback function
            if (voiceDataMonitorList.get(i)!=null) {
                voiceDataMonitorList.get(i).onHamRecord.OnReceiveData(buffer, bufferLen);
            }
        }

        //doDataMonitorChanged();
    }


    /**
     * Whether currently in recording state
     *
     * @return boolean, whether currently in recording state
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Start recording. This method keeps the device in a continuous recording state.
     * Recording data is retrieved through the listener class GetVoiceData.
     * After the recording object reads data (audioRecord.read), it invokes the OnReceiveData callback for all listeners in the list.
     * The recording state is tracked in isRecording.
     */
    @SuppressLint("MissingPermission")
    public void startRecord() {
        if (isMicRecord){//if using MIC for audio capture
            micRecorder.start();
            micRecorder.setOnDataListener(new MicRecorder.OnDataListener() {
                @Override
                public void onDataReceived(float[] data, int len) {
                    doOnWaveDataReceived(len,data);
                }
            });
        }
            isRunning=true;

    }

    private void doDataMonitorChanged(){
        if (onVoiceMonitorChanged!=null){
            onVoiceMonitorChanged.onMonitorChanged(voiceDataMonitorList.size());
        }
    }
    /**
     * Delete a data monitor
     * @param monitor the data monitor
     */
    public void deleteVoiceDataMonitor(VoiceDataMonitor monitor) {
        voiceDataMonitorList.remove(monitor);
        doDataMonitorChanged();
    }

    /**
     * Get the number of monitors
     * @return the count
     */
    public int getVoiceMonitorCount(){
        return voiceDataMonitorList.size();
    }

    /**
     * Get the list of monitors
     * @return monitor list
     */
    public ArrayList<VoiceDataMonitor> getVoiceDataMonitors(){
        return this.voiceDataMonitorList;
    }

    /**
     * Stop recording. When recording stops, all monitors in the listener list are removed.
     */
    public void stopRecord() {
        micRecorder.stopRecord();
        isRunning = false;
    }

    /**
     * Reinitialize the mic recorder to pick up newly connected USB audio devices.
     * Re-wires the data listener after reinit.
     */
    public void reinitializeMicRecorder() {
        micRecorder.reinitialize();
        if (isMicRecord) {
            micRecorder.setOnDataListener(new MicRecorder.OnDataListener() {
                @Override
                public void onDataReceived(float[] data, int len) {
                    doOnWaveDataReceived(len, data);
                }
            });
        }
    }

    /**
     * Method to retrieve recording data, implemented by adding a data monitor (VoiceDataMonitor).
     * Recording data is provided in the OnGetVoiceDataDone callback, triggered when the recording reaches the specified duration (milliseconds).
     * To get recording data, a monitor object is added to the recorder. Data is collected in the monitor's OnReceiveData callback.
     * When the expected amount of data is reached, the OnGetVoiceDataDone callback is triggered. This callback runs in a separate thread, so be careful with UI handling.
     * There are two monitoring modes: one-shot and looping.
     * One-shot: after data is obtained, the monitor is automatically removed and will not trigger again.
     * Looping: the monitor persists; after data is obtained, the data is reset and enters the next monitoring state. The monitor is only removed when recording stops.
     * duration in milliseconds
     *
     * @param duration         recording data duration (milliseconds)
     * @param afterDoneRemove  whether to remove the monitor after obtaining data; false: loop to continuously obtain recording data
     * @param getVoiceDataDone callback triggered when recording data reaches the specified duration
     */
    public VoiceDataMonitor getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone) {
        if (isRunning) {
            VoiceDataMonitor dataMonitor = new VoiceDataMonitor(duration, this
                    , afterDoneRemove, getVoiceDataDone);
            dataMonitor.voiceDataMonitor = dataMonitor;//used for the monitor to remove itself
            voiceDataMonitorList.add(dataMonitor);
            doDataMonitorChanged();
            return dataMonitor;
        } else {
            return null;
        }
    }

    /**
     * Monitor class for retrieving recording data.
     * This monitor requires setting the recording duration (milliseconds). When the specified duration is reached,
     * an OnGetVoiceDataDone callback is produced, from which the recording data for that duration can be obtained.
     * The monitor can be set as one-shot (afterDoneRemove=true) or looping (afterDoneRemove=false).
     * One-shot: the monitor stops listening after reaching the specified duration, and the recorder removes it.
     * Looping: after reaching the specified duration, it resets and continues monitoring. This mode is convenient for generating waveform table data.
     */
    static class VoiceDataMonitor {
        private final String TAG = "GetVoiceData";
        private final float[] voiceData;//recording data. Size is determined by duration, sampling rate, and bit depth.
        private int dataCount;//counter, current amount of data acquired

        //onHamRecord is the callback triggered when the recorder has data; it fills the voiceData buffer, and when the buffer is full, triggers the OnGetVoiceDataDone callback.
        public OnHamRecord onHamRecord;
        //getVoiceData is the address of this monitor, used to remove this monitor from the recorder's listener list.
        // After constructing GetVoiceData, IMPORTANT!!! this variable must be assigned! Otherwise this monitor cannot be removed.
        public VoiceDataMonitor voiceDataMonitor = null;

        /**
         * Monitor class for retrieving recording data.
         * Constructor for GetVoiceData class. This class is added to the HamRecorder's onGetVoiceList to produce callbacks when recording data is available.
         * The purpose of this class is to allow multiple objects to retrieve data from the recording without conflict.
         *
         * @param duration           duration of recording data to acquire (milliseconds)
         * @param hamRecorder        instance of the recorder class, for operations like removing this monitor
         * @param afterDoneRemove    whether to remove this monitor instance after reaching the recording duration; true: remove, false: do not remove, loop monitoring
         * @param onGetVoiceDataDone callback triggered after reaching the recording duration. To avoid taking too much recording time, this callback runs in a separate thread.
         */
        public VoiceDataMonitor(int duration, HamRecorder hamRecorder, boolean afterDoneRemove
                , OnGetVoiceDataDone onGetVoiceDataDone) {
            //duration in milliseconds
            //host object, for conveniently calling operations to remove this instance from the data acquisition action list

            dataCount = 0;//current amount of data acquired
            //generate data buffer of expected size
            //because it is 16-bit sampling, so byte*2
            //voiceData = new byte[duration * HamRecorder.sampleRateInHz * 2 / 1000];
            voiceData = new float[duration * HamRecorder.sampleRateInHz  / 1000];

            //callback function triggered when recording data is available
            onHamRecord = new OnHamRecord() {
                @Override
                public void OnReceiveData(float[] data, int size) {
                    int remainingSize = size+dataCount-voiceData.length;//if greater than 0, this is the remaining data amount

                    for (int i = 0; (i < size) && (dataCount < voiceData.length); i++) {
                            voiceData[dataCount] = data[i];//copy data from recording buffer to this monitor
                            dataCount++;
                    }

                    if (dataCount >= (voiceData.length)) {//when data amount reaches the required amount, trigger callback
                        onGetVoiceDataDone.onGetDone(voiceData);
                        if (afterDoneRemove) {//if this is a one-shot data acquisition, remove this monitor callback from the recorder's listener list
                            hamRecorder.deleteVoiceDataMonitor(voiceDataMonitor);
                        } else {
                            dataCount = 0;//if looping recording, reset the counter
                            if (remainingSize>0) {//forward remaining data to subsequent events
                                float[] remainingData = new float[remainingSize];
                                System.arraycopy(data, size - remainingSize, remainingData, 0, remainingSize);
                                OnReceiveData(remainingData,remainingSize);
                            }
                        }
                    }
                }
            };

        }

    }

    /**
     * Class method to save data to a file with a temporary filename.
     * @param data the data
     * @return the generated temporary filename
     */
    public static String saveDataToFile(byte[] data) {
        String audioFileName = null;
        File recordingFile;
        try {
            //generate temporary filename
            recordingFile = File.createTempFile("Audio", ".wav", null);
            audioFileName = recordingFile.getPath();

            //data stream file
            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(audioFileName)));
            //write WAV file header
            new WriteWavHeader(data.length, sampleRateInHz, channelConfig, audioFormat).writeHeader(dos);
            for (int i = 0; i < data.length; i++) {
                dos.write(data[i]);
            }
            Log.d(TAG, String.format("File generation complete (%d bytes, %.2f seconds), file: %s", data.length + 44
                    , ((float) data.length / 2 / sampleRateInHz), audioFileName));
            dos.close();//close file stream


        } catch (IOException e) {
            Log.e(TAG, String.format("Error generating temporary file! %s", e.getMessage()));
        }

        return audioFileName;
    }

    /**
     * Convert raw audio data to 16-bit array data.
     * @param buffer raw audio data (8-bit)
     * @return 16-bit int format array
     */
    public static int[] byteDataTo16BitData(byte[] buffer){
        int[] data=new int[buffer.length /2];
        for (int i = 0; i < buffer.length/2; i++) {
            int  res = (buffer[i*2] & 0x000000FF) | (((int) buffer[i*2+1]) << 8);
            data[i]=res;
        }
        return data;
    }

    /**
     * Convert raw audio data to float array data
     * @param bytes raw audio data (float)
     * @return converted float array
     */
    public static float[] getFloatFromBytes(byte[] bytes) {
        float[] floats = new float[bytes.length / 4];
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        for (int i = 0; i < floats.length; i++) {
            try {
                floats[i] = dis.readFloat();
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
        try {
            dis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return floats;
    }
}
