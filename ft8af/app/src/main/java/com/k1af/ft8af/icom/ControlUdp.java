package com.k1af.ft8af.icom;
/**
 * Base class for control stream handling.
 *
 * @author BGY70Z
 * @date 2023-08-26
 */

import android.util.Log;

import java.net.DatagramPacket;
import java.util.Timer;
import java.util.TimerTask;

public class ControlUdp extends IcomUdpBase {
    private static final String TAG = "ControlUdp";
    public final String APP_NAME = "FT8AF";

    //Related to sample rate: samples per 20ms = 12000/50 = 240 = 0xF0; actual byte count (16-bit) is doubled = 480 bytes


    public Timer tokenTimer;//Token renewal timer

    public String userName;
    public String password;
    public String rigName = "";
    public String audioName = "";
    public byte[] rigMacAddress = new byte[6];//Provided in 0xA8 and 0x90 packets
    public String connectionMode = "";

    public boolean gotAuthOK = false;//Token authentication passed
    public boolean isAuthenticated = false;//Login successful
    public boolean rigIsBusy = false;

    public IcomCivUdp civUdp;
    public AudioUdp audioUdp;


    public ControlUdp(String userName, String password, String remoteIp, int remotePort) {
        udpStyle = IcomUdpStyle.ControlUdp;
        this.userName = userName;
        this.password = password;

        this.rigIp = remoteIp;
        this.rigPort = remotePort;

    }


    @Override
    public void onDataReceived(DatagramPacket packet, byte[] data) {
        // Parent class default packet handling:
        // Control packet 0x10 (CMD_I_AM_HERE, CMD_RETRANSMIT),
        // Ping packet 0x15
        // Variable-length packets: RETRANSMIT packet, type=IComPacketTypes.CMD_RETRANSMIT
        super.onDataReceived(packet, data);
        switch (data.length) {
            case IComPacketTypes.CONTROL_SIZE://0x04 and 0x01 commands already handled in parent class
                if (IComPacketTypes.ControlPacket.getType(data) == IComPacketTypes.CMD_I_AM_HERE) {
                    rigIp = packet.getAddress().getHostAddress();
                }
                //If the radio replies I'm ready, initiate login
                if (IComPacketTypes.ControlPacket.getType(data) == IComPacketTypes.CMD_I_AM_READY) {
                    sendLoginPacket();//Radio is ready, request login with 0x80 packet
                    startIdleTimer();//Start idle packet timer
                }
                break;
            case IComPacketTypes.TOKEN_SIZE://Handle token renewal and related operations
                onReceiveTokenPacket(data);
                break;
            case IComPacketTypes.STATUS_SIZE://0x50 radio replies with its parameters: CivPort, AudioPort, etc.
                onReceiveStatusPacket(data);
                break;
            case IComPacketTypes.LOGIN_RESPONSE_SIZE://0x60 radio replies to login request
                onReceiveLoginResponse(data);
                break;
            case IComPacketTypes.CONNINFO_SIZE://Radio sends 0x90 packet twice; the difference is in the busy field
                onReceiveConnInfoPacket(data);
                break;
            case IComPacketTypes.CAP_CAPABILITIES_SIZE://0xA8 packet, returns CI-V address
                byte[] audioCap = IComPacketTypes.CapCapabilitiesPacket.getRadioCapPacket(data, 0);
                if (audioCap != null) {
                    civUdp.supportTX = IComPacketTypes.RadioCapPacket.getSupportTX(audioCap);
                    civUdp.civAddress = IComPacketTypes.RadioCapPacket.getCivAddress(audioCap);
                    audioName = IComPacketTypes.RadioCapPacket.getAudioName(audioCap);
                }
                break;
        }
    }


    /**
     * Handle connInfo (0x90) packet from the radio. The radio sends the 0x90 packet twice:
     * first with busy=0, second with busy=1.
     * Extract macAddress and radio name from the 0x90 packet.
     * This is left for IcomControlUdp and XieGuControlUdp to implement.
     * @param data 0x90 packet
     */
    public void onReceiveConnInfoPacket(byte[] data) {
    }


    /**
     * Handle login response packet from the radio
     *
     * @param data 0x60 packet
     */
    public void onReceiveLoginResponse(byte[] data) {
        if (IComPacketTypes.ControlPacket.getType(data) == 0x01) return;
        connectionMode = IComPacketTypes.LoginResponsePacket.getConnection(data);
        Log.d(TAG, "connection mode:" + connectionMode);
        if (IComPacketTypes.LoginResponsePacket.authIsOK(data)) {//errorCode=0x00, authentication successful
            Log.d(TAG, "onReceiveLoginResponse: Login succeed!");
            if (!isAuthenticated) {
                rigToken = IComPacketTypes.LoginResponsePacket.getToken(data);
                Log.d(TAG, "onReceiveLoginResponse: send token confirm 0x02");
                sendTokenPacket(IComPacketTypes.TOKEN_TYPE_CONFIRM);//Send token confirmation packet 0x40
                startTokenTimer();//Start token renewal timer
                isAuthenticated = true;
            }
        }
        if (onStreamEvents != null) {//Trigger authentication event
            onStreamEvents.OnLoginResponse(IComPacketTypes.LoginResponsePacket.authIsOK(data));
        }
    }

