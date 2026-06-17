package com.k1af.ft8af.icom;
/**
 * Handle ICom audio stream, extends AudioUdp.
 * @author BGY70Z
 * @date 2023-08-26
 */

import android.util.Log;

import com.k1af.ft8af.GeneralVariables;

import java.net.DatagramPacket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class IcomAudioUdp extends AudioUdp {
    private static final String TAG = "IcomAudioUdp";


    private final ExecutorService doTXThreadPool =Executors.newCachedThreadPool();
    private final DoTXAudioRunnable doTXAudioRunnable=new DoTXAudioRunnable(this);


    @Override
    public void sendTxAudioData(float[] audioData) {
        if (audioData==null) return;

        short[] temp=new short[audioData.length];
        //Incoming audio is LPCM, 32-bit float, 12000Hz
        //iCOM audio format is LPCM 16-bit Int, 12000Hz
        //Need to convert from float to 16-bit int
        for (int i = 0; i < audioData.length; i++) {
            float x = audioData[i];
            if (x > 1.0)
                x = 1.0f;
            else if (x < -1.0)
                x = -1.0f;
            temp[i] = (short)  (x * 32767.0);
        }
        doTXAudioRunnable.audioData=temp;
        doTXThreadPool.execute(doTXAudioRunnable);
    }
    private static class DoTXAudioRunnable implements Runnable{
        IcomAudioUdp icomAudioUdp;
        short[] audioData;//Incoming audio is LPCM 16-bit Int, 12000Hz

        public DoTXAudioRunnable(IcomAudioUdp icomAudioUdp) {
            this.icomAudioUdp = icomAudioUdp;
        }

        @Override
        public void run() {
            if (audioData==null) return;

            final int partialLen = IComPacketTypes.TX_BUFFER_SIZE * 2;//Packet data length
            //Convert to BYTE, little-endian

            //byte[] data = new byte[audioData.length * 2 + partialLen * 4];//Extra silence padding: 20ms*2 = 80ms total before and after
            //Play silence first before audio; the for-i loop handles leading silence, the for-j loop handles trailing silence
            byte[] audioPacket = new byte[partialLen];
            for (int i = 0; i < (audioData.length / IComPacketTypes.TX_BUFFER_SIZE) + 8; i++) {//6 extra cycles: 3 before, 3 after
                if (!icomAudioUdp.isPttOn) break;
                long now = System.currentTimeMillis() - 1;//Get current time

                icomAudioUdp.sendTrackedPacket(IComPacketTypes.AudioPacket.getTxAudioPacket(audioPacket
                        , (short) 0, icomAudioUdp.localId, icomAudioUdp.remoteId, icomAudioUdp.innerSeq));
                icomAudioUdp.innerSeq++;

                Arrays.fill(audioPacket,(byte)0x00);
                if (i>=3) {//Let the first two empty packets be sent out
                    for (int j = 0; j < IComPacketTypes.TX_BUFFER_SIZE; j++) {
                        if ((i-3) * IComPacketTypes.TX_BUFFER_SIZE + j < audioData.length) {
                            System.arraycopy(IComPacketTypes.shortToBigEndian((short)
                                            (audioData[(i-3) * IComPacketTypes.TX_BUFFER_SIZE + j]
                                                    * GeneralVariables.volumePercent))//Multiply by volume ratio
                                    , 0, audioPacket, j * 2, 2);
                        }
                    }
                }
                while (icomAudioUdp.isPttOn) {
                    if (System.currentTimeMillis() - now >= 21) {//20ms per cycle
                        break;
                    }
                }
            }
            Log.d(TAG, "run: Audio transmission complete!!" );
            Thread.currentThread().interrupt();
        }

    }


    @Override
    public void onDataReceived(DatagramPacket packet, byte[] data) {
        super.onDataReceived(packet, data);
        //Received data is at 12000Hz sample rate
        if (!IComPacketTypes.AudioPacket.isAudioPacket(data)) return;
        byte[] audioData = IComPacketTypes.AudioPacket.getAudioData(data);
        if (onStreamEvents != null) {
            onStreamEvents.OnReceivedAudioData(audioData);
        }
    }
}
