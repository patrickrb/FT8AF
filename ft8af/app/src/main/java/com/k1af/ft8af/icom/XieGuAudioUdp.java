package com.k1af.ft8af.icom;
/**
 * Handle Xiegu audio stream, extends AudioUdp.
 *
 * @author BGY70Z
 * @date 2023-08-26
 */

import android.util.Log;

import com.k1af.ft8af.GeneralVariables;

import java.net.DatagramPacket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class XieGuAudioUdp extends AudioUdp {
    private static final String TAG = "XieGuAudioUdp";

    private final ExecutorService doTXThreadPool = Executors.newCachedThreadPool();
//    private final DoTXAudioRunnable doTXAudioRunnable = new DoTXAudioRunnable(this);


    private final AudioRunnable audioRunnable = new AudioRunnable(this);
    private boolean audioIsRunning = false;
    //private DatagramPacket packet;
    //private byte[] data;

    @Override
    public void sendTxAudioData(float[] audioData) {
        if (audioData == null) return;

        short[] temp = new short[audioData.length];//12000
        //Incoming audio is LPCM, 32-bit float, 12000Hz
        //Need to convert from float to 16-bit int
        for (int i = 0; i < audioData.length; i++) {
            float x = audioData[i];
            if (x > 0.999999f)
                temp[i] = 32767;
            else if (x < -0.999999f)
                temp[i] = -32766;
            else
                temp[i] = (short) (x * 32767.0);
        }

        audioRunnable.setAudioData(temp);
        //doTXAudioRunnable.audioData = temp;
        //doTXThreadPool.execute(doTXAudioRunnable);
    }

    @Override
    public void startTxAudio() {
        if (!audioIsRunning) {
            audioIsRunning = true;
            doTXThreadPool.execute(audioRunnable);

        }
    }


    @Override
    public void stopTXAudio() {
        audioIsRunning = false;
        audioRunnable.stop();
    }


    private static class AudioRunnable implements Runnable {
        private final int partialLen = (int) (IComPacketTypes.AUDIO_SAMPLE_RATE * 0.02);//20ms packet data length
        private final byte[] audioPacket = new byte[partialLen * 2];
        private final byte[] ft8Audio = new byte[15 * IComPacketTypes.AUDIO_SAMPLE_RATE * 2];//15 seconds, sample rate * 2 (16-bit, so double)
        private int index = 0;
        XieGuAudioUdp audioUdp;
        private boolean isRunning = true;

        public AudioRunnable(XieGuAudioUdp audioUdp) {

            this.audioUdp = audioUdp;
            Log.e(TAG, "AudioRunnable: create runnable");
        }

        public void setAudioData(short[] audioData) {
            for (int i = 0; i < audioData.length; i++) {
                System.arraycopy(IComPacketTypes.shortToBigEndian((short)
                                (audioData[i]
                                        * GeneralVariables.volumePercent))//Multiply by volume ratio
                        , 0, ft8Audio, i * 2, 2);
            }
            index = 0;

        }

        @Override
        public void run() {
            while (isRunning) {
                long now = System.currentTimeMillis() - 1;//Get current time
                if (audioUdp.isPttOn) {
                    System.arraycopy(ft8Audio, index, audioPacket, 0, audioPacket.length);
                    index = index + partialLen * 2;
                    if (index >= ft8Audio.length) index = 0;
                }


                audioUdp.sendTrackedPacket(IComPacketTypes.AudioPacket.getTxAudioPacket(audioPacket
                        , (short) 0, audioUdp.localId, audioUdp.remoteId, audioUdp.innerSeq));
                audioUdp.innerSeq++;
                while (isRunning) {
                    if (System.currentTimeMillis() - now >= 21) {//20ms per cycle
                        break;
                    }
                }
            }
        }

        public void stop() {
            isRunning = false;
        }
    }
//
//    private static class DoTXAudioRunnable implements Runnable {
//        XieGuAudioUdp audioUdp;
//        short[] audioData;//Incoming audio is LPCM 16-bit Int, 12000Hz
//
//        public DoTXAudioRunnable(XieGuAudioUdp audioUdp) {
//            this.audioUdp = audioUdp;
//        }
//
//        @Override
//        public void run() {
//            if (audioData == null) return;
//
//            final int partialLen = (int) (IComPacketTypes.AUDIO_SAMPLE_RATE * 0.02);//20ms packet data length
//
//            //Convert to BYTE, little-endian
//            //Play silence first; for-i loop handles leading silence, for-j loop handles trailing silence
//            byte[] audioPacket = new byte[partialLen * 2];
//            for (int i = 0; i < (audioData.length / partialLen) + 8; i++) {//6 extra cycles: 3 before, 3 after
//                if (!audioUdp.isPttOn) break;
//                long now = System.currentTimeMillis() - 1;//Get current time
//
//                audioUdp.sendTrackedPacket(IComPacketTypes.AudioPacket.getTxAudioPacket(audioPacket
//                        , (short) 0, audioUdp.localId, audioUdp.remoteId, audioUdp.innerSeq));
//                audioUdp.innerSeq++;
//
//                Arrays.fill(audioPacket, (byte) 0x00);
//                if (i >= 3) {//Let the first two empty packets be sent out
//                    for (int j = 0; j < partialLen; j++) {
//                        if ((i - 3) * partialLen + j < audioData.length) {
//                            System.arraycopy(IComPacketTypes.shortToBigEndian((short)
//                                            (audioData[(i - 3) * partialLen + j]
//                                                    * GeneralVariables.volumePercent))//Multiply by volume ratio
//                                    , 0, audioPacket, j * 2, 2);
//                        }
//                    }
//                }
//                while (audioUdp.isPttOn) {
//                    if (System.currentTimeMillis() - now >= 21) {//20ms per cycle
//                        break;
//                    }
//                }
//            }
//            Log.d(TAG, "run: Audio transmission complete!!");
//            Thread.currentThread().interrupt();
//        }
//
//    }

    /**
     * Audio data received from radio
     *
     * @param packet data packet
     * @param data   data
     */
    @Override
    public void onDataReceived(DatagramPacket packet, byte[] data) {
        super.onDataReceived(packet, data);
        if (IComPacketTypes.CONTROL_SIZE == data.length) {
            if (IComPacketTypes.ControlPacket.getType(data) == IComPacketTypes.CMD_I_AM_READY) {
                startTxAudio();
            }
        }
        if (!IComPacketTypes.AudioPacket.isAudioPacket(data)) return;
        byte[] audioData = IComPacketTypes.AudioPacket.getAudioData(data);
        if (onStreamEvents != null) {
            onStreamEvents.OnReceivedAudioData(audioData);
        }
    }


}
