package com.k1af.ft8af.icom;
/**
 * Simple UDP stream handler wrapper.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;

import org.checkerframework.checker.units.qual.A;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Timer;
import java.util.TimerTask;


public class IcomUdpBase {
    public enum IcomUdpStyle {//Stream types
        UdpBase,
        ControlUdp,
        CivUdp,
        AudioUdp
    }

    public static String getUdpStyle(IcomUdpStyle style) {
        switch (style) {
            case ControlUdp:
                return GeneralVariables.getStringFromResource(R.string.control_stream);
            case CivUdp:
                return GeneralVariables.getStringFromResource(R.string.civ_stream);
            case AudioUdp:
                return GeneralVariables.getStringFromResource(R.string.audio_stream);
            default:
                return GeneralVariables.getStringFromResource(R.string.data_stream);
        }
    }

    /**
     * Event interface
     */
    public interface OnStreamEvents {
        void OnReceivedIAmHere(byte[] data);

        void OnReceivedCivData(byte[] data);

        void OnReceivedAudioData(byte[] audioData);

        void OnUdpSendIOException(IcomUdpStyle style, IOException e);

        void OnLoginResponse(boolean authIsOK);
        //void OnWatchDogAlert(IcomUdpStyle style,boolean isAlerted);
    }

    public IcomUdpStyle udpStyle = IcomUdpStyle.UdpBase;

    private static final String TAG = "IcomUdpBase";
    public int rigPort;
    public String rigIp;
    public int localPort;
    public int localId = (int) System.currentTimeMillis();//Random code using time as random variable
    public int remoteId;
    public boolean authDone = false;//Login done
    public boolean rigReadyDone = false;//Radio is ready; control can login, CI-V can open.
    public short trackedSeq = 1;//are you there=0, are you ready=1. Tracked packets start after are you ready
    public short pingSeq = 0;//Ping initial value is 0
    public short innerSeq = 0x30;
    public int rigToken;//Token provided by radio
    public short localToken = (short) System.currentTimeMillis();//Locally generated token, can be random
    public boolean isPttOn = false;


    public IcomSeqBuffer txSeqBuffer = new IcomSeqBuffer();//Sent command history list
    //public IcomSeqBuffer rxSeqBuffer = new IcomSeqBuffer();//Received command history list
    public long lastReceivedTime = System.currentTimeMillis();//Last data received time
    public long lastSentTime = System.currentTimeMillis();//Last data sent time


    public IcomUdpClient udpClient;//UDP client for radio communication


    public OnStreamEvents onStreamEvents;//Event handlers
    //Timer execution: TimerTask, invoke via timer.schedule(task, delay, period)
    public Timer areYouThereTimer;
    private AreYouThereTimerTask areYouThereTask = null;
    public Timer pingTimer;
    public Timer idleTimer;//Idle packet timer


    public void close() {
        onStreamEvents = null;//No need to pop up network error messages
        sendUntrackedPacket(IComPacketTypes.ControlPacket.toBytes(IComPacketTypes.CMD_DISCONNECT
                , (short) 0, localId, remoteId));
        stopTimer(areYouThereTimer);
        stopTimer(pingTimer);
        stopTimer(idleTimer);
        closeStream();
    }

    /**
     * Close udpClient
     */
    public void closeStream() {
        if (udpClient != null) {
            try {
                udpClient.setActivated(false);
            } catch (SocketException e) {
                e.printStackTrace();
                Log.e(TAG, "closeStream: " + e.getMessage());
            }
        }
    }

    /**
     * Open UDP stream port. If already open, reopens it and local port may change
     */
    public void openStream() {//Open
        if (udpClient == null) {
            udpClient = new IcomUdpClient(-1);
        }
        udpClient.setOnUdpEvents(new IcomUdpClient.OnUdpEvents() {
            @Override
            public void OnReceiveData(DatagramSocket socket, DatagramPacket packet, byte[] data) {
                //Filter out invalid packets
                if (data.length < IComPacketTypes.CONTROL_SIZE) return;//If smaller than minimum control packet, exit
                if (IComPacketTypes.ControlPacket.getRcvdId(data) != localId)
                    return;//If received ID differs from my ID, also exit

                onDataReceived(packet, data);
            }

            @Override
            public void OnUdpSendIOException(IOException e) {
                if (onStreamEvents != null) {
                    onStreamEvents.OnUdpSendIOException(udpStyle, e);
                }
            }
        });

        try {
            if (udpClient.isActivated()) udpClient.setActivated(false);
            udpClient.setActivated(true);
            localPort = udpClient.getLocalPort();
            Log.d(TAG, "IcomUdpBase: Open udp local port:" + localPort);
        } catch (SocketException e) {
            e.printStackTrace();
            Log.e(TAG, "IcomUdpBase: Open udp failed:" + e.getMessage());
        }
    }

    /**
     * Check if UDP is open
     * @return whether open
     */
    public boolean streamOpened() {
        if (udpClient == null) {
            return false;
        } else {
            return udpClient.isActivated();
        }
    }

    /**
     * Action after receiving data; subclasses can override
     *
     * @param data data
     */
    public void onDataReceived(DatagramPacket packet, byte[] data) {

        //Common packet handling here; non-common handling can be overridden in subclasses.
        switch (data.length) {
            case IComPacketTypes.CONTROL_SIZE://Control packet, handle I'm here and retransmit
                onReceivedControlPacket(data);
                break;
            case IComPacketTypes.PING_SIZE://Ping packet
                onReceivedPingPacket(data);//Reply to ping
                break;
            case IComPacketTypes.RETRANSMIT_RANGE_SIZE://0x18, request retransmit by sequence range
                break;

        }

        //Handle multiple retransmit requests at once: type=0x01, len!=0x10; retransmit seq numbers are a short array after byte 0x10.
        if (data.length != IComPacketTypes.CONTROL_SIZE
                && IComPacketTypes.ControlPacket.getType(data) == IComPacketTypes.CMD_RETRANSMIT) {
            retransmitMultiPacket(data);
        }

        //todo - If received data is not a Ping packet, and type=0x00 && seq!=0x00, it is a command; consider adding to rxSeqBuffer.
        //todo - Received command buffer is rxSeqBuffer

    }

    /**
     * When Control (0x10) packet is received
     *
     * @param data data packet
     */
    public void onReceivedControlPacket(byte[] data) {
        //todo Should implement reply for type=0x01 command, i.e. retransmit
        switch (IComPacketTypes.ControlPacket.getType(data)) {
            case IComPacketTypes.CMD_I_AM_HERE:
                if (onStreamEvents != null) {
                    onStreamEvents.OnReceivedIAmHere(data);
                }
                remoteId = IComPacketTypes.ControlPacket.getSentId(data);//Record the remote ID
                stopTimer(areYouThereTimer);//Stop are you there timer
                startPingTimer();//Start ping timer; all 3 ports have ping
                //Send are you ready?
                sendUntrackedPacket(IComPacketTypes.ControlPacket.toBytes(
                        IComPacketTypes.CMD_ARE_YOU_READY, (short) 1, localId, remoteId
                ));
                //Start idle timer in control stream
                //If ping timer not started, start at 500ms; all 3 ports have Ping
                break;
            case IComPacketTypes.CMD_I_AM_READY:
                //startIdleTimer();//Start idle packet timer
                //Different ports handle differently; implement in subclass overrides
                //control = login
                //civ = openClose
                //audio = not observed
                break;
            case IComPacketTypes.CMD_RETRANSMIT://A packet needs to be retransmitted
                retransmitPacket(data);
                break;
        }

    }

    /**
     * Find and retransmit a single packet
     *
     * @param data request packet from radio
     */
    public void retransmitPacket(byte[] data) {
        retransmitPacket(IComPacketTypes.ControlPacket.getSeq(data));
    }

    /**
     * Find and retransmit a single packet
     *
     * @param retransmitSeq sequence number to retransmit
     */
    public void retransmitPacket(short retransmitSeq) {
        byte[] packet = txSeqBuffer.get(retransmitSeq);

        if (packet != null) {//Found the historical sent packet
            sendUntrackedPacket(packet);
        } else {//Packet not found, send an idle packet
            sendUntrackedPacket(IComPacketTypes.ControlPacket.idlePacketData(retransmitSeq, localId, remoteId));
        }
    }

    /**
     * Find and retransmit multiple packets; data format: controlPacket+short array
     *
     * @param data request packet from radio
     */
    public void retransmitMultiPacket(byte[] data) {
        if (data.length <= IComPacketTypes.CONTROL_SIZE) return;
        if (IComPacketTypes.ControlPacket.getType(data) != IComPacketTypes.CMD_RETRANSMIT) return;
        for (int i = 0x10; i < data.length; i = i + 2) {
            if (i + 1 > data.length - 1) break;//Guard: prevent array index overflow if byte count is odd
            //Retransmit command
            retransmitPacket(IComPacketTypes.readShortBigEndianData(data, i));
        }
    }


    /**
     * Start Are you there timer
     * Triggered every 500ms
     * Are you there packet is control packet 0x10, type 0x03
     */
    public void startAreYouThereTimer() {
        Log.e(TAG, "startAreYouThereTimer: stop timer:" + this.toString());
        stopTimer(areYouThereTimer);

        areYouThereTimer = new Timer();

        areYouThereTimer.scheduleAtFixedRate(new AreYouThereTimerTask()
                , 0, IComPacketTypes.ARE_YOU_THERE_PERIOD_MS);
    }

    /**
     * Start ping timer
     */
    public void startPingTimer() {
        stopTimer(pingTimer);//Close any previously opened timer
        Log.d(TAG, String.format("start PingTimer: local port:%d,remote port %d", localPort, rigPort));
        pingTimer = new Timer();
        pingTimer.scheduleAtFixedRate(new PingTimerTask(), 0, IComPacketTypes.PING_PERIOD_MS);
    }

    /**
     * Idle packet timer
     */
    public void startIdleTimer() {
        stopTimer(idleTimer);
        Log.d(TAG, String.format("start Idle Timer: local port:%d,remote port %d", localPort, rigPort));
        idleTimer = new Timer();
        idleTimer.scheduleAtFixedRate(new IdleTimerTask(), IComPacketTypes.IDLE_PERIOD_MS
                , IComPacketTypes.IDLE_PERIOD_MS);
    }

    /**
     * Stop timer
     *
     * @param timer timer
     */
    public void stopTimer(Timer timer) {
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
    }


    public void onReceivedPingPacket(byte[] data) {
        //Two cases: radio pings me, or radio replies to my ping
        if (IComPacketTypes.ControlPacket.getType(data) == IComPacketTypes.CMD_PING) {
            if (IComPacketTypes.PingPacket.getReply(data) == 0x00) {//Radio pings me
                sendReplyPingPacket(data);//Reply to radio ping
            } else {//Reply to my ping, seq++
                if (IComPacketTypes.ControlPacket.getSeq(data) == pingSeq) {
                    pingSeq++;
                }
            }
        }
    }

    /**
     * Send token packet 0x40
     * @param requestType token type, 0x02=confirm, 0x05=renew
     */
    public void sendTokenPacket(byte requestType) {
        sendTrackedPacket(IComPacketTypes.TokenPacket.getTokenPacketData((short) 0
                , localId, remoteId, requestType, innerSeq, localToken, rigToken));
        innerSeq++;
    }

    /**
     * Send ping packet to radio
     */
    public void sendPingPacket() {
        byte[] data = IComPacketTypes.PingPacket.sendPingData(localId, remoteId, pingSeq);
        sendUntrackedPacket(data);//Ping packets use their own sequence, so send as untracked
        //pingSeq++; increment only after radio replies
    }

    /**
     * Reply to radio's ping
     *
     * @param data remote ping data
     */
    public void sendReplyPingPacket(byte[] data) {
        byte[] packet = IComPacketTypes.PingPacket.sendReplayPingData(data, localId, remoteId);
        sendUntrackedPacket(packet);
    }

    /**
     * Send command data packet
     *
     * @param data data packet
     */
    public synchronized void sendUntrackedPacket(byte[] data) {
        try {
            udpClient.sendData(data, rigIp, rigPort);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    /**
     * Send tracked data packet
     *
     * @param data data packet
     */
    public synchronized void sendTrackedPacket(byte[] data) {
        try {
            lastSentTime = System.currentTimeMillis();
            System.arraycopy(IComPacketTypes.shortToBigEndian(trackedSeq), 0
                    , data, 6, 2);//Write sequence number into data
            udpClient.sendData(data, rigIp, rigPort);
            txSeqBuffer.add(trackedSeq, data);
            trackedSeq++;
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    public int getLocalPort() {
        return localPort;
    }

    /**
     * Send idle packet as tracked. Usually called from idleTimer. Placed here for convenience.
     */
    public void sendIdlePacket() {
        //seq is set to 0 because sendTrackedPacket will write trackedSeq into the packet
        sendTrackedPacket(IComPacketTypes.ControlPacket.idlePacketData((short) 0, localPort, remoteId));
    }


    public OnStreamEvents getOnStreamEvents() {
        return onStreamEvents;
    }

    public void setOnStreamEvents(OnStreamEvents onStreamEvents) {
        this.onStreamEvents = onStreamEvents;
    }

    /**
     * Are you there timer task
     * Control packet 0x10, type 0x03
     */
    public class AreYouThereTimerTask extends TimerTask {
        @Override
        public void run() {
            Log.d(TAG, String.format("Task,AreYouThereTimer: local port:%d,remote port %d", localPort, rigPort));
            sendUntrackedPacket(
                    IComPacketTypes.ControlPacket.toBytes(IComPacketTypes.CMD_ARE_YOU_THERE
                            , (short) 0, localId, 0));
        }
    }

    /**
     * Ping timer task
     */
    public class PingTimerTask extends TimerTask {
        @Override
        public void run() {
            sendPingPacket();//Send ping packet
        }
    }

    /**
     * Idle command timer task
     */
    public class IdleTimerTask extends TimerTask {

        @Override
        public void run() {
            if (txSeqBuffer.getTimeOut() > 200) {//If no command sent for over 200ms, send an idle command
                sendTrackedPacket(
                        IComPacketTypes.ControlPacket.toBytes(IComPacketTypes.CMD_NULL
                                , (short) 0, localId, remoteId));
            }
        }
    }

}
