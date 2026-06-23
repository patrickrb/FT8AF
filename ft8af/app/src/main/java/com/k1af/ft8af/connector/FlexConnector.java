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
import com.k1af.ft8af.ui.ToastMessage;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * Connector for Flex network connections
 * @author BGY70Z
 * @date 2023-03-20
 */
public class FlexConnector extends BaseRigConnector {
    public MutableLiveData<FlexMeterList> mutableMeterList=new MutableLiveData<>();
    public FlexMeterInfos flexMeterInfos =new FlexMeterInfos("");
    public FlexMeterList meterList=new FlexMeterList();
    public interface OnWaveDataReceived{
        void OnDataReceived(int bufferLen,float[] buffer);
    }
    /** Fired when the Flex link is up (TCP connected + GUI client created) or fails,
     * so MainViewModel can surface the CAT connection state + a success/failure toast. */
    public interface OnConnectionResult{
        void onConnected();
        void onFailed();
    }
    public int maxRfPower;
    public int maxTunePower;
    // True once at least one meter value packet has arrived, so the HUD can tell
    // "no meter stream yet" apart from genuine zero readings.
    public volatile boolean meterDataReceived = false;

    private static final String TAG = "FlexConnector";

    private FlexRadio flexRadio;

    private OnWaveDataReceived onWaveDataReceived;
    private OnConnectionResult onConnectionResult;

    public void setOnConnectionResult(OnConnectionResult listener){
        this.onConnectionResult = listener;
    }


    public FlexConnector(Context context, FlexRadio flexRadio, int controlMode) {
        super(controlMode);
        this.flexRadio = flexRadio;
        maxTunePower=GeneralVariables.flexMaxTunePower;
        maxRfPower=GeneralVariables.flexMaxRfPower;
        setFlexRadioInterface();
        //connect();
    }

    public static int[] byteDataTo16BitData(byte[] buffer){
        int[] data=new int[buffer.length /2];
        for (int i = 0; i < buffer.length/2; i++) {
            int  res = (buffer[i*2+1] & 0x000000FF) | (((int) buffer[i*2]) << 8);
            data[i]=res;
        }
        return data;
    }