    /**
     * Handle radio status parameters. 0x50 packet
     *
     * @param data 0x50 packet
     */
    public void onReceiveStatusPacket(byte[] data) {
        //if (this.authDone) return;//6100 frequently triggers 0x50 packets
        if (IComPacketTypes.ControlPacket.getType(data) == 0x01) return;
        if (IComPacketTypes.StatusPacket.getAuthOK(data)
                && IComPacketTypes.StatusPacket.getIsConnected(data)) {//Token auth succeeded and connected
            audioUdp.rigPort = IComPacketTypes.StatusPacket.getRigAudioPort(data);
            audioUdp.rigIp = rigIp;
            civUdp.rigPort = IComPacketTypes.StatusPacket.getRigCivPort(data);
            civUdp.rigIp = rigIp;
            Log.e(TAG, String.format("onReceiveStatusPacket: Status packet 0x50: civRigPort:%d,audioRigPort:%d"
                    , civUdp.rigPort, audioUdp.rigPort));
            //todo 6100 differs from iCom
            civUdp.startAreYouThereTimer();//CI-V port starts connecting to radio
            audioUdp.startAreYouThereTimer();//Audio port starts connecting to radio
        }//else handle connection close???
    }

    /**
     * Handle token packet
     *
     * @param data 0x40 packet
     */
    public void onReceiveTokenPacket(byte[] data) {
        //Check if this is a token renewal packet
        if (IComPacketTypes.TokenPacket.getRequestType(data) == IComPacketTypes.TOKEN_TYPE_RENEWAL
                && IComPacketTypes.TokenPacket.getRequestReply(data) == 0x02
                && IComPacketTypes.ControlPacket.getType(data) != IComPacketTypes.CMD_RETRANSMIT) {
            int response = IComPacketTypes.TokenPacket.getResponse(data);
            if (response == 0x0000) {//Renewal succeeded
                gotAuthOK = true;
            } else if (response == 0xffffffff) {
                remoteId = IComPacketTypes.ControlPacket.getSentId(data);
                localToken = IComPacketTypes.TokenPacket.getTokRequest(data);
                rigToken = IComPacketTypes.TokenPacket.getToken(data);
                sendConnectionRequest();//Request connection
            } else {
                Log.e(TAG, "Token renewal failed,unknow response");
            }
        }
    }

    /**
     * Send CI-V command
     *
     * @param data command
     */
    public void sendCivData(byte[] data) {
        civUdp.sendCivData(data);
    }

    /**
     * Send audio data to the radio
     *
     * @param data data
     */
    public void sendWaveData(float[] data) {
        audioUdp.sendTxAudioData(data);
    }

    /**
     * Send 0x90 packet to request connection from the radio
     */
    public void sendConnectionRequest() {
        sendTrackedPacket(IComPacketTypes.ConnInfoPacket.connectRequestPacket((short) 0
                , localId, remoteId, (byte) 0x01, (byte) 0x03, innerSeq, localToken, rigToken
                , rigMacAddress, rigName, userName, IComPacketTypes.AUDIO_SAMPLE_RATE
                , civUdp.getLocalPort(), audioUdp.getLocalPort()
                , IComPacketTypes.TX_BUFFER_SIZE));
        innerSeq++;
    }

    /**
     * Send login packet (0x80 packet)
     */
    public void sendLoginPacket() {
        sendTrackedPacket(IComPacketTypes.LoginPacket.loginPacketData((short) 0
                , localId, remoteId, innerSeq, localToken, rigToken, userName, password, APP_NAME));
        innerSeq++;
    }

    @Override
    public void setOnStreamEvents(OnStreamEvents onStreamEvents) {
        super.setOnStreamEvents(onStreamEvents);
        audioUdp.onStreamEvents = onStreamEvents;
        civUdp.onStreamEvents = onStreamEvents;
    }

    /**
     * Start token renewal timer
     */
    public void startTokenTimer() {
        stopTimer(tokenTimer);
        Log.d(TAG, String.format("start Toke Timer: local port:%d,remote port %d", localPort, rigPort));
        tokenTimer = new Timer();
        tokenTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                sendTokenPacket(IComPacketTypes.TOKEN_TYPE_RENEWAL);
            }
        }, IComPacketTypes.TOKEN_RENEWAL_PERIOD_MS, IComPacketTypes.TOKEN_RENEWAL_PERIOD_MS);
    }

    public void closeAll() {
        sendTrackedPacket(IComPacketTypes.TokenPacket.getTokenPacketData((short) 0
                , localId, remoteId, IComPacketTypes.TOKEN_TYPE_DELETE, innerSeq, localToken, rigToken));
        innerSeq++;
        this.close();
        civUdp.close();
        audioUdp.stopTXAudio();
        audioUdp.close();

        civUdp.sendOpenClose(false);
    }
}
