package com.k1af.ft8af.icom;
/**
 * Base class for audio stream handling.
 * @author BGY70Z
 * @date 2023-08-26
 */

import android.util.Log;

import com.k1af.ft8af.GeneralVariables;

import java.net.DatagramPacket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioUdp extends IcomUdpBase {
    private static final String TAG = "AudioUdp";

    public AudioUdp() {
        udpStyle = IcomUdpStyle.AudioUdp;
    }



    public void sendTxAudioData(float[] audioData){}
    public void startTxAudio(){}
    public void stopTXAudio(){}
}
