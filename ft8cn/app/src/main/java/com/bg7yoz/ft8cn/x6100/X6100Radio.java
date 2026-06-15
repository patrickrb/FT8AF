package com.bg7yoz.ft8cn.x6100;

import static com.bg7yoz.ft8cn.flex.VITA.XIEGU_METER_CLASS_ID;
import static com.bg7yoz.ft8cn.flex.VITA.XIEGU_METER_Stream_Id;
import static com.bg7yoz.ft8cn.flex.VITA.XIEGU_PING_CLASS_ID;
import static com.bg7yoz.ft8cn.flex.VITA.XIEGU_PING_Stream_Id;
import static com.bg7yoz.ft8cn.x6100.X6100Radio.XieguResponseStyle.RESPONSE;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.flex.RadioTcpClient;
import com.bg7yoz.ft8cn.flex.RadioUdpClient;
import com.bg7yoz.ft8cn.flex.VITA;
import com.bg7yoz.ft8cn.flex.VitaPacketType;
import com.bg7yoz.ft8cn.flex.VitaTSF;
import com.bg7yoz.ft8cn.flex.VitaTSI;
import com.bg7yoz.ft8cn.rigs.BaseRig;
import com.bg7yoz.ft8cn.rigs.IcomRigConstant;
import com.bg7yoz.ft8cn.ui.ToastMessage;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

public class X6100Radio {
    public enum XieguCommand {
        UNKNOW,//unknown command
        AUDIO,//audio command
        STREAM,//data stream command
        SUB,//subscribe to meter
        UNSUB,//unsubscribe from meter
        A91,//FT8 symbol
        ATU,//automatic antenna tuner
        TUNE,//set frequency
        MODE,//operating mode
        PTT,//PTT operation
        SET//settings operation
    }

    public enum XieguResponseStyle {
        STATUS,//status information, S+HANDLE
        RESPONSE,//command response, R+client command sequence number
        HANDLE,//radio-assigned handle, H+handle (32-bit hex representation)
        VERSION,//version number, V+version number
        COMMAND,//send command, C+sequence number|command
        UNKNOW//unknown response type
    }

    private static final String TAG = "X6100Radio";
    private static int lossCount = 0;
    private static int currentCount = -1;

    private String modelName;//radio model
    private String version;//radio firmware version
    private String rig_ip;//radio IP address
    private String mac;//MAC address
    public boolean isPttOn = false;
    private int control_port = 7002;//radio control port
    private int stream_port = 7003;//radio stream data port
    private int discovery_port = 7001;//discovery protocol port
    private long lastSeen;//time of last message received
    private boolean isAvailable = true;//whether the radio is available
    private final StringBuilder buffer = new StringBuilder();//command buffer
    private final RadioTcpClient tcpClient = new RadioTcpClient();
    private RadioUdpClient streamClient;
    private int commandSeq = 1;//command sequence number
    private XieguCommand xieguCommand;
    private int handle = 0;
    private String commandStr;
    private int frames = 768;//frames per cycle
    private int period = 64;//duration per cycle in milliseconds


    //************************event handling interfaces*******************************
    private OnReceiveDataListener onReceiveDataListener;//event for current received data
    private OnTcpConnectStatus onTcpConnectStatus;//event for TCP connection status changes
    private OnReceiveStreamData onReceiveStreamData;//event for processing received stream data
    private OnCommandListener onCommandListener;//command trigger event
    private OnStatusListener onStatusListener;//status trigger event
    //*****************************************************************
    private AudioTrack audioTrack = null;


    ///******************for meter information display*************
    public MutableLiveData<Long> mutablePing = new MutableLiveData<>();//ping value
    public MutableLiveData<Integer> mutableLossPackets = new MutableLiveData<>();//number of lost packets
    public MutableLiveData<X6100Meters> mutableMeters = new MutableLiveData<>();
    private X6100Meters meters = new X6100Meters();

    private boolean swrAlert = false;
    private boolean alcAlert = false;




    private Timer pingTimer = new Timer();

