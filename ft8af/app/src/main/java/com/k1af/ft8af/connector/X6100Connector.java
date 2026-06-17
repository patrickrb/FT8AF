package com.k1af.ft8af.connector;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.flex.FlexCommand;
import com.k1af.ft8af.flex.FlexMeterInfos;
import com.k1af.ft8af.flex.FlexMeterList;
import com.k1af.ft8af.flex.FlexRadio;
import com.k1af.ft8af.flex.RadioTcpClient;
import com.k1af.ft8af.flex.VITA;
import com.k1af.ft8af.rigs.BaseRig;
import com.k1af.ft8af.ui.ToastMessage;
import com.k1af.ft8af.x6100.X6100Meters;
import com.k1af.ft8af.x6100.X6100Radio;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Network connector for Xiegu FT8CNs
 * @author BGY70Z
 * @date 2023-12-01
 */
public class X6100Connector extends BaseRigConnector {

    public interface OnWaveDataReceived{
        void OnDataReceived(int bufferLen,float[] buffer);
    }
    //public int maxRfPower;
    //public int maxTunePower;

    private static final String TAG = "X6100Connector";

    private X6100Radio xieguRadio;

    private OnWaveDataReceived onWaveDataReceived;

    private BaseRig baseRig;
    private boolean streamIsOn =false;

    public float maxTXPower=10.0f;
    public MutableLiveData<Float> mutableMaxTxPower = new MutableLiveData<>();


    public X6100Connector(Context context, X6100Radio xiegRadio, int controlMode) {
        super(controlMode);
        this.xieguRadio = xiegRadio;
        setXieguRadioInterface();

    }

    public static short[] byteDataTo16BitData(byte[] buffer){
        short[] data=new short[buffer.length /2];
        for (int i = 0; i < buffer.length/2; i++) {
            short  res = (short) ((buffer[i*2+1] & 0x00FF) | (((short) buffer[i*2]) << 8));
            data[i]=res;
        }
        return data;
    }

