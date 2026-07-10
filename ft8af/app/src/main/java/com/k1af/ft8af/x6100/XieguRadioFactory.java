package com.k1af.ft8af.x6100;

/**
 * XieguRadioFactory - all currently discovered radios.
 * RadioFactory: Instantiate this class to create a Radio Factory that discovers Xiegu radios on the same LAN.
 *
 * Receives VITA protocol data from UDP broadcast on port 7001 and parses radio information.
 *
 * @author BGY70Z
 * @date 2023-11-29
 */


import android.util.Log;

import com.k1af.ft8af.flex.FlexRadio;
import com.k1af.ft8af.flex.RadioUdpClient;
import com.k1af.ft8af.flex.VITA;


import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;




public class XieguRadioFactory {
    private static final String TAG="XieguRadioFactory";
    private static final int XIEGU_DISCOVERY_PORT =7001;
    private static XieguRadioFactory instance=null;
    private final RadioUdpClient broadcastClient ;
    private OnXieguRadioEvents onXieguRadioEvents;

    private Timer refreshTimer=null;
    private TimerTask refreshTask=null;

    public ArrayList<X6100Radio> xieguRadios=new ArrayList<>();

    /**
     * Get the radio list instance
     * @return radio list instance
     */
    public static XieguRadioFactory getInstance(){
        if (instance==null){
            instance= new XieguRadioFactory();
        }
        instance.xieguRadios.clear();
        return instance;
    }



    public XieguRadioFactory() {
        broadcastClient = new RadioUdpClient(XIEGU_DISCOVERY_PORT);


        broadcastClient.setOnUdpEvents(new RadioUdpClient.OnUdpEvents() {
            @Override
            public void OnReceiveData(DatagramSocket socket, DatagramPacket packet, byte[] data) {
                VITA vita = new VITA(data);

                if (vita.isAvailable//If the data packet is valid
                        &&vita.classId64 == VITA.XIEGU_Discovery_Class_Id
                        &&vita.streamId==VITA.XIEGU_Discovery_Stream_Id){
                    InetAddress address = packet.getAddress();//Get IP address
                    updateXieguRadioList(new String(vita.payload),address.getHostAddress());
                }
            }
        });
        try {
            broadcastClient.setActivated(true);
        } catch (SocketException e) {
            e.printStackTrace();
            Log.e(TAG, "XieguRadioFactory: "+e.getMessage());
        }

    }


    public void startRefreshTimer(){
        if (refreshTimer==null) {
            refreshTask=new TimerTask() {
                @Override
                public void run() {
                    Log.e(TAG, "run: checking offline status" );
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
     * Find the radio's MAC address from the data
     * @param s data
     * @return MAC address
     */
    static String getMacAddress(String s){
        if (s==null){return "";}
        String[] strings=s.split(" ");
        for (int i = 0; i <strings.length ; i++) {
            // Require the "mac=" prefix (not just "mac") before reading the value.
            // The old code matched startsWith("mac") and then did
            // substring("mac".length() + 1) == substring(4) unconditionally, so a
            // bare "mac" token (length 3, as in a truncated/garbled discovery
            // broadcast) threw StringIndexOutOfBoundsException. This runs on the
            // UDP discovery read thread (RadioUdpClient), whose loop catches only
            // IOException, so that RuntimeException escaped and crashed the whole
            // app. Requiring the '=' also matches how X6100Radio.getParameterStr
            // reads "mac=" and avoids misreading a stray "machine" token as a MAC.
            // Twin of the FlexRadioFactory.getSerialNum fix (PR #501/#503).
            if (strings[i].toLowerCase().startsWith("mac=")){
                return strings[i].substring("mac=".length());
            }
        }
        return "";
    }

    /**
     * Find a radio with the specified MAC in the radio list
     * @param mac MAC address
     * @return radio instance
     */
    public X6100Radio checkXieguRadioByMac(String mac){
        for (X6100Radio radio:xieguRadios) {
            if (radio.isEqual(mac)){
                return radio;
            }
        }
        return null;
    }

    private synchronized void updateXieguRadioList(String s,String ip){
       String mac =  getMacAddress(s);
       if (mac.equals("")) {return;}
        X6100Radio radio=checkXieguRadioByMac(mac);
        if (radio!=null){
            radio.updateLastSeen();
        }else {
            radio=new X6100Radio(s,ip);
            if (onXieguRadioEvents!=null){
                onXieguRadioEvents.onXieguRadioAdded(radio);
            }
            xieguRadios.add(radio);
        }
    }

    /**
     * Check if a radio is offline; if so, trigger the offline event
     */
    private void checkOffLineRadios(){
        for (X6100Radio radio:xieguRadios) {
            if (radio.isInvalidNow()){
               if (onXieguRadioEvents!=null){
                   onXieguRadioEvents.onXieguRadioInvalid(radio);
               }
            }
        }
    }

    //***********Getter****************
    public RadioUdpClient getBroadcastClient() {
        return broadcastClient;
    }

    public OnXieguRadioEvents getOnFlexRadioEvents() {
        return onXieguRadioEvents;
    }

    public void setOnXieguRadioEvents(OnXieguRadioEvents onXieguRadioEvents) {
        this.onXieguRadioEvents = onXieguRadioEvents;
    }
    //*********************************


    /**
     * Interface for radio list change events
     */
    public static interface OnXieguRadioEvents{
        void onXieguRadioAdded(X6100Radio flexRadio);
        void onXieguRadioInvalid(X6100Radio flexRadio);
    }

}
