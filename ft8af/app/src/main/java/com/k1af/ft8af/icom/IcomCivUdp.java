package com.k1af.ft8af.icom;
/**
 * Handle ICom CI-V stream.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import com.k1af.ft8af.rigs.IcomRigConstant;

import java.net.DatagramPacket;
import java.util.Timer;
import java.util.TimerTask;

public class IcomCivUdp extends IcomUdpBase{
    private static final String TAG="IcomCivUdp";
    public byte civAddress=(byte)0xA4;
    public boolean supportTX=true;
    public short civSeq=0;


    private Timer openCivDataTimer;


    public IcomCivUdp() {
        udpStyle=IcomUdpStyle.CivUdp;
    }

    @Override
    public void onDataReceived(DatagramPacket packet,byte[] data) {
        super.onDataReceived(packet,data);

        if (data.length == IComPacketTypes.CONTROL_SIZE) {
            if (IComPacketTypes.ControlPacket.getType(data) == IComPacketTypes.CMD_I_AM_READY) {
                Log.d(TAG, "onDataReceived: civ I am ready!!");
                sendOpenClose(true);//Open connection
                startIdleTimer();//Start idle packet timer
                startCivDataTimer();//Start CI-V watchdog

            }
        } else {
            if (IComPacketTypes.ControlPacket.getType(data) != IComPacketTypes.CMD_PING) {
                Log.d(TAG, "onDataReceived: CIV :" + IComPacketTypes.byteToStr(data));
                checkCivData(data);
            }
            if (IComPacketTypes.ControlPacket.getType(data) == IComPacketTypes.CMD_RETRANSMIT) {
                Log.d(TAG, "onDataReceived: type=0x01"+IComPacketTypes.byteToStr(data) );
                //lastReceived=System.currentTimeMillis();//Update last data received time for watchdog handling
            }

        }
    }

    public void checkCivData(byte[] data){
       if (IComPacketTypes.CivPacket.checkIsCiv(data)){
           lastReceivedTime=System.currentTimeMillis();
           if (getOnStreamEvents()!=null){
               getOnStreamEvents().OnReceivedCivData(IComPacketTypes.CivPacket.getCivData(data));
           }
       }
    }

    public void sendOpenClose(boolean open){
        if (open) {
            sendTrackedPacket(IComPacketTypes.OpenClosePacket.toBytes((short) 0
                    , localId, remoteId, civSeq,(byte) 0x04));//Open connection
        }else {
            sendTrackedPacket(IComPacketTypes.OpenClosePacket.toBytes((short) 0
                    , localId, remoteId, civSeq,(byte) 0x00));//Close connection
        }
        civSeq++;
    }
    public void startCivDataTimer(){
        stopTimer(openCivDataTimer);
        Log.d(TAG, String.format("openCivDataTimer: local port:%d,remote port %d", localPort, rigPort));
        openCivDataTimer = new Timer();
        openCivDataTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (System.currentTimeMillis()-lastReceivedTime>2000) {
                    sendOpenClose(true);
                }
            }
        }, 100, IComPacketTypes.OPEN_CLOSE_PERIOD_MS);
    }


    public void sendPttAction(boolean pttOn){
        if (pttOn) {
            sendCivData(IcomRigConstant.setPTTState(0xe0, civAddress, IcomRigConstant.PTT_ON));
        }else {
            sendCivData(IcomRigConstant.setPTTState(0xe0, civAddress, IcomRigConstant.PTT_OFF));
        }
    }

    public void sendCivData(byte[] data){
        sendTrackedPacket(IComPacketTypes.CivPacket.setCivData((short) 0,localId,remoteId,civSeq,data));
        civSeq++;
    }

    @Override
    public void close() {
        super.close();
        sendOpenClose(false);
        stopTimer(openCivDataTimer);
        stopTimer(idleTimer);

    }
}