    /**
     * Convert raw audio data to 16-bit array data.
     * @param buffer raw audio data (8-bit)
     * @return 16-bit int format array
     */
    private float[] byteDataToFloatData(byte[] buffer){
        float[] data=new float[buffer.length /2];
        for (int i = 0; i < buffer.length/2; i++) {
            int  res = (buffer[i*2] & 0x000000FF) | (((int) buffer[i*2+1]) << 8);
            data[i]=res/32768.0f;
        }
        return data;
    }
    private void setXieguRadioInterface() {
        xieguRadio.setOnReceiveStreamData(new X6100Radio.OnReceiveStreamData() {
            @Override
            public void onReceiveAudio(byte[] data) {
                if (onWaveDataReceived!=null){
                    float[] waveFloat = byteDataToFloatData(data);
                    onWaveDataReceived.OnDataReceived(waveFloat.length,waveFloat);
                }
            }

            @Override
            public void onReceiveIQ(byte[] data) {

            }

            @Override
            public void onReceiveFFT(VITA vita) {

            }

            @Override
            public void onReceiveMeter(X6100Meters meters) {
                maxTXPower= meters.max_power;
                mutableMaxTxPower.postValue(maxTXPower);
            }

            @Override
            public void onReceiveUnKnow(byte[] data) {

            }
        });


        //Event when a command response is received
        xieguRadio.setOnCommandListener(new X6100Radio.OnCommandListener() {
            @Override
            public void onResponse(X6100Radio.XieguResponse response) {
                Log.d(TAG, String.format("onResponse(%s): %s"
                        ,response.xieguCommand.toString(),response.rawData ));
                if (response.xieguCommand == X6100Radio.XieguCommand.STREAM){
                   if (response.resultContent.toUpperCase().contains("PORT=")){//Indicates the stream port is open
                        streamIsOn =true;
                   }
                }

                if (response.resultCode!=0) {//Only show failed commands
                    ToastMessage.show(response.resultContent);
                    Log.e(TAG, "onResponse: "+response.resultContent);
                }

            }
        });

        //Event when status information is received
        xieguRadio.setOnStatusListener(new X6100Radio.OnStatusListener() {
            @Override
            public void onStatus(X6100Radio.XieguResponse response) {
                //Display status messages
                if (response.resultCode == 0){//Indicates the radio status has changed
                    String status[] = response.resultContent.split(" ");
                    for (int i = 0; i < status.length; i++) {
                        if (status[i].startsWith("active_freq")){//Find the frequency status and set the frequency
                            String temp[]=status[i].split("=");
                            if (baseRig != null && temp.length > 1) {
                                try {
                                    baseRig.setFreq(Long.parseLong(temp[1].trim()));
                                } catch (NumberFormatException e) {
                                    Log.e(TAG, "Failed to parse frequency: " + temp[1]);
                                }
                            }
                        }
                    }
                }
                Log.d(TAG, "onStatus: "+response.rawData );
            }
        });



        xieguRadio.setOnTcpConnectStatus(new X6100Radio.OnTcpConnectStatus() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onConnectSuccess(RadioTcpClient tcpClient) {
                ToastMessage.show(String.format(GeneralVariables.getStringFromResource(R.string.init_flex_operation)
                        ,xieguRadio.getModelName()));
                Thread streamThread = new Thread(new Runnable() {//Using a thread here to prevent blocking the TCP object
                    @Override
                    public void run() {
                        long startTime = System.currentTimeMillis();
                        long timeout = 30000; // 30 second timeout
                        while (!streamIsOn && (System.currentTimeMillis() - startTime) < timeout) {//Wait for the radio to open the stream port
                            xieguRadio.commandOpenStream();//Set the UDP port
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException e) {
                                Log.w(TAG, "Stream open thread interrupted");
                                Thread.currentThread().interrupt();
                                break;
                            }
                            //todo Commands are frequently dropped here
                            xieguRadio.commandGetAudioInfo();//Read the 6100 playback parameters
                            //xieguRadio.commandSubGetMeter();//Query meter index numbers
                            xieguRadio.commandSubAllMeter();//Subscribe to meter stream data
                            //xieguRadio.commandSetTxPower(1);//Subscribe to meter stream data
                        }
                        if (!streamIsOn) {
                            Log.w(TAG, "Timed out waiting for stream port to open");
                        }
                    }
                });
                streamThread.setDaemon(true);
                streamThread.start();
            }

            @Override
            public void onConnectFail(RadioTcpClient tcpClient) {
                ToastMessage.show(String.format(GeneralVariables.getStringFromResource
                        (R.string.xiegu_connect_failed),xieguRadio.getModelName()));
            }

            @Override
            public void onConnectionClosed(RadioTcpClient tcpClient) {
                if (baseRig != null && baseRig.getOnRigStateChanged()!=null) {
                    baseRig.getOnRigStateChanged().onDisconnected();
                }
            }
        });

    }


    @Override
    public void sendData(byte[] data) {
        xieguRadio.sendData(data);
    }


    @Override
    public void setPttOn(boolean on) {
        xieguRadio.isPttOn=on;
        xieguRadio.commandPTTOnOff(on);
    }

    @Override
    public void setPttOn(byte[] command) {
    }

    @Override
    public void sendWaveData(float[] data) {
        xieguRadio.sendWaveData(data);
    }


    public void setMaxTXPower(int power){
        maxTXPower=power;
        mutableMaxTxPower.postValue(maxTXPower);
        GeneralVariables.flexMaxRfPower=power;
        xieguRadio.commandSetTxPower(power);//Set transmit power

    }

    //Method for sending A91 data packets
    @Override
    public void sendFt8A91(byte[] a91,float baseFreq){
        Log.d(TAG,String.format("A91:%s", BaseRig.byteToStr(a91)));
        //xieguRadio.commandSendA91(a91,GeneralVariables.volumePercent,baseFreq);
        xieguRadio.commandSendA91(a91,0.95f,baseFreq);
    }

    @Override
    public void setRFVolume(int volume) {
        xieguRadio.commandSetTxVol(volume);
    }

    @Override
    public void connect() {
        super.connect();
        xieguRadio.openAudio();
        xieguRadio.connect();
        xieguRadio.openStreamPort();
    }

    @Override
    public void disconnect() {
        super.disconnect();
        xieguRadio.closeAudio();
        xieguRadio.closeStreamPort();
        xieguRadio.disConnect();
    }

    public OnWaveDataReceived getOnWaveDataReceived() {
        return onWaveDataReceived;
    }

    public void setOnWaveDataReceived(OnWaveDataReceived onWaveDataReceived) {
        this.onWaveDataReceived = onWaveDataReceived;
    }

    public BaseRig getBaseRig() {
        return baseRig;
    }

    public void setBaseRig(BaseRig baseRig) {
        this.baseRig = baseRig;
    }

    @Override
    public boolean isConnected() {
        return xieguRadio.isConnect();
    }

    public X6100Radio getXieguRadio() {
        return xieguRadio;
    }
}
