package com.k1af.ft8af.flex;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

// Enum definitions for the VITA discovery message parser
//enum VitaTokens {
//    nullToken ,
//    ipToken,
//    portToken,
//    modelToken,
//    serialToken,
//    callsignToken,
//    nameToken,
//    dpVersionToken,
//    versionToken,
//    statusToken,
//};
/**
 * RadioFactory: All currently discovered radios.
 * Instantiate this class to create a Radio Factory that maintains the FlexRadio list (flexRadios) for radios discovered on the network.
 *
 * Uses UDP protocol to receive VITA protocol data from broadcast on port 4992, parsing serial numbers to update the radio list flexRadios.
 * @author BGY70Z
 * @date 2023-03-20
 */
public class FlexRadioFactory {
    private static final String TAG="FlexRadioFactory";
    private static final int FLEX_DISCOVERY_PORT =4992;
    private static FlexRadioFactory instance=null;
    private final RadioUdpClient broadcastClient ;
    private OnFlexRadioEvents onFlexRadioEvents;

    private Timer refreshTimer=null;
    private TimerTask refreshTask=null;

    public ArrayList<FlexRadio> flexRadios=new ArrayList<>();

    /**
     * Get the radio list instance
     * @return radio list instance
     */
    public static FlexRadioFactory getInstance(){
        if (instance==null){
            instance= new FlexRadioFactory();
        }
        return instance;
    }



    public FlexRadioFactory() {
        broadcastClient = new RadioUdpClient(FLEX_DISCOVERY_PORT);

        broadcastClient.setOnUdpEvents(new RadioUdpClient.OnUdpEvents() {
            @Override
            public void OnReceiveData(DatagramSocket socket, DatagramPacket packet, byte[] data) {
                VITA vita = new VITA(data);
                if (vita.isAvailable//If packet is valid and classId=0x534CFFFF, StreamId=0x800, update the radio list
                        &&vita.informationClassCode==VITA.FLEX_CLASS_ID
                        &&vita.packetClassCode==VITA.VS_Discovery
                        &&vita.streamId==VITA.FLEX_Discovery_stream_ID){
                    updateFlexRadioList(new String(vita.payload));
                }
            }
        });
        try {
            broadcastClient.setActivated(true);
        } catch (SocketException e) {
            e.printStackTrace();
            Log.e(TAG, "FlexRadioFactory: "+e.getMessage());
        }

    }


    public void startRefreshTimer(){
        if (refreshTimer==null) {
            refreshTask=new TimerTask() {
                @Override
                public void run() {
                    Log.e(TAG, "run: checking offline" );
                    checkOffLineRadios();
                }
            };
            refreshTimer=new Timer();
            refreshTimer.schedule(refreshTask, 1000, 1000);//Check if radios in the list are online (every second)
        }
    }
    public void cancelRefreshTimer(){
        if (refreshTimer!=null){
            refreshTimer.cancel();
            refreshTimer=null;
            refreshTask.cancel();
            refreshTask=null;
        }
    }

    /**
     * Find the radio's serial number from the data
     * @param s data
     * @return serial number
     */
    private String getSerialNum(String s){
        String[] strings=s.split(" ");
        for (int i = 0; i <strings.length ; i++) {
            if (strings[i].toLowerCase().startsWith("serial")){
                return strings[i].substring("serial".length()+1);
            }
        }
        return "";
    }

    /**
     * Search for a radio with the specified serial number in the radio list
     * @param serial serial number
     * @return radio instance
     */
    public FlexRadio checkFlexRadioBySerial(String serial){
        for (FlexRadio radio:flexRadios) {
            if (radio.isEqual(serial)){
                return radio;
            }
        }
        return null;
    }

    private synchronized void updateFlexRadioList(String s){
        String serial=getSerialNum(s);
        if (serial.equals("")){return;}
        FlexRadio radio=checkFlexRadioBySerial(serial);
        if (radio!=null){
            radio.updateLastSeen();
        }else {
            radio=new FlexRadio(s);
            if (onFlexRadioEvents!=null){
                onFlexRadioEvents.OnFlexRadioAdded(radio);
            }
            flexRadios.add(radio);
        }
    }

    /**
     * Check if a radio is offline; if so, trigger the offline event
     */
    private void checkOffLineRadios(){
        for (FlexRadio radio:flexRadios) {
            if (radio.isInvalidNow()){
               if (onFlexRadioEvents!=null){
                   onFlexRadioEvents.OnFlexRadioInvalid(radio);
               }
            }
        }
    }

    //***********Getter****************
    public RadioUdpClient getBroadcastClient() {
        return broadcastClient;
    }

    public OnFlexRadioEvents getOnFlexRadioEvents() {
        return onFlexRadioEvents;
    }

    public void setOnFlexRadioEvents(OnFlexRadioEvents onFlexRadioEvents) {
        this.onFlexRadioEvents = onFlexRadioEvents;
    }
    //*********************************


    /**
     * Interface for radio list change events
     */
    public static interface OnFlexRadioEvents{
        void OnFlexRadioAdded(FlexRadio flexRadio);
        void OnFlexRadioInvalid(FlexRadio flexRadio);
    }

}
