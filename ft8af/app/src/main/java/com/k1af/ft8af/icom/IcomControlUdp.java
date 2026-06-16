package com.k1af.ft8af.icom;
/**
 * ICom control stream.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import java.net.DatagramPacket;
import java.util.Timer;
import java.util.TimerTask;

public class IcomControlUdp extends ControlUdp {
    private static final String TAG = "IcomControlUdp";


    public IcomControlUdp(String userName, String password, String remoteIp, int remotePort) {
        super(userName,password,remoteIp,remotePort);

        civUdp = new IcomCivUdp();
        audioUdp = new IcomAudioUdp();

        civUdp.rigIp = remoteIp;
        audioUdp.rigIp = remoteIp;
        civUdp.openStream();
        audioUdp.openStream();
    }


    /**
     * Handle connInfo (0x90) packet from the radio. The radio sends the 0x90 packet twice:
     * first with busy=0, second with busy=1.
     * Extract macAddress and radio name from the 0x90 packet.
     *
     * @param data 0x90 packet
     */
    @Override
    public void onReceiveConnInfoPacket(byte[] data) {
        rigMacAddress = IComPacketTypes.ConnInfoPacket.getMacAddress(data);
        rigIsBusy = IComPacketTypes.ConnInfoPacket.getBusy(data);
        rigName = IComPacketTypes.ConnInfoPacket.getRigName(data);

        if (!rigIsBusy) {//First time receiving 0x90 packet; need to reply with a 0x90 packet
            sendTrackedPacket(
                    IComPacketTypes.ConnInfoPacket.connInfoPacketData(data, (short) 0
                            , localId, remoteId
                            , (byte) 0x01, (byte) 0x03, innerSeq, localToken, rigToken
                            , rigName, userName
                            , IComPacketTypes.AUDIO_SAMPLE_RATE//Receive at 12000Hz sample rate
                            , IComPacketTypes.AUDIO_SAMPLE_RATE//Transmit at 12000Hz sample rate
                            , civUdp.localPort, audioUdp.localPort
                            , IComPacketTypes.XIEGU_TX_BUFFER_SIZE));//0x96 is the buffer size commonly used by wfView
                            //, IComPacketTypes.TX_BUFFER_SIZE));//0xf0 is the buffer size used in earlier testing
            innerSeq++;
        }
    }

}
