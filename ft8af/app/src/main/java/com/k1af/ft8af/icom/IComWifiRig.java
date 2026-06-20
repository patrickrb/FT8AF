package com.k1af.ft8af.icom;
/**
 * WiFi mode iCom radio operations.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.media.AudioTrack;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.icom.IcomUdpBase.IcomUdpStyle;
import com.k1af.ft8af.ui.ToastMessage;

import java.io.IOException;

public class IComWifiRig extends WifiRig{

    public IComWifiRig(String ip, int port, String userName, String password) {
        super(ip,port,userName,password);
    }

    @Override
    public void start(){
        opened=true;
        openAudio();//Open audio
        controlUdp=new IcomControlUdp(userName,password,ip,port);

        //Set events; handle radio status and receive audio data from radio
        controlUdp.setOnStreamEvents(new IcomUdpBase.OnStreamEvents() {
            @Override
            public void OnReceivedIAmHere(byte[] data) {

            }

            @Override
            public void OnReceivedCivData(byte[] data) {
                if (onDataEvents!=null){
                    onDataEvents.onReceivedCivData(data);
                }
            }

            @Override
            public void OnReceivedAudioData(byte[] audioData) {
                if (onDataEvents!=null){
                    onDataEvents.onReceivedWaveData(audioData);
                }
                if (audioTrack!=null){
                   // if (!isPttOn) {//If PTT is not pressed
                        audioTrack.write(audioData, 0, audioData.length
                                , AudioTrack.WRITE_NON_BLOCKING);
                 //   }
                }
            }

            @Override
            public void OnUdpSendIOException(IcomUdpStyle style,IOException e) {
                ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                        R.string.network_exception),IcomUdpBase.getUdpStyle(style),e.getMessage()));
                close();
            }

            @Override
            public void OnLoginResponse(boolean authIsOK) {
                if (authIsOK){
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.login_succeed));
                }else {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.loging_failed));
                    controlUdp.closeAll();
                }
            }

        });
        controlUdp.openStream();//Open port
        controlUdp.startAreYouThereTimer();//Start connecting to radio
    }

    @Override
    public void setPttOn(boolean on){//Set PTT on/off
        isPttOn=on;
        controlUdp.civUdp.sendPttAction(on);
        controlUdp.audioUdp.isPttOn=on;
    }

    @Override
    public void sendCivData(byte[] data){
        controlUdp.sendCivData(data);
    }

    @Override
    public void sendWaveData(float[] data){//Send audio data to radio
        controlUdp.sendWaveData(data);
    }

    /**
     * Close all connections and audio
     */
    @Override
    public void close(){
        opened=false;
        controlUdp.closeAll();
        closeAudio();
    }


}
