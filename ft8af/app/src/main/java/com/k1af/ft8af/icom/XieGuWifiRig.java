package com.k1af.ft8af.icom;
/**
 * WiFi mode Xiegu radio operations.
 * @author BGY70Z
 * @date 2023-08-27
 */

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.icom.IcomUdpBase.IcomUdpStyle;
import com.k1af.ft8af.ui.ToastMessage;

import java.io.IOException;

public class XieGuWifiRig extends WifiRig{

    public XieGuWifiRig(String ip, int port, String userName, String password) {
        super(ip,port,userName,password);
    }

    @Override
    public void start(){
        // Tear down a previous session first (reconnect without an explicit disconnect,
        // e.g. the tap-to-reconnect chip): otherwise its timers and sockets stay alive and
        // keep firing this rig's handlers.
        ControlUdp previous = controlUdp;
        if (previous != null) {
            try {
                previous.closeAll();
            } catch (RuntimeException ignored) {
                // Already closed / never opened: nothing to reclaim.
            }
        }
        // Tag this attempt so handlers left over from a previous session can't report
        // into the new one (see WifiRig.beginLinkSession).
        final int session = beginLinkSession();
        opened=true;
        openAudio();//Open audio
        // Local handle for the handlers below: they must only ever tear down *their own*
        // sockets, never the rig's current controlUdp after a reconnect (Copilot #778).
        final ControlUdp udp = new XieGuControlUdp(userName,password,ip,port);
        controlUdp=udp;

        //Set events; handle radio status and receive audio data from radio
        controlUdp.setOnStreamEvents(new IcomUdpBase.OnStreamEvents() {
            @Override
            public void OnReceivedIAmHere(byte[] data) {

            }

            @Override
            public void OnReceivedCivData(byte[] data) {
                if (!isCurrentSession(session)) return;
                if (onDataEvents!=null){
                    onDataEvents.onReceivedCivData(data);
                }
            }

            @Override
            public void OnReceivedAudioData(byte[] audioData) {
                if (!isCurrentSession(session)) return;
                if (onDataEvents!=null){
                    onDataEvents.onReceivedWaveData(audioData);
                }
                writeAudio(audioData);//Guards against a concurrent closeAudio() release
            }

            @Override
            public void OnUdpSendIOException(IcomUdpStyle style,IOException e) {
                // Stale session: close only the old sockets, no toast, no close() of the
                // rig (which would tear down the new session's controlUdp).
                if (!admitSessionEvent(session, udp::closeAll)) return;
                ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                        R.string.network_exception),IcomUdpBase.getUdpStyle(style),e.getMessage()));
                notifySendError(session);
                close();
            }

            @Override
            public void OnLoginResponse(boolean authIsOK) {
                // Stale session: its sockets must not outlive it whatever the rig said.
                if (!admitSessionEvent(session, udp::closeAll)) return;
                notifyLoginResult(session, authIsOK);
                if (authIsOK){
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.login_succeed));
                }else {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.loging_failed));
                    udp.closeAll();
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
        notifyClosed();
        controlUdp.closeAll();
        closeAudio();
    }


}
