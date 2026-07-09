package com.k1af.ft8af.flex;
/**
 * Flex radio operations: commands use TCP, data streams use UDP.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import androidx.annotation.NonNull;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.ui.ToastMessage;
import com.k1af.ft8af.wave.FT8Resample;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashSet;


public class FlexRadio {

    public enum FlexMode {LSB, USB, AM, CW, DIGL, DIGU, SAM, FM, NFM, DFM, RTTY, RAW, ARQ, UNKNOW}

    public enum AntMode {ANT1, ANT2, RX_A, XVTA, UNKNOW}


    private static final String TAG = "FlexRadio";
    public static int streamPort = 7051;
    private int flexStreamPort = 4993;
    public boolean isPttOn = false;
    public long streamTxId = 0x084000000;

    public static int getStreamPort() {//Get UDP port for streaming, auto-increment to avoid duplicates
        return ++streamPort;
    }

    //private int streamPort;//Current UDP port for streaming, this instance's port


    /*********************
     * Basic radio info, obtained from the discovery protocol
     *************************/
    private String discovery_protocol_version;//=3.0.0.2
    private String model;//=FLEX-6400
    private String serial;//=1418-6579-6400-0461
    private String version;//=3.3.32.8203
    private String nickname;//=FlexRADIO
    private String callsign;//=FlexRADIO
    private String ip = "";//=192.168.3.86
    private int port = 4992;//=4992//TCP port for controlling the radio
    private String status;//=Available
    private String inUse_ip;//=192.168.3.5
    private String inUse_host;//=DESKTOP-RR564NK.local
    private String max_licensed_version;//=v3
    private String radio_license_id;//=00-1C-2D-05-04-70
    private String requires_additional_license;//=0
    private String fpc_mac;//=
    private int wan_connected;//=1
    private int licensed_clients;//=2
    private int available_clients;//=1
    private int max_panadapters;//=2
    private int available_panadapters;//=1
    private int max_slices;//=2
    private int available_slices;//=1
    private String gui_client_ips;//=192.168.3.5
    private String gui_client_hosts;//=DESKTOP-RR564NK.local
    private String gui_client_programs;//=SmartSDR-Win
    private String gui_client_stations;//=DESKTOP-RR564NK
    private String gui_client_handles;//=0x19EAFA02

    private long lastSeen;//Time of last message
    private boolean isAvailable = true;//Whether the radio is available


    private int commandSeq = 1;//Command sequence number
    private FlexCommand flexCommand;
    private int handle = 0;
    private String commandStr;


    private final StringBuilder buffer = new StringBuilder();//Command buffer
    private final RadioTcpClient tcpClient = new RadioTcpClient();
    private RadioUdpClient streamClient;

    private boolean allFlexRadioStatusEvent = false;
    private String clientID = "";
    private long daxAudioStreamId = 0;
    private int daxTxAudioStreamId = 0;
    private long panadapterStreamId = 0;
    private final HashSet<Long> streamIdSet = new HashSet<>();

    //************************Event handler interfaces*******************************
    private OnReceiveDataListener onReceiveDataListener;//Current data receive event
    private OnTcpConnectStatus onTcpConnectStatus;//TCP connection status change event
    private OnReceiveStreamData onReceiveStreamData;//Stream data receive event handler
    private OnCommandListener onCommandListener;//Command event trigger
    private OnMessageListener onMessageListener;//Message event trigger
    private OnStatusListener onStatusListener;//Status event trigger
    //*****************************************************************
    private AudioTrack audioTrack = null;

    public FlexRadio() {
        updateLastSeen();
    }

    public FlexRadio(String discoverStr) {
        update(discoverStr);
        updateLastSeen();
    }

    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }

    /**
     * Find a specified string parameter in the parameter list
     *
     * @param parameters parameter list
     * @param prefix     parameter name prefix
     * @return parameter value
     */
    private String getParameterStr(String[] parameters, String prefix) {
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].toLowerCase().startsWith(prefix.toLowerCase() + "=")) {
                return parameters[i].substring(prefix.length() + 1);
            }
        }
        //If not found, return empty string
        return "";

    }

    /**
     * Find a specified int parameter in the parameter list
     *
     * @param parameters parameter list
     * @param prefix     parameter name prefix
     * @return parameter value
     */
    private int getParameterInt(String[] parameters, String prefix) {
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].toLowerCase().startsWith(prefix.toLowerCase() + "=")) {
                try {
                    return Integer.parseInt(parameters[i].substring(prefix.length() + 1));
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                    Log.e(TAG, "getParameterInt exception: " + e.getMessage());
                    return 0;
                }
            }
        }
        //If not found, return 0
        return 0;

    }

    /**
     * Update parameters from the discovery protocol
     *
     * @param discoverStr parameters
     */
    public void update(String discoverStr) {
        String[] paras = discoverStr.split(" ");
        discovery_protocol_version = getParameterStr(paras, "discovery_protocol_version");
        model = getParameterStr(paras, "model");
        serial = getParameterStr(paras, "serial");
        version = getParameterStr(paras, "version");
        nickname = getParameterStr(paras, "nickname");
        callsign = getParameterStr(paras, "callsign");
        ip = getParameterStr(paras, "ip");
        port = getParameterInt(paras, "port");
        status = getParameterStr(paras, "status");
        inUse_ip = getParameterStr(paras, "inUse_ip");
        inUse_host = getParameterStr(paras, "inUse_host");
        max_licensed_version = getParameterStr(paras, "max_licensed_version");
        radio_license_id = getParameterStr(paras, "radio_license_id");
        requires_additional_license = getParameterStr(paras, "requires_additional_license");
        fpc_mac = getParameterStr(paras, "fpc_mac");
        wan_connected = getParameterInt(paras, "wan_connected");
        licensed_clients = getParameterInt(paras, "licensed_clients");
        available_clients = getParameterInt(paras, "available_clients");
        max_panadapters = getParameterInt(paras, "max_panadapters");
        available_panadapters = getParameterInt(paras, "available_panadapters");
        max_slices = getParameterInt(paras, "max_slices");
        available_slices = getParameterInt(paras, "available_slices");
        gui_client_ips = getParameterStr(paras, "gui_client_ips");
        gui_client_hosts = getParameterStr(paras, "gui_client_hosts");
        gui_client_programs = getParameterStr(paras, "gui_client_programs");
        gui_client_stations = getParameterStr(paras, "gui_client_stations");
        gui_client_handles = getParameterStr(paras, "gui_client_handles");
    }

    /**
     * Check if this instance is the same radio
     *
     * @param serialNum radio serial number
     * @return true/false
     */
    public boolean isEqual(String serialNum) {
        return this.serial.equalsIgnoreCase(serialNum);
    }


    /**
     * Connect to the radio
     */
    public void connect() {
        this.connect(this.ip, this.port);
    }

    /**
     * Connect to the radio via TCP
     *
     * @param ip   address
     * @param port port
     */
    public void connect(String ip, int port) {
        if (tcpClient.isConnect()) {
            tcpClient.disconnect();
        }
        //Events triggered by TCP connection
        tcpClient.setOnDataReceiveListener(new RadioTcpClient.OnDataReceiveListener() {
            @Override
            public void onConnectSuccess() {
                if (onTcpConnectStatus != null) {
                    onTcpConnectStatus.onConnectSuccess(tcpClient);
                }
            }

            @Override
            public void onConnectFail() {
                if (onTcpConnectStatus != null) {
                    onTcpConnectStatus.onConnectFail(tcpClient);
                }
            }

            @Override
            public void onDataReceive(byte[] buffer) {
                if (onReceiveDataListener != null) {
                    onReceiveDataListener.onDataReceive(buffer);
                }
                onReceiveData(buffer);
            }

            @Override
            public void onConnectionClosed() {
                tcpClient.disconnect();
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.tcp_connect_closed));
            }
        });
        clearBufferData();//Clear buffered command data
        tcpClient.connect(ip, port);//Connect TCP

        //openStreamPort();//Open port for receiving data streams
    }

    /**
     * Handle received audio data
     *
     * @param data audio data
     */
    private void doReceiveAudio(byte[] data) {
        if (onReceiveStreamData != null) {
            onReceiveStreamData.onReceiveAudio(data);
        }
        if (audioTrack != null) {//If audio playback is already open, write audio stream data
            float[] sound = getFloatFromBytes(data);//Length is 256 floats
            audioTrack.write(sound, 0, sound.length, AudioTrack.WRITE_NON_BLOCKING);
        }
    }

    /**
     * Handle received IQ data
     *
     * @param data data
     */
    private void doReceiveIQ(byte[] data) {
        if (onReceiveStreamData != null) {
            onReceiveStreamData.onReceiveIQ(data);
        }
    }

    /**
     * Handle received FFT data
     *
     * @param vita data
     */
    private void doReceiveFFT(VITA vita) {
        if (onReceiveStreamData != null) {
            onReceiveStreamData.onReceiveFFT(vita);
        }
    }

    /**
     * Handle received meter data
     *
     * @param vita data
     */
    private void doReceiveMeter(VITA vita) {
        if (onReceiveStreamData != null) {
            onReceiveStreamData.onReceiveMeter(vita);
        }
    }

    /**
     * Handle received unknown data
     *
     * @param data data
     */
    private void doReceiveUnKnow(byte[] data) {
        if (onReceiveStreamData != null) {
            onReceiveStreamData.onReceiveUnKnow(data);
        }
    }

    /**
     * Open audio in streaming mode. Plays data when audio stream is received.
     */
    public void openAudio() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat myFormat = new AudioFormat.Builder().setSampleRate(24000)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
        int mySession = 0;
        audioTrack = new AudioTrack(attributes, myFormat
                , 24000 * 4, AudioTrack.MODE_STREAM
                , mySession);
        audioTrack.play();
    }

    /**
     * Close audio
     */
    public void closeAudio() {
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
    }

    private synchronized void addStreamIdToSet(long streamId) {
        streamIdSet.add(streamId);
    }

    /**
     * Open port for receiving data streams
     */
    public void openStreamPort() {
        if (streamClient != null) {
            if (streamClient.isActivated()) {
                try {
                    streamClient.setActivated(false);
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        }


        RadioUdpClient.OnUdpEvents onUdpEvents = new RadioUdpClient.OnUdpEvents() {
            @Override
            public void OnReceiveData(DatagramSocket socket, DatagramPacket packet, byte[] data) {
                if (flexStreamPort != packet.getPort()) flexStreamPort = packet.getPort();


                VITA vita = new VITA(data);
                addStreamIdToSet(vita.streamId);

                //Log.e(TAG, String.format("OnReceiveData: stream id:0x%x,class id:0x%x",vita.streamId,vita.classId) );
                switch (vita.classId) {
                    case VITA.FLEX_DAX_AUDIO_CLASS_ID://Audio data
                        //Log.e(TAG, String.format("FLEX_DAX_AUDIO_CLASS_ID stream id:0x%x",vita.streamId ));
                        doReceiveAudio(vita.payload);
                        break;
                    case VITA.FLEX_DAX_IQ_CLASS_ID://IQ data
                        doReceiveIQ(vita.payload);
                        break;
                    case VITA.FLEX_FFT_CLASS_ID://Spectrum data
                        doReceiveFFT(vita);
                        //Log.e(TAG, String.format("OnReceiveData: FFT:%d,STREAM ID:0x%x",vita.payload.length,vita.streamId));
                        break;
                    case VITA.FLEX_METER_CLASS_ID://Meter data
                        //Log.e(TAG, String.format("FLEX_METER_CLASS_ID: stream id:0x%x",vita.streamId ));
                        doReceiveMeter(vita);
                        //Log.e(TAG, String.format("OnReceiveData: METER class id:0x%x,stream id:0x%x,length:%d\n%s"
                        //        ,vita.classId,vita.streamId,vita.payload.length,vita.showPayload() ));
                        break;
                    default://Unknown type data
                        doReceiveUnKnow(data);
                        break;
                }
            }
        };

        //Determine the stream UDP port here
        streamPort = getStreamPort();
        streamClient = new RadioUdpClient(streamPort);
        streamClient.setOnUdpEvents(onUdpEvents);
        try {
            streamClient.setActivated(true);
        } catch (SocketException e) {
            e.printStackTrace();
            Log.d(TAG, "onCreate: " + e.getMessage());
        }


    }

    /**
     * Close the port for receiving data streams
     */
    public synchronized void closeStreamPort() {
        if (streamClient != null) {
            if (streamClient.isActivated()) {
                try {
                    streamClient.setActivated(false);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        streamClient = null;
    }

    /**
     * Disconnect from the radio
     */
    public synchronized void disConnect() {
        if (tcpClient.isConnect()) {
            tcpClient.disconnect();
        }
    }

    /**
     * FlexRadio transmits at 24000 sample rate; also converts mono to stereo
     * @param data audio
     */
    public void sendWaveData(float[] data) {

        float[] temp = new float[data.length * 2];
        //Convert to stereo, 24000 sample rate
        for (int i = 0; i < data.length; i++) {
            temp[i * 2] = data[i];
            temp[i * 2 + 1] = data[i];
        }


        //port=4991;
        //streamTxId=0x084000001;
        // class id=0x00 00 1c 2d 53 4c 01 23????
        //One packet every 5ms? Stereo, 256 floats total
        Log.e(TAG, String.format("sendWaveData: streamid:0x%x,ip:%s,port:%d",streamTxId,ip, port) );
        new Thread(new Runnable() {
            @Override
            public void run() {

                VITA vita = new VITA();

                int count = 0;
                int packetCount=0;
                while (count<temp.length){
                    long now = System.currentTimeMillis() - 1;//Get current time



                    float[] voice=new float[256];//Because it's stereo, 240*2


                    //for (int j = 0; j <3 ; j++) {
                        for (int i = 0; i < voice.length; i++) {
                            voice[i] = temp[count];
                            count++;
                            if (count > temp.length) break;
                        }

                        //byte[] send = vita.audioDataToVita(packetCount, streamTxId,0x534c2d, voice);
                        //daxTxAudioStreamId=0x0084000001&0x00000000ffffffff;
                        //Log.e(TAG, String.format("run: daxTxAudioStreamId:0x%X",daxTxAudioStreamId) );
                        //daxTxAudioStreamId,0x534c0123,
                        vita.streamId=daxTxAudioStreamId;
                        vita.classId = 0x534c0123;
                        vita.classId64 = 0x00001c2d534c0123L;
                        byte[] send = vita.audioFloatDataToVita(packetCount,  voice);
                        packetCount++;
                        try {
                            //Log.e(TAG, String.format("run: send ip:%s, port:%d",ip,4993) );
                            streamClient.sendData(send, ip, 4993);
                        } catch (UnknownHostException e) {
                            throw new RuntimeException(e);
                        }
                        if (count>temp.length) break;
                    //}
                    while (isPttOn) {
                        if (System.currentTimeMillis() - now >= 5) {//5ms per cycle, 256 floats per cycle
                            break;
                        }
                    }
                    if (!isPttOn){
                       // Log.e(TAG, String.format("count：%d,temp.length:%d",count,temp.length ));
                    }

                }


//                for (int i = 0; i < (temp.length / (24 * 2 * 40)); i++) {//40ms worth of data
//                    if (!isPttOn) return;
//                    long now = System.currentTimeMillis() - 1;//Get current time
//
//                    float[] voice = new float[24 * 2 * 10];
//                    for (int j = 0; j < 24 * 2 *10; j++) {
//                        voice[j] = temp[i * 24 * 2 * 10 + j];
//                    }
//                    //Log.e(TAG, "sendWaveData: "+floatToStr(voice) );
//                    //streamTxId=0x84000001;
//                    byte[] send = vita.audioDataToVita(count, streamTxId, voice);
//                    count++;
//
//                    try {
//                        streamClient.sendData(send, ip, port);
//                    } catch (UnknownHostException e) {
//                        throw new RuntimeException(e);
//                    }
//
//                    while (isPttOn) {
//                        if (System.currentTimeMillis() - now >= 41) {//40ms per cycle, 3 packets per cycle, 64 floats per packet
//                            break;
//                        }
//                    }
//                }
            }
        }).start();


        //Set up audio packet sending
        //streamClient.sendData();
    }
    public static String byteToStr(byte[] data) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            s.append(String.format("%02x ", data[i] & 0xff));
        }
        return s.toString();
    }
    @SuppressLint("DefaultLocale")
    public static String floatToStr(float[] data) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            s.append(String.format("%f ", data[i]));
        }
        return s.toString();
    }
    /**
     * Whether the radio is connected
     *
     * @return true/false
     */
    public boolean isConnect() {
        return tcpClient.isConnect();
    }

    public synchronized void sendData(byte[] data) {
        tcpClient.sendByte(data);
    }

    /**
     * Build a command. Sequence number rule: last 3 digits are the command type, sequence / 1000 is the actual sequence number
     *
     * @param command    command type
     * @param cmdContent specific command content
     */
    @SuppressLint("DefaultLocale")
    public void sendCommand(FlexCommand command, String cmdContent) {
        if (tcpClient.isConnect()) {
            commandSeq++;
            flexCommand = command;
            commandStr = String.format("C%d%03d|%s\n", commandSeq, command.ordinal()
                    , cmdContent);
            tcpClient.sendByte(commandStr.getBytes());
            Log.d(TAG, "sendCommand: " + commandStr);
        }
    }

    /**
     * Clear buffer data
     */
    private void clearBufferData() {
        buffer.setLength(0);
    }

    /**
     * Event triggered when data is received; this is data from the TCP connection
     *
     * @param data data
     */
    private void onReceiveData(byte[] data) {
        String s = new String(data);
        if (!s.contains("\n")) {//No newline means the command line hasn't been fully received
            buffer.append(s);
        } else {//Command line(s) received. There may be more than one. Trigger OnReceiveLine here
            String[] commands = s.split("\n");
            if (commands.length > 0) {//Append the first line of received data to previously received command data
                buffer.append(commands[0]);
            }

            //First trigger the buffered data
            doReceiveLineEvent(buffer.toString());
            clearBufferData();
            //Trigger from the second line onwards; skip the last line, check if it ends with newline
            for (int i = 1; i < commands.length - 1; i++) {
                doReceiveLineEvent(commands[i]);
            }

            if (commands.length > 1) {//When data is multi-line, handle the last line
                if (s.endsWith("\n")) {//If it ends with newline or buffer isn't full (fully received), trigger event
                    doReceiveLineEvent(commands[commands.length - 1]);
                } else {//If not ending with newline, the command hasn't been fully received
                    buffer.append(commands[commands.length - 1]);
                }
            }
        }
    }

    /**
     * Event triggered when a data line is received. Can trigger two types of events:
     * 1. Line data event onReceiveLineListener
     * 2. Command event onCommandListener
     *
     * @param line data line
     */
    private void doReceiveLineEvent(String line) {

        FlexResponse response = new FlexResponse(line);
        //Update the handle
        switch (response.responseStyle) {
            case VERSION:
                this.version = response.version;
                break;
            case HANDLE:
                this.handle = response.handle;
                break;
            case RESPONSE:
                if (response.daxStreamId != 0) {
                    this.daxAudioStreamId = response.daxStreamId;
                }
                if (response.panadapterStreamId != 0) {
                    this.panadapterStreamId = response.panadapterStreamId;
                }
                if (response.daxTxStreamId != 0) {
                    this.daxTxAudioStreamId = response.daxTxStreamId;
                    Log.d(TAG, String.format("doReceiveLineEvent: txStreamID:0x%x", daxTxAudioStreamId));
                }

                break;
        }

        if (response.responseStyle == FlexResponseStyle.RESPONSE) {
            if (getCommandStyleFromResponse(response) == FlexCommand.CLIENT_GUI) {
                setClientIDFromResponse(response);//Set CLIENT ID
            }
        }

        //Whether to show status info from other clients
        if (response.responseStyle == FlexResponseStyle.STATUS) {
            if (!allFlexRadioStatusEvent && (!(handle == response.handle || response.handle == 0))) {
                return;
            }

        }

        switch (response.responseStyle) {
            case RESPONSE://When a command response is received
                doCommandResponse(response);//Process some command response messages
                break;
            case STATUS://When a status message is received
                if (onStatusListener != null) {
                    onStatusListener.onStatus(response);
                }
                break;
            case MESSAGE://When a message is received
                if (onMessageListener != null) {
                    onMessageListener.onMessage(response);
                    break;
                }
        }
    }

    /**
     * Process command response messages and trigger the command response event
     *
     * @param response response message
     */
    private void doCommandResponse(FlexResponse response) {
        if (onCommandListener != null) {
            onCommandListener.onResponse(response);
        }
    }


    private void setClientIDFromResponse(FlexResponse response) {
        if (response.responseStyle != FlexResponseStyle.RESPONSE) return;
        if (getCommandStyleFromResponse(response) != FlexCommand.CLIENT_GUI) return;
        if (response.content.equals("0")) {//R3001|0|0BF06C76-EB9E-47E0-B570-EAFB7D556055
            String[] temp = response.rawData.split("\\|");
            if (temp.length < 3) return;
            clientID = temp[2];
        }
    }

    public FlexCommand getCommandStyleFromResponse(FlexResponse response) {
        if (response.responseStyle != FlexResponseStyle.RESPONSE) {
            return FlexCommand.UNKNOW;
        }
        //Log.e(TAG, "getCommandStyleFromResponse: "+response.rawData );

        try {
            int cmdIndex = Integer.parseInt(response.head.substring(response.head.length() - 3));
            if (cmdIndex >= 0 && cmdIndex < FlexCommand.values().length) {
                return FlexCommand.values()[cmdIndex];
            }
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            e.printStackTrace();
            Log.e(TAG, "getCommandStyleFromResponse exception: " + e.getMessage());
        }
        return FlexCommand.UNKNOW;
    }

    /**
     * Check if the radio just went offline. Offline condition: no broadcast packets received within 5 seconds
     *
     * @return true/false
     */
    public boolean isInvalidNow() {
        if (isAvailable) {//If marked online but no packets received for more than 5 seconds, consider just went offline
            isAvailable = System.currentTimeMillis() - lastSeen < 1000 * 5;//Less than 5 seconds means online
            return !isAvailable;
        } else {//If already marked offline, then it didn't just go offline
            return false;
        }
    }

    @NonNull
    @Override
    public String toString() {
        return String.format("FlexRadio{version='%s', handle=%X}", version, handle);
    }

    //**************FlexRadio command wrappers *START***********************
    public synchronized void commandClientDisconnect() {
        sendCommand(FlexCommand.CLIENT_DISCONNECT, "client disconnect");
    }

    public synchronized void commandClientGui() {
        sendCommand(FlexCommand.CLIENT_GUI, "client gui");
    }

    public synchronized void commandClientSetEnforceNetWorkGui() {
        sendCommand(FlexCommand.CLIENT_SET_ENFORCE_NETWORK
                , "client set enforce_network_mtu=1 network_mtu=1450");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSliceRemove(int sliceOder) {
        sendCommand(FlexCommand.SLICE_REMOVE, String.format("slice r %d", sliceOder));
    }

    public synchronized void commandSliceList() {
        sendCommand(FlexCommand.SLICE_LIST, "slice list");
    }

    public synchronized void commandSliceCreate() {
        sendCommand(FlexCommand.SLICE_CREATE_FREQ, "slice create");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSliceTune(int sliceOder, String freq) {
        sendCommand(FlexCommand.SLICE_TUNE, String.format("slice t %d %s", sliceOder, freq));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSliceSetRxAnt(int sliceOder, AntMode antMode) {
        sendCommand(FlexCommand.SLICE_SET_RX_ANT, String.format("slice s %d rxant=%s", sliceOder, antMode.toString()));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSliceSetTxAnt(int sliceOder, AntMode antMode) {
        sendCommand(FlexCommand.SLICE_SET_TX_ANT, String.format("slice s %d txant=%s", sliceOder, antMode.toString()));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSliceSetMode(int sliceOder, FlexMode mode) {
        sendCommand(FlexCommand.SLICE_SET_TX_ANT, String.format("slice s %d mode=%s", sliceOder, mode.toString()));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSliceSetNR(int sliceOder, boolean on) {
        sendCommand(FlexCommand.SLICE_SET_NR, String.format("slice s %d nr=%s", sliceOder, on ? "on" : "off"));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSliceGetError(int sliceOder) {
        sendCommand(FlexCommand.SLICE_GET_ERROR, String.format("slice get_error %d", sliceOder));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSliceSetNB(int sliceOder, boolean on) {
        sendCommand(FlexCommand.SLICE_SET_NB, String.format("slice s %d nb=%s", sliceOder, on ? "on" : "off"));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSetDaxAudio(int channel, int sliceOder, boolean txEnable) {
        sendCommand(FlexCommand.DAX_AUDIO, String.format("dax audio set %d slice=%d tx=%s", channel, sliceOder, txEnable ? "1" : "0"));
        //sendCommand(FlexCommand.DAX_AUDIO, String.format("dax audio set %d tx=%s", channel, txEnable ? "1" : "0"));
        //sendCommand(FlexCommand.DAX_AUDIO, "dax tx 1");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSetDaxIQ(int channel, int panadapter, int rate) {
        sendCommand(FlexCommand.DAX_IQ, String.format("dax iq set %d pan=%d rat=%d", channel, panadapter, rate));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandUdpPort() {
        sendCommand(FlexCommand.CLIENT_UDPPORT, String.format("client udpport %d", streamPort));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandStreamCreateDaxRx(int channel) {
        sendCommand(FlexCommand.STREAM_CREATE_DAX_RX, String.format("stream create type=dax_rx dax_channel=%d", channel));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandStreamCreateDaxTx(int channel) {
        sendCommand(FlexCommand.STREAM_CREATE_DAX_TX, String.format("stream create type=dax_tx dax_channel=%d compression=none", channel));
//        sendCommand(FlexCommand.STREAM_CREATE_DAX_TX, String.format("stream create type=dax_tx compression=none"));
        //sendCommand(FlexCommand.STREAM_CREATE_DAX_TX, String.format("stream create type=remote_audio_tx"));
    }

    public synchronized void commandRemoveDaxStream() {
        sendCommand(FlexCommand.STREAM_REMOVE, String.format("stream remove 0x%x", getDaxAudioStreamId()));
    }

    public synchronized void commandRemoveAllStream() {
        for (Long id : streamIdSet) {
            sendCommand(FlexCommand.STREAM_REMOVE, String.format("stream remove 0x%x", id));
        }
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSetFilter(int sliceOrder, int filt_low, int filt_high) {
        sendCommand(FlexCommand.FILT_SET, String.format("filt %d %d %d", sliceOrder, filt_low, filt_high));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandStartATU() {
        sendCommand(FlexCommand.FILT_SET, "atu start");
    }

    public synchronized void commandGetInfo() {
        sendCommand(FlexCommand.INFO, "info");
    }

    public synchronized void commandPanadapterCreate() {
        sendCommand(FlexCommand.PANADAPTER_CREATE, "display pan c freq=9.5 ant=ANT1 x=800 y=400");
    }

    public synchronized void commandPanadapterRemove() {
        sendCommand(FlexCommand.PANADAPTER_REMOVE, String.format("display pan r 0x%x", panadapterStreamId));
        //sendCommand(FlexCommand.PANADAPTER_REMOVE,"display pan r 0x40000001");
        //sendCommand(FlexCommand.PANADAPTER_REMOVE,"display pan r 0x40000000");
    }

    public synchronized void commandMeterCreateAmp() {
        sendCommand(FlexCommand.METER_CREATE_AMP, "meter create name=AFRAMP type=AMP min=-150.0 max=20.0 units=AMPS");
    }

    public synchronized void commandMeterList() {
        sendCommand(FlexCommand.METER_LIST, "meter list");
    }

    public synchronized void commandSubClientAll() {
        sendCommand(FlexCommand.SUB_CLIENT_ALL, "sub client all");
    }

    public synchronized void commandSubTxAll() {
        sendCommand(FlexCommand.SUB_TX_ALL, "sub client all");
    }

    public synchronized void commandSubAtuAll() {
        sendCommand(FlexCommand.SUB_ATU_ALL, "sub atu all");
    }

    public synchronized void commandSubAmplifierAtuAll() {
        sendCommand(FlexCommand.SUB_amplifier_ALL, "sub amplifier all");
    }

    public synchronized void commandSubMeterAll() {
        sendCommand(FlexCommand.SUB_METER_ALL, "sub meter all");
        //sendCommand(FlexCommand.SUB_METER_ALL,"sub meter 15");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSubMeterById(int id) {
        sendCommand(FlexCommand.SUB_METER_ID, String.format("sub meter %d", id));
    }

    public synchronized void commandSubPanAll() {
        sendCommand(FlexCommand.SUB_PAN_ALL, "sub pan all");
    }

    public synchronized void commandSubSliceAll() {
        sendCommand(FlexCommand.SUB_METER_ALL, "sub slice all");
    }

    public synchronized void commandSubAudioStreamAll() {
        sendCommand(FlexCommand.SUB_AUDIO_STREAM_ALL, "sub audio_stream all");
    }

    public synchronized void commandSubDaxIqAll() {
        sendCommand(FlexCommand.SUB_DAX_IQ_ALL, "sub daxiq all");
    }

    public synchronized void commandSubDaxAll() {
        sendCommand(FlexCommand.SUB_DAX_ALL, "sub dax all");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSetRfPower(int power) {
        sendCommand(FlexCommand.TRANSMIT_MAX_POWER, String.format("transmit set max_power_level=%d", power));
        sendCommand(FlexCommand.TRANSMIT_POWER, String.format("transmit set rfpower=%d", power));
        //sendCommand(FlexCommand.TRANSMIT_MAX_POWER,"info");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSetTunePower(int power) {
        sendCommand(FlexCommand.AUT_TUNE_MAX_POWER, String.format("transmit set tunepower=%d", power));
    }

    public synchronized void commandPTTOnOff(boolean on) {
        if (on) {
            sendCommand(FlexCommand.PTT_ON, "xmit 1");
        } else {
            sendCommand(FlexCommand.PTT_ON, "xmit 0");
        }
    }

    public synchronized void commandTuneTransmitOnOff(boolean on) {
        if (on) {
            sendCommand(FlexCommand.PTT_ON, "transmit tune on");
        } else {
            sendCommand(FlexCommand.PTT_ON, "transmit tune off");
        }
    }


    @SuppressLint("DefaultLocale")
    public synchronized void commandDisplayPan(int x, int y) {
        sendCommand(FlexCommand.DISPLAY_PAN, String.format("display pan set 0x%X xpixels=%d", 0x40000000, x));
        sendCommand(FlexCommand.DISPLAY_PAN, String.format("display pan set 0x%X ypixels=%d", 0x40000000, y));
    }
    //**************FlexRadio command wrappers *END***********************

    @Override
    protected void finalize() throws Throwable {
        closeStreamPort();
        super.finalize();
    }


    //**************Interfaces**********************

    /**
     * When TCP data is received
     */
    public interface OnReceiveDataListener {
        void onDataReceive(byte[] data);
    }

    /**
     * When a command response is received
     */
    public interface OnCommandListener {
        void onResponse(FlexResponse response);
    }

    public interface OnStatusListener {
        void onStatus(FlexResponse response);
    }

    public interface OnMessageListener {
        void onMessage(FlexResponse response);
    }

    /**
     * When TCP connection status changes
     */
    public interface OnTcpConnectStatus {
        void onConnectSuccess(RadioTcpClient tcpClient);

        void onConnectFail(RadioTcpClient tcpClient);
    }

    /**
     * Event when stream data is received
     */
    public interface OnReceiveStreamData {
        void onReceiveAudio(byte[] data);//Audio data

        void onReceiveIQ(byte[] data);//IQ data

        void onReceiveFFT(VITA vita);//Spectrum data

        void onReceiveMeter(VITA vita);//Meter data

        void onReceiveUnKnow(byte[] data);//Unknown data
    }
    //*******************************************


    /**
     * Base class for radio TCP response data
     */
    public static class FlexResponse {
        private static final String TAG = "FlexResponse";
        public FlexResponseStyle responseStyle;
        public String head;//Message header
        public String content;//Message content
        public String exContent;//Extended content; some response messages have 3 parts, this is the 3rd part
        public String rawData;//Raw data
        public int seq_number;//32-bit int, command sequence number
        public int handle;//Handle, 32-bit, hexadecimal
        public String version;//Version info
        public int message_num;//Message number, 32-bit, hex. Bits 24-25 contain severity (0=info, 1=warning, 2=error, 3=fatal)
        public long daxStreamId = 0;
        public int daxTxStreamId = 0;
        public long panadapterStreamId = 0;
        public FlexCommand flexCommand = FlexCommand.UNKNOW;
        public long resultValue = 0;

        public FlexResponse(String line) {
            //Log.e(TAG, "FlexResponse: line--->"+line );
            rawData = line;
            char header;
            if (line.length() > 0) {
                header = line.toUpperCase().charAt(0);
            } else {
                header = 0;
            }
            switch (header) {
                case 'S':
                    responseStyle = FlexResponseStyle.STATUS;
                    getHeadAndContent(line, "\\|");
                    try {
                        this.handle = Integer.parseInt(head.substring(1), 16);//Parse hexadecimal
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                        Log.e(TAG, "FlexResponse status handle exception: " + e.getMessage());
                    }
                    break;
                case 'R':
                    responseStyle = FlexResponseStyle.RESPONSE;
                    getHeadAndContent(line, "\\|");
                    try {
                        seq_number = Integer.parseInt(head.substring(1));//Parse command sequence number
                        int cmdIndex = seq_number % 1000;
                        if (cmdIndex >= 0 && cmdIndex < FlexCommand.values().length) {
                            flexCommand = FlexCommand.values()[cmdIndex];
                        }
                        switch (flexCommand) {
                            case STREAM_CREATE_DAX_RX:
                                this.daxStreamId = getStreamId(line);
                                break;
                            case PANADAPTER_CREATE:
                                this.panadapterStreamId = getStreamId(line);
                                break;
                            case STREAM_CREATE_DAX_TX:
                                this.daxTxStreamId = getStreamId(line);
                                break;
                        }
                        resultValue = Integer.parseInt(content, 16);//Get the command's return value

                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                        Log.e(TAG, "FlexResponse parseInt seq_number exception: " + e.getMessage());
                    }
                    break;
                case 'H':
                    responseStyle = FlexResponseStyle.HANDLE;
                    head = line;
                    content = line;
                    Log.e(TAG, "FlexResponse: handle:" + line.substring(1));
                    try {
                        this.handle = Integer.parseInt(line.substring(1), 16);//Parse hexadecimal
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                        Log.e(TAG, "FlexResponse parseInt handle exception: " + e.getMessage());
                    }

                    break;
                case 'V':
                    responseStyle = FlexResponseStyle.VERSION;
                    head = line;
                    content = line;
                    this.version = line.substring(1);
                    break;
                case 'M':
                    responseStyle = FlexResponseStyle.MESSAGE;
                    getHeadAndContent(line, "\\|");
                    try {
                        //Log.e(TAG, "FlexResponse: "+line );
                        // Guard: substring(2) needs len > 2 to yield a non-empty
                        // token. A truncated/garbled frame ("M"/"M|...") would throw
                        // StringIndexOutOfBoundsException (uncaught -> read-thread
                        // crash); "M1"/"M1|..." would yield "" and a needless
                        // NumberFormatException. Mirrors the 'C' branch's length guard.
                        if (head.length() > 2) {
                            this.message_num = Integer.parseInt(head.substring(2), 16);//Message number, 32-bit, hex
                        }
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                        Log.e(TAG, "FlexResponse parseInt message_num exception: " + e.getMessage());
                    }

                    break;
                case 'C':
                    responseStyle = FlexResponseStyle.COMMAND;
                    getHeadAndContent(line, "\\|");
                    int index = 1;
                    if (head.length() > 2) {
                        if (head.toUpperCase().charAt(1) == 'D') index = 2;
                    }
                    try {
                        seq_number = Integer.parseInt(head.substring(index));
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                        Log.e(TAG, "FlexResponse parseInt seq_number exception: " + e.getMessage());
                    }

                    break;
                case 0:
                default:
                    responseStyle = FlexResponseStyle.UNKNOW;
                    break;
            }
        }

        private int getStreamId(String line) {
            String[] lines = line.split("\\|");
            if (lines.length > 2) {
                if (lines[1].equals("0")) {
                    try {
                        return Integer.parseInt(lines[2], 16);//stream id, hexadecimal
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                        Log.e(TAG, "getDaxStreamId exception: " + e.getMessage());
                    }
                }
            }
            return 0;
        }

        /**
         * Split the message into header and content, assigning them to head and content respectively
         *
         * @param line  message
         * @param split delimiter
         */
        private void getHeadAndContent(String line, String split) {
            String[] temp = line.split(split);
            if (temp.length > 1) {
                head = temp[0];
                content = temp[1];
            } else {
                head = temp.length > 0 ? temp[0] : "";
                content = "";

            }

            if (temp.length > 2) {
                exContent = temp[2];
            } else {
                exContent = "";
            }

        }

        public String resultStatus() {
            if (resultValue == 0) {
                return String.format(GeneralVariables.getStringFromResource(
                        R.string.instruction_success), flexCommand.toString());
            } else {
                return String.format(GeneralVariables.getStringFromResource(
                        R.string.instruction_failed), flexCommand.toString(), rawData);
            }
        }

    }


    // ********Event Getters and Setters*********

    public OnReceiveDataListener getOnReceiveDataListener() {
        return onReceiveDataListener;
    }

    public void setOnReceiveDataListener(OnReceiveDataListener onReceiveDataListener) {
        this.onReceiveDataListener = onReceiveDataListener;
    }

    public OnCommandListener getOnCommandListener() {
        return onCommandListener;
    }

    public void setOnCommandListener(OnCommandListener onCommandListener) {
        this.onCommandListener = onCommandListener;
    }

    public RadioTcpClient getTcpClient() {
        return tcpClient;
    }

    public String getVersion() {
        return version;
    }

    public int getHandle() {
        return handle;
    }

    public String getHandleStr() {
        return String.format("%X", handle);
    }

    public void setHandle(int handle) {
        this.handle = handle;
    }

    public boolean isAllFlexRadioStatusEvent() {
        return allFlexRadioStatusEvent;
    }

    public void setAllFlexRadioStatusEvent(boolean allFlexRadioStatusEvent) {
        this.allFlexRadioStatusEvent = allFlexRadioStatusEvent;
    }

    public String getClientID() {
        return clientID;
    }

    public void setClientID(String clientID) {
        clientID = clientID;
    }

    public int getCommandSeq() {
        return commandSeq * 1000 + flexCommand.ordinal();
    }

    public String getCommandStr() {
        return commandStr;
    }

    public long getDaxAudioStreamId() {
        return daxAudioStreamId;
    }

    public String getDiscovery_protocol_version() {
        return discovery_protocol_version;
    }

    public String getModel() {
        return model;
    }

    public String getSerial() {
        return serial;
    }

    public String getNickname() {
        return nickname;
    }

    public String getCallsign() {
        return callsign;
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public String getStatus() {
        return status;
    }

    public String getInUse_ip() {
        return inUse_ip;
    }

    public String getInUse_host() {
        return inUse_host;
    }

    public String getMax_licensed_version() {
        return max_licensed_version;
    }

    public String getRadio_license_id() {
        return radio_license_id;
    }

    public String getRequires_additional_license() {
        return requires_additional_license;
    }

    public String getFpc_mac() {
        return fpc_mac;
    }

    public int getWan_connected() {
        return wan_connected;
    }

    public int getLicensed_clients() {
        return licensed_clients;
    }

    public int getAvailable_clients() {
        return available_clients;
    }

    public int getMax_panadapters() {
        return max_panadapters;
    }

    public int getAvailable_panadapters() {
        return available_panadapters;
    }

    public int getMax_slices() {
        return max_slices;
    }

    public int getAvailable_slices() {
        return available_slices;
    }

    public String getGui_client_ips() {
        return gui_client_ips;
    }

    public String getGui_client_hosts() {
        return gui_client_hosts;
    }

    public String getGui_client_programs() {
        return gui_client_programs;
    }

    public String getGui_client_stations() {
        return gui_client_stations;
    }

    public String getGui_client_handles() {
        return gui_client_handles;
    }

    public boolean isAvailable() {
        return isAvailable;
    }

    public OnTcpConnectStatus getOnTcpConnectStatus() {
        return onTcpConnectStatus;
    }

    public void setOnTcpConnectStatus(OnTcpConnectStatus onTcpConnectStatus) {
        this.onTcpConnectStatus = onTcpConnectStatus;
    }

    public OnReceiveStreamData getOnReceiveStreamData() {
        return onReceiveStreamData;
    }

    public void setOnReceiveStreamData(OnReceiveStreamData onReceiveStreamData) {
        this.onReceiveStreamData = onReceiveStreamData;
    }

    public OnStatusListener getOnStatusListener() {
        return onStatusListener;
    }

    public void setOnStatusListener(OnStatusListener onStatusListener) {
        this.onStatusListener = onStatusListener;
    }

    public RadioUdpClient getStreamClient() {
        return streamClient;
    }

    public AudioTrack getAudioTrack() {
        return audioTrack;
    }


    public static float[] getFloatFromBytes(byte[] bytes) {
        float[] floats = new float[bytes.length / 4];
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        for (int i = 0; i < floats.length; i++) {
            try {
                floats[i] = dis.readFloat();
            } catch (IOException e) {
                e.printStackTrace();
                Log.e(TAG, "getFloat: ------>>" + e.getMessage());
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

    public void setIp(String ip) {
        this.ip = ip;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