    private TimerTask pingTask() {
        return new TimerTask() {
            @Override
            public void run() {

                try {
                    if (!streamClient.isActivated() || !isConnect()) {
                        pingTimer.cancel();
                        pingTimer.purge();
                        pingTimer = null;
                        return;
                    }
                    VITA vita = new VITA(VitaPacketType.EXT_DATA_WITH_STREAM
                            , VitaTSI.TSI_OTHER
                            , VitaTSF.TSF_REALTIME
                            , 0
                            , XIEGU_PING_Stream_Id
                            , XIEGU_PING_CLASS_ID);

                    vita.packetCount = 0;
                    vita.packetSize = 7;
                    vita.integerTimestamp = 0;//0 = outgoing packet, 1 = incoming packet
                    vita.fracTimeStamp = System.currentTimeMillis();
                    streamClient.sendData(vita.pingDataToVita(), rig_ip, stream_port);

                } catch (Exception e) {
                    Log.e(TAG, "ping timer error:" + e.getMessage());
                }
            }
        };
    }

    /**
     * Update the last seen time
     */
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }

    public X6100Radio() {
        updateLastSeen();
    }

    public X6100Radio(String s, String ip) {
        mutableLossPackets.postValue(0);
        update(s, ip);
    }

    public void update(String discoverStr, String ip) {
        Log.d(TAG, discoverStr);
        rig_ip = ip;

        String[] paras = discoverStr.replace("\0", " ").split(" ");
        version = getParameterStr(paras, "ft8cn_server_version");
        modelName = getParameterStr(paras, "model");
        mac = getParameterStr(paras, "mac");
        control_port = getParameterInt(paras, "control_port");
        stream_port = getParameterInt(paras, "stream_port");
        discovery_port = getParameterInt(paras, "discovery_port");

        updateLastSeen();
    }

    /**
     * Find a specified string parameter from the parameter list
     *
     * @param parameters parameter list
     * @param prefix     parameter name prefix
     * @return the parameter value
     */
    private String getParameterStr(String[] parameters, String prefix) {
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].toLowerCase().startsWith(prefix.toLowerCase() + "=")) {
                return parameters[i].substring(prefix.length() + 1);
            }
        }
        //if not found, return empty string
        return "";
    }

    /**
     * Find a specified int parameter from the parameter list
     *
     * @param parameters parameter list
     * @param prefix     parameter name prefix
     * @return the parameter value
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
        //if not found, return 0
        return 0;

    }

    /**
     * Check if the radio just went offline. Offline condition: no broadcast data packet received from the radio within 5 seconds.
     *
     * @return whether just went offline
     */
    public boolean isInvalidNow() {
        if (isAvailable) {//if marked as online but no data packet received for more than 5 seconds, consider it just went offline
            isAvailable = System.currentTimeMillis() - lastSeen < 1000 * 5;//less than 5 seconds, consider online
            return !isAvailable;
        } else {//if already marked offline, it is not "just went offline"
            return false;
        }
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getVersion() {
        return version;
    }

    public String getRig_ip() {
        return rig_ip;
    }

    public void setRig_ip(String rig_ip) {
        this.rig_ip = rig_ip;
    }

    public String getMac() {
        return mac;
    }

    public boolean isEqual(String madAddress) {
        return this.mac.equalsIgnoreCase(madAddress);
    }

    /**
     * Connect to the radio for control
     */
    public void connect() {
        this.connect(this.rig_ip, this.control_port);
    }

    /**
     * Connect to the radio via TCP for control
     *
     * @param ip   address
     * @param port port
     */
    public void connect(String ip, int port) {
        if (tcpClient.isConnect()) {
            tcpClient.disconnect();
        }
        //events triggered by TCP connection
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
                if (onReceiveDataListener != null) {//pass data to XieGu6100NetRig here
                    onReceiveDataListener.onDataReceive(buffer);
                }
                onReceiveData(buffer);
            }

            @Override
            public void onConnectionClosed() {
                tcpClient.disconnect();
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.tcp_connect_closed));
                if (onTcpConnectStatus != null) {
                    onTcpConnectStatus.onConnectionClosed(tcpClient);
                }
            }
        });
        clearBufferData();//clear cached command data
        tcpClient.connect(ip, port);//connect TCP

    }

    /**
     * Close the stream data receiving port
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

    @SuppressLint("DefaultLocale")
    public synchronized void commandAtuOn() {
        sendCommand(XieguCommand.ATU, "atu on");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandAtuOff() {
        sendCommand(XieguCommand.ATU, "atu off");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandAtuStart() {
        sendCommand(XieguCommand.ATU, "atu start");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandOpenStream() {
        sendCommand(XieguCommand.STREAM, String.format("stream on %d", stream_port));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSendA91(byte[] a91, float vol, float freq) {
        sendCommand(XieguCommand.A91, String.format("a91 %.2f %.0f %s", vol, freq, BaseRig.byteToStr(a91)));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandGetAudioInfo() {
        sendCommand(XieguCommand.AUDIO, "audio get all");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandGetStreamInfo() {
        sendCommand(XieguCommand.STREAM, "stream get");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSubAllMeter() {
        sendCommand(XieguCommand.SUB, "sub all");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSubGetMeter() {
        sendCommand(XieguCommand.SUB, "sub get");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandUnubMeter() {
        sendCommand(XieguCommand.UNSUB, "unsub");
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandTuneFreq(long freq) {
        sendCommand(XieguCommand.TUNE, String.format("tune %d", freq));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSetMode(String mode, int filter) {
        sendCommand(XieguCommand.TUNE, String.format("mode %s %d", mode, filter));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSetTxPower(int power) {
        sendCommand(XieguCommand.SET, String.format("set tx %d", power));
    }

    @SuppressLint("DefaultLocale")
    public synchronized void commandSetTxVol(int volume) {
        sendCommand(XieguCommand.SET, String.format("set tx_vol %d", volume));
    }

    private void showAlert() {
        Log.e(TAG, String.format("ALC:%f", meters.alc));
        if ((meters.swr >= 3) && GeneralVariables.swr_switch_on) {
            if (!swrAlert) {
                swrAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_high_alert));
            }
        } else {
            swrAlert = false;
        }


        if ((meters.alc > IcomRigConstant.xiegu_alc_alert_max
                || meters.alc < IcomRigConstant.xiegu_alc_alert_min)
                && GeneralVariables.alc_switch_on) {
            if (!alcAlert) {
                alcAlert = true;
                if (meters.alc > IcomRigConstant.xiegu_alc_alert_max) {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.alc_high_alert));
                } else {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.alc_low_alert));
                }
            }
        } else {
            alcAlert = false;
        }
    }

    /**
     * Open the port for receiving data streams
     */
    public void openStreamPort() {
        if (streamClient != null) {
            if (streamClient.isActivated()) {
                try {
                    streamClient.setActivated(false);
                } catch (Exception e) {
                    ToastMessage.show(e.getMessage());
                    e.printStackTrace();
                }

            }
        }


        RadioUdpClient.OnUdpEvents onUdpEvents = new RadioUdpClient.OnUdpEvents() {
            @SuppressLint("DefaultLocale")
            @Override
            public void OnReceiveData(DatagramSocket socket, DatagramPacket packet, byte[] data) {
                VITA vita = new VITA(data);
                if (vita.classId64 == VITA.XIEGU_AUDIO_CLASS_ID) {//audio data

                    //check for packet loss
                    int temp = lossCount;
                    if (currentCount <= -1) {//initialize current packet counter
                        currentCount = vita.packetCount;
                    }
                    if (currentCount > vita.packetCount) {//more than 16 packets were lost
                        lossCount = lossCount + vita.packetCount + 16 - currentCount - 1;
                    } else if (currentCount < vita.packetCount) {//fewer than 16 packets were lost
                        lossCount = lossCount + vita.packetCount - currentCount - 1;
                    }

                    currentCount = vita.packetCount;//reset packet counter
                    if (lossCount > temp) {
                        Log.e(TAG, String.format("Packet loss count: %d", lossCount));
                        for (int i = 0; i < (lossCount - temp); i++) {
                            Log.d(TAG, String.format("Resending data, %d, size:%d", i, vita.payload.length));
                            sendReceivedAudio(vita.payload);//resend current data to the recorder
                        }
                        mutableLossPackets.postValue(lossCount);
                    }
                    //copyVoiceData(vita.payload);//
                    sendReceivedAudio(vita.payload);//send audio to recorder
                    playReceiveAudio(vita.payload);//play current audio data

                } else if (vita.classId64 == XIEGU_PING_CLASS_ID//ping data
                        && vita.streamId == XIEGU_PING_Stream_Id
                        && vita.integerTimestamp == 1) {//ping response packet
                    mutablePing.postValue(System.currentTimeMillis() - vita.fracTimeStamp);
                } else if (vita.classId64 == XIEGU_METER_CLASS_ID//meter data
                        && vita.streamId == XIEGU_METER_Stream_Id) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            meters.update(vita.payload);
                            mutableMeters.postValue(meters);
                            if (isPttOn) {
                                showAlert();
                            } else {
                                alcAlert = false;
                                swrAlert = false;
                            }
                            if (onReceiveStreamData != null) {
                                onReceiveStreamData.onReceiveMeter(meters);
                            }
                        }
                    }).start();

                }


            }
        };

        //determine the stream UDP port here
        streamClient = new RadioUdpClient(stream_port);
        streamClient.setOnUdpEvents(onUdpEvents);
        try {
            streamClient.setActivated(true);
            pingTimer.schedule(pingTask(), 1000, 1000);//start ping timer

        } catch (SocketException e) {
            ToastMessage.show(e.getMessage());
            e.printStackTrace();
            Log.d(TAG, "streamClient: " + e.getMessage());
        }


    }

    /**
     * Action to send received audio data to the recorder
     *
     * @param data audio data
     */
    private void sendReceivedAudio(byte[] data) {
        if (onReceiveStreamData != null) {
            onReceiveStreamData.onReceiveAudio(data);
        }
    }

    /**
     * Process received audio data
     *
     * @param data audio data
     */
    private void playReceiveAudio(byte[] data) {
        if (audioTrack != null) {//if audio playback is open, write audio stream data
            audioTrack.write(data, 0, data.length, AudioTrack.WRITE_NON_BLOCKING);
        }
    }

    /**
     * Disconnect from the radio
     */
    public synchronized void disConnect() {
        if (tcpClient.isConnect()) {
            tcpClient.disconnect();
        }
        if (pingTimer != null) {
            pingTimer.cancel();
            pingTimer.purge();
            pingTimer = null;
        }
    }

    /**
     * Whether the radio is connected
     *
     * @return connection status
     */
    public boolean isConnect() {
        return tcpClient.isConnect();
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


    /**
     * Event triggered when data is received; this is data from the TCP connection
     *
     * @param data the data
     */
    private void onReceiveData(byte[] data) {

        if (data.length > 4) {//check if this is a legacy ICOM command
            if ((data[0] == (byte) 0xfe) && (data[1] == (byte) 0xfe)
                    && ((data[2] == (byte) 0xe0) || data[3] == (byte) 0xe0)) {
                clearBufferData();
                return;
            }
        }
        String s = new String(data);
        if (!s.contains("\n")) {//no newline character, meaning the command line is not fully received
            buffer.append(s);
        } else {//command lines are present, possibly more than one. Trigger OnReceiveLine in this section
            String[] commands = s.split("\n");
            if (commands.length > 0) {//append the first line of received data to previously received command data
                buffer.append(commands[0]);
            }

            //first trigger the buffered data
            doReceiveLineEvent(buffer.toString());
            clearBufferData();
            //trigger from the second line; don't trigger the last line yet, check if it ends with a newline
            for (int i = 1; i < commands.length - 1; i++) {
                doReceiveLineEvent(commands[i]);
            }

            if (commands.length > 1) {//when data is multi-line, handle the last line
                if (s.endsWith("\n")) {//if it ends with a newline or buffer is not full (fully received), trigger event
                    doReceiveLineEvent(commands[commands.length - 1]);
                } else {//if not ending with newline, the command is not fully received
                    buffer.append(commands[commands.length - 1]);
                }
            }
        }
    }


    /**
     * Event triggered when a data line is received. Can trigger two types of events:
     * 1. Line data event onReceiveLineListener
     * 2. Command event onCommandListener
     * <p>
     * //* @param line the data line
     */
    private void doReceiveLineEvent(String line) {

        XieguResponse response = new XieguResponse(line);
        //update the handle
        switch (response.responseStyle) {
            case VERSION:
                this.version = response.head.substring(1);
                break;
            case HANDLE:
                this.handle = Integer.parseInt(response.head.substring(1), 16);
                break;
            case RESPONSE:
                if (XieguCommand.AUDIO == response.xieguCommand) {//response information for audio command
                    setAudioInfo(response.resultContent);
                }
                if (onCommandListener != null) {
                    onCommandListener.onResponse(response);
                }
                break;
            case STATUS:

                if (response.resultCode == 0) {//radio status has changed
                    String status[] = response.resultContent.split(" ");
                    for (int i = 0; i < status.length; i++) {//find PTT state and set PTT
                        if (status[i].startsWith("ptt")) {//check PTT
                            String temp[] = status[i].split("=");
                            if (temp.length > 1) {
                                isPttOn = temp[1].equalsIgnoreCase("on");
                            }
                        }

                        if (status[i].startsWith("play_volume")) {//check play volume
                            String temp[] = status[i].split("=");
                            if (temp.length > 1) {
                                try {
                                    float vol = Integer.parseInt(temp[1].trim()) * 1.0f / 100f;
                                    GeneralVariables.volumePercent = vol;
                                    GeneralVariables.mutableVolumePercent.postValue(vol);
                                } catch (NumberFormatException e) {
                                    Log.e(TAG, "Error parsing play_volume: " + temp[1], e);
                                }
                            }
                        }
                    }
                }

                if (onStatusListener != null) {
                    onStatusListener.onStatus(response);
                }
                break;
        }

    }

    /**
     * Get the radio's audio information
     *
     * @param result the returned information
     */
    private void setAudioInfo(String result) {
        String[] keys = result.split(" ");
        for (int i = 0; i < keys.length; i++) {
            String[] val = keys[i].split("=");
            if (val.length < 2) continue;
            try {
                if (val[0].equalsIgnoreCase("period")) period = Integer.parseInt(val[1]) / 1000;
                if (val[0].equalsIgnoreCase("frames")) frames = Integer.parseInt(val[1]);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Error parsing audio info: " + keys[i], e);
            }
        }
        Log.d(TAG, String.format("set audio para:frames=%d,period=%d", frames, period));
    }

    public synchronized void sendData(byte[] data) {
        tcpClient.sendByte(data);
    }

    /**
     * Build a command. Command sequence number rule: last 3 digits are the command type; sequence number divided by 1000 is the actual sequence number.
     *
     * @param command    the command type
     * @param cmdContent the command content
     */
    @SuppressLint("DefaultLocale")
    public void sendCommand(XieguCommand command, String cmdContent) {
        if (tcpClient.isConnect()) {
            commandSeq++;
            xieguCommand = command;
            commandStr = String.format("C%05d%03d|%s\n", commandSeq, command.ordinal()
                    , cmdContent);
            tcpClient.sendByte(commandStr.getBytes());
            Log.d(TAG, "sendCommand: " + commandStr);
        }
    }

    public synchronized void commandPTTOnOff(boolean on) {
        if (on) {
            sendCommand(XieguCommand.PTT, "ptt on");
        } else {
            sendCommand(XieguCommand.PTT, "ptt off");
        }
    }

    /**
     * Transmit sampling rate is 12000 Hz, mono, 16-bit
     *
     * @param data audio data
     */
    public void sendWaveData(float[] data) {
        Log.d(TAG, String.format("send wav data,len:%d....", data.length));
        short[] temp = new short[data.length];
        //incoming audio is LPCM, 32-bit float, 12000Hz
        //X6100 audio format is LPCM 16-bit int, 12000Hz
        //need to convert float to 16-bit int
        for (int i = 0; i < data.length; i++) {
            float x = data[i];
            if (x > 1.0)
                x = 1.0f;
            else if (x < -1.0)
                x = -1.0f;
            temp[i] = (short) (x * 32767.0);
        }
        short[] payload = new short[frames];

        VITA vita = new VITA(VitaPacketType.EXT_DATA_WITH_STREAM
                , VitaTSI.TSI_OTHER
                , VitaTSF.TSF_SAMPLE_COUNT
                , 0
                , 0x84000001
                , 0x584945475500A1L);

        vita.packetCount = 0;
        vita.integerTimestamp = 0;
        vita.fracTimeStamp = payload.length * 2L;


        try {
            int count = 0;
            int a = 0;
            while (count < temp.length) {
                long now = System.currentTimeMillis();//get current time
                Arrays.fill(payload, (short) 0);//clear array

                if (!isPttOn) break;
                if (data.length - count > frames) {
                    System.arraycopy(temp, count, payload, 0, frames);
                    count = count + frames;
                } else {
                    System.arraycopy(temp, count, payload, 0, temp.length - count);
                    count = temp.length;
                }
                streamClient.sendData(vita.audioShortDataToVita(vita.packetCount, payload), rig_ip, stream_port);
                while (isPttOn) {
                    if (System.currentTimeMillis() - now >= period) {//64ms per cycle
                        break;
                    }
                }
                a++;
            }


        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Clear buffered data
     */
    private void clearBufferData() {
        buffer.setLength(0);
    }

    /**
     * Open audio in streaming mode. Play data when audio stream is received.
     */
    public void openAudio() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat myFormat = new AudioFormat.Builder().setSampleRate(12000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
        int mySession = 0;
        audioTrack = new AudioTrack(attributes, myFormat
                //, 12000 * 4, AudioTrack.MODE_STREAM
                , 768 * 2 * 4, AudioTrack.MODE_STREAM//X6100 audio cycle is 64ms, totaling 768*2 bytes
                , mySession);
        audioTrack.play();
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

    public OnCommandListener getOnCommandListener() {
        return onCommandListener;
    }

    public void setOnCommandListener(OnCommandListener onCommandListener) {
        this.onCommandListener = onCommandListener;
    }

    public OnReceiveDataListener getOnReceiveDataListener() {
        return onReceiveDataListener;
    }

    public void setOnReceiveDataListener(OnReceiveDataListener onReceiveDataListener) {
        this.onReceiveDataListener = onReceiveDataListener;
    }


//**************various interfaces**********************

    /**
     * When TCP data is received
     */
    public interface OnReceiveDataListener {
        void onDataReceive(byte[] data);
    }

    /**
     * When TCP connection status changes
     */
    public interface OnTcpConnectStatus {
        void onConnectSuccess(RadioTcpClient tcpClient);

        void onConnectFail(RadioTcpClient tcpClient);

        void onConnectionClosed(RadioTcpClient tcpClient);
    }

    /**
     * Event when stream data is received
     */
    public interface OnReceiveStreamData {
        void onReceiveAudio(byte[] data);//audio data

        void onReceiveIQ(byte[] data);//IQ data

        void onReceiveFFT(VITA vita);//spectrum data

        void onReceiveMeter(X6100Meters meters);//meter data

        void onReceiveUnKnow(byte[] data);//unknown data
    }

    /**
     * When a command response is received
     */
    public interface OnCommandListener {
        void onResponse(XieguResponse response);
    }

    public interface OnStatusListener {
        void onStatus(XieguResponse response);
    }
    //*******************************************


    /**
     * Base class for radio TCP response data
     */
    public static class XieguResponse {
        private static final String TAG = "XieguResponse";
        public XieguResponseStyle responseStyle;
        public String head;//message header
        public int resultCode;//message code
        public String resultContent;//extended message; some response messages are split into 3 parts, this is the 3rd part
        public String rawData;//raw data
        public int seq_number;//32-bit int, command sequence number

        public XieguCommand xieguCommand = XieguCommand.UNKNOW;


        public XieguResponse(String line) {
            rawData = line;
            char header;
            if (line.length() > 0) {
                header = line.toUpperCase().charAt(0);
            } else {
                header = 0;
            }
            switch (header) {
                case 'S':
                    responseStyle = XieguResponseStyle.STATUS;
                    getHeadAndContent(line, "\\|");//get the command's header, value, and content

                    break;
                case 'R':
                    responseStyle = RESPONSE;
                    getHeadAndContent(line, "\\|");
                    try {
                        seq_number = Integer.parseInt(head.substring(1));//parse command sequence number
                        int cmdIndex = seq_number % 1000;
                        if (cmdIndex >= 0 && cmdIndex < XieguCommand.values().length) {
                            xieguCommand = XieguCommand.values()[cmdIndex];
                        }
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                        Log.e(TAG, "XieguResponse parseInt seq_number exception: " + e.getMessage());
                    }
                    break;
                case 'H':
                    responseStyle = XieguResponseStyle.HANDLE;
                    head = line;
                    resultContent = line;
                    Log.d(TAG, "XieguResponse: handle:" + line.substring(1));

                    break;
                case 'V':
                    responseStyle = XieguResponseStyle.VERSION;
                    head = line;
                    resultContent = line;
                    break;

                case 0:
                default:
                    responseStyle = XieguResponseStyle.UNKNOW;
                    break;
            }
        }


        /**
         * Split the message into header and content, assigning them to head and content respectively
         *
         * @param line  the message
         * @param split the delimiter
         */
        private void getHeadAndContent(String line, String split) {
            String[] temp = line.split(split);
            resultCode = -1;
            head = temp.length > 0 ? temp[0] : "";
            if (temp.length > 1) {
                try {
                    resultCode = Integer.parseInt(temp[1]);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Error parsing resultCode: " + temp[1], e);
                }
            }

            if (temp.length > 2) {
                resultContent = temp[2];
            } else {
                resultContent = "";
            }
        }
    }

}