    private void setFlexRadioInterface() {
        flexRadio.setOnReceiveStreamData(new FlexRadio.OnReceiveStreamData() {
            @Override
            public void onReceiveAudio(byte[] data) {
                if (onWaveDataReceived!=null){
                    float[] buffer=getMonoFloatFromBytes(data);//Convert 24000 to 12000 sample rate, stereo to mono
                    onWaveDataReceived.OnDataReceived(buffer.length,buffer);
                }
            }

            @Override
            public void onReceiveIQ(byte[] data) {

            }

            @Override
            public void onReceiveFFT(VITA vita) {
                //if (vita.streamId==0x40000000) {
                //    mutableVita.postValue(vita.showHeadStr() + "\n" + vita.showPayloadHex());
                //}
            }

            @Override
            public void onReceiveMeter(VITA vita) {
                //Log.e(TAG, "onReceiveMeter: "+vita.showPayloadHex() );
                meterList.setMeters(vita.payload,flexMeterInfos);

                mutableMeterList.postValue(meterList);
                if (!meterDataReceived) {
                    meterDataReceived = true;
                    GeneralVariables.fileLog("FlexMeter: first meter packet received");
                }
            }

            @Override
            public void onReceiveUnKnow(byte[] data) {

            }
        });


        //Event when a command response is received
        flexRadio.setOnCommandListener(new FlexRadio.OnCommandListener() {
            @Override
            public void onResponse(FlexRadio.FlexResponse response) {
                if (response.resultValue!=0) {//Only show failed commands
                    //ToastMessage.show(response.resultStatus());
                    //Log.e(TAG, "onResponse: "+response.resultStatus());
                }

                Log.d(TAG, "onResponse: command:"+response.flexCommand.toString());
                //Log.e(TAG, "onResponse: "+response.resultStatus());
                Log.e(TAG, "onResponse: "+response.rawData );

                if (response.flexCommand== FlexCommand.METER_LIST){
                    //FlexMeters flexMeters=new FlexMeters(response.exContent);
                    flexMeterInfos.setMeterInfos(response.exContent);
                    flexRadio.commandSubMeterAll();//Subscribe to all meter messages
                    //flexMeters.getAllMeters();
                    //Log.e(TAG, "onResponse: ----->>>"+flexMeters.getAllMeters() );
                }
                if (response.flexCommand==FlexCommand.STREAM_CREATE_DAX_TX){
                    flexRadio.streamTxId=response.daxTxStreamId;
                }

//                if (response.flexCommand== FlexCommand.METER_LIST){
//                    Log.e(TAG, "onResponse: ."+response.rawData.replace("#","\n") );
//                }
            }
        });

        //Event when status information is received
        flexRadio.setOnStatusListener(new FlexRadio.OnStatusListener() {
            @Override
            public void onStatus(FlexRadio.FlexResponse response) {
                //Display status messages
                //ToastMessage.show(response.content);
                Log.e(TAG, "onStatus: "+response.rawData );
            }
        });



        flexRadio.setOnTcpConnectStatus(new FlexRadio.OnTcpConnectStatus() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onConnectSuccess(RadioTcpClient tcpClient) {
                ToastMessage.show(String.format(GeneralVariables.getStringFromResource(R.string.init_flex_operation)
                        ,flexRadio.getModel()));

                flexRadio.commandClientDisconnect();//Disconnect all previous connections
                flexRadio.commandClientGui();//Create GUI

                flexRadio.commandSubDaxAll();//Register all DAX streams



                flexRadio.commandClientSetEnforceNetWorkGui();//Configure network MTU settings

                //flexRadio.commandSliceList();//List slices
                flexRadio.commandSliceCreate();//Create slice





                //todo To prevent stream ports from not being released, change the port?
                //FlexRadio.streamPort++;

                flexRadio.commandUdpPort();//Set UDP port


                flexRadio.commandStreamCreateDaxRx(1);//Create stream data to DAX channel 1
                flexRadio.commandStreamCreateDaxTx(1);//Create stream data to DAX channel 1

                flexRadio.commandSetDaxAudio(1, 0, true);//Enable DAX

                //TODO Should we set this??? dax tx T or dax tx 1
                flexRadio.commandSliceTune(0,String.format("%.3f",GeneralVariables.band/1000000f));
                flexRadio.commandSliceSetMode(0, FlexRadio.FlexMode.DIGU);//Set operating mode
                flexRadio.commandSetFilter(0, 0, 3000);//Set filter to 3000 Hz


                flexRadio.commandMeterList();//List the meters
                //flexRadio.commandSubMeterAll();//Subscription command moved to the response handling section

                setMaxRfPower(maxRfPower);//set transmit power
                setMaxTunePower(maxTunePower);//Set tune power

                //flexRadio.commandSubMeterById(5);//List a specific meter

                //flexRadio.commandSliceSetNR(0, true);
                //flexRadio.commandSliceSetNB(0, true);

                //flexRadio.commandDisplayPan(10, 10);
                //flexRadio.commandSetFilter(0,0,3000);
                // flexRadio.sendCommand(FlexCommand.FILT_SET, "filt 0 0 3000");
                //flexRadio.commandMeterCreateAmp();
                //flexRadio.commandMeterList();


                //flexRadio.sendCommand(FlexCommand.INFO, "info");
                //flexRadio.commandGetInfo();
                //flexRadio.commandSliceGetError(0);

                //flexRadio.sendCommand(FlexCommand.SLICE_GET_ERROR, "slice get_error 0");
                //flexRadio.sendCommand(FlexCommand.REMOTE_RADIO_RX_ON, "remote_audio rx_on on");
                //
                //flexRadio.sendCommand("c1|client gui\n");
                //playData();

                if (onConnectionResult != null) onConnectionResult.onConnected();
            }

            @Override
            public void onConnectFail(RadioTcpClient tcpClient) {
                ToastMessage.show(String.format(GeneralVariables.getStringFromResource
                        (R.string.flex_connect_failed),flexRadio.getModel()));
                if (onConnectionResult != null) onConnectionResult.onFailed();
            }
        });

    }
    public void setMaxRfPower(int power){
        maxRfPower=power;
        GeneralVariables.flexMaxRfPower=power;
        flexRadio.commandSetRfPower(maxRfPower);//set transmit power

    }
    public void setMaxTunePower(int power){
        maxTunePower=power;
        GeneralVariables.flexMaxTunePower=power;
        flexRadio.commandSetTunePower(maxTunePower);//Set tune power

    }
    public void startATU(){
        flexRadio.commandStartATU();
    }
    public void tuneOnOff(boolean on){
        flexRadio.commandTuneTransmitOnOff(on);
    }
    public void subAllMeters(){
        if (flexMeterInfos.size()==0) {
            //todo Can we avoid using commandMeterList()?
            flexRadio.commandMeterList();//List the meters
            flexRadio.commandSubMeterAll();//Display all meter messages
        }
    }

    /**
     * Force a meter (re)subscription regardless of cached meter-info state. The
     * connect-time subscription is gated on a METER_LIST response arriving; this
     * unconditional path is what the meters HUD calls when it opens, so meters
     * stream even if that response was missed. Safe to call repeatedly.
     */
    public void requestMeterStream(){
        GeneralVariables.fileLog("FlexMeter: requestMeterStream (subscribing)");
        flexRadio.commandMeterList();
        flexRadio.commandSubMeterAll();
    }

    @Override
    public void sendData(byte[] data) {
        flexRadio.sendData(data);
    }


    @Override
    public void setPttOn(boolean on) {
        flexRadio.isPttOn=on;
        flexRadio.commandPTTOnOff(on);
    }

    @Override
    public void setPttOn(byte[] command) {
        //cableSerialPort.sendData(command);//send PTT via CAT command
    }

    @Override
    public void sendWaveData(float[] data) {
        //Log.e(TAG, "sendWaveData: flexConnector:"+data.length );
        flexRadio.sendWaveData(data);
    }

    @Override
    public void connect() {
        super.connect();
        flexRadio.openAudio();
        flexRadio.connect();
        flexRadio.openStreamPort();
    }

    @Override
    public void disconnect() {
        super.disconnect();
        flexRadio.closeAudio();
        flexRadio.closeStreamPort();
        flexRadio.disConnect();
    }

    public OnWaveDataReceived getOnWaveDataReceived() {
        return onWaveDataReceived;
    }

    public void setOnWaveDataReceived(OnWaveDataReceived onWaveDataReceived) {
        this.onWaveDataReceived = onWaveDataReceived;
    }

    /**
     * Get mono data; convert 24000Hz sample rate to 12000Hz, convert stereo to mono.
     * @param bytes Raw audio data
     * @return Mono data
     */
    public static float[] getMonoFloatFromBytes(byte[] bytes) {
        float[] floats = new float[bytes.length / 16];
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        for (int i = 0; i < floats.length; i++) {
            try {
                float f1,f2;
                f1=dis.readFloat();
                dis.readFloat();//discard one channel
                f2=dis.readFloat();
                floats[i] = Math.max(f1,f2);//take maximum value
                dis.readFloat();//discard 1 channel
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
        try {
            dis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return floats;
    }

    @Override
    public boolean isConnected() {
        return flexRadio.isConnect();
    }
}
