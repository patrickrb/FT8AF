package com.k1af.ft8af.icom;

import android.util.Log;

/**
 * ICom packet parsing and assembly.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */
public class IComPacketTypes {
    private static final String TAG = "IComPacketTypes";

    public static final int TX_BUFFER_SIZE = 0xf0;
    public static final int XIEGU_TX_BUFFER_SIZE = 0x96;
    /**
     * Packet lengths by type
     */
    public static final int CONTROL_SIZE = 0x10;
    public static final int WATCHDOG_SIZE = 0x14;
    public static final int PING_SIZE = 0x15;
    public static final int OPENCLOSE_SIZE = 0x16;
    public static final int RETRANSMIT_RANGE_SIZE = 0x18;//Retransmit range
    public static final int TOKEN_SIZE = 0x40;
    public static final int STATUS_SIZE = 0x50;
    public static final int LOGIN_RESPONSE_SIZE = 0x60;
    public static final int LOGIN_SIZE = 0x80;
    public static final int CONNINFO_SIZE = 0x90;
    public static final int CAPABILITIES_SIZE = 0x42;//Capabilities packet
    public static final int RADIO_CAP_SIZE = 0x66;
    public static final int CAP_CAPABILITIES_SIZE = 0xA8;//0x42+0x66
    public static final int AUDIO_HEAD_SIZE = 0x18;//Audio packet header is 0x10+0x08, followed by audio data


    public static final short CMD_NULL = 0x00;//Null command
    public static final short CMD_RETRANSMIT = 0x01;//Retransmit request; seq is the sequence number to retransmit
    public static final short CMD_ARE_YOU_THERE = 0x03;//Are you there? seq must be 0
    public static final short CMD_I_AM_HERE = 0x04;//I am here response
    public static final short CMD_DISCONNECT = 0x05;//Disconnect
    public static final short CMD_ARE_YOU_READY = 0x06;//Are you ready? seq=1
    public static final short CMD_I_AM_READY = 0x06;//Radio replies it is ready
    public static final short CMD_PING = 0x07;//Ping; seq has its own sequence

    public static final byte TOKEN_TYPE_DELETE = 0x01;//Token delete packet
    public static final byte TOKEN_TYPE_CONFIRM = 0x02;//Token confirm packet
    public static final byte TOKEN_TYPE_DISCONNECT = 0x04;//Disconnect CI-V and audio streams
    public static final byte TOKEN_TYPE_RENEWAL = 0x05;//Token renewal


    public static final long PING_PERIOD_MS = 500;//Ping timer period
    public static final long ARE_YOU_THERE_PERIOD_MS = 500;//Radio discovery timer period
    public static final long IDLE_PERIOD_MS = 100;//Idle packet timer period
    public static final long TOKEN_RENEWAL_PERIOD_MS = 60000;//Token renewal timer period
    public static final long PURGE_MILLISECONDS = 10000;//Maximum data buffer retention time is 10 seconds
    public static final long OPEN_CLOSE_PERIOD_MS = 500;//CI-V command periodically sends open command to keep port open
    public static final long WATCH_DOG_PERIOD_MS = 500;//Watchdog monitoring data reception status
    public static final long WATCH_DOG_ALERT_MS = 2000;//Threshold for triggering data reception alert
    public static final long METER_TIMER_PERIOD_MS = 500;//Meter check timer period

    public static final int AUDIO_SAMPLE_RATE = 12000;//Audio sample rate used by FT8CN for iCom
    public static final int XIEGU_AUDIO_SAMPLE_RATE = 48000;//Audio sample rate used by FT8CN for Xiegu

    public static final short CODEC_ALL_SUPPORTED = 0x018b;
    public static final short CODEC_ONLY_24K = 0x0100;
    public static final short CODEC_ONLY_12K = 0x0080;
    public static final short CODEC_ONLY_441K = 0x0040;//44.1k
    public static final short CODEC_ONLY_2205K = 0x0020;//22.05k
    public static final short CODEC_ONLY_11025K = 0x0010;//11.025k
    public static final short CODEC_ONLY_48K = 0x0008;
    public static final short CODEC_ONLY_32K = 0x0004;
    public static final short CODEC_ONLY_16K = 0x0002;
    public static final short CODEC_ONLY_8K = 0x0001;

    public static class IcomCodecType {
        public static final byte ULAW_1CH_8BIT = 0x01;
        public static final byte LPCM_1CH_8BIT = 0x02;
        public static final byte LPCM_1CH_16BIT = 0x04;//FT8CN recommended value
        public static final byte PCM_2CH_8BIT = 0x08;
        public static final byte LPCM_2CH_16BIT = 0x10;
        public static final byte ULAW_2CH_8BIT = 0x20;
        public static final byte OPUS_CH1 = 0x40;
        public static final byte OPUS_CH2 = (byte) 0x80;
    }


    /**
     * Control command packet. Content-free packet for simple communication and retransmit requests, 0x10
     */
    public static class ControlPacket {
        /**
         * Convert control packet to byte stream
         *
         * @return byte stream
         */
        public static byte[] toBytes(short type, short seq, int sentId, int rcvdId) {
            byte[] packet = new byte[CONTROL_SIZE];
            System.arraycopy(intToBigEndian(CONTROL_SIZE), 0, packet, 0, 4);
            System.arraycopy(shortToBigEndian(type), 0, packet, 4, 2);
            System.arraycopy(shortToBigEndian(seq), 0, packet, 6, 2);
            System.arraycopy(intToBigEndian(sentId), 0, packet, 8, 4);
            System.arraycopy(intToBigEndian(rcvdId), 0, packet, 12, 4);
            return packet;
        }

        public static byte[] idlePacketData(short seq, int sendid, int rcvdId) {
            return toBytes(CMD_NULL, seq, sendid, rcvdId);
        }

        public static boolean isControlPacket(byte[] data) {//0x10
            return data.length == CONTROL_SIZE && readIntBigEndianData(data, 0x00) == CONTROL_SIZE;
        }

        public static short getType(byte[] data) {
            if (data.length < CONTROL_SIZE) return 0;
            return readShortBigEndianData(data, 0x04);
        }

        public static short getSeq(byte[] data) {
            if (data.length < CONTROL_SIZE) return 0;
            return readShortBigEndianData(data, 0x06);
        }

        public static int getSentId(byte[] data) {
            if (data.length < CONTROL_SIZE) return 0;
            return readIntBigEndianData(data, 0x08);
        }

        public static int getRcvdId(byte[] data) {
            if (data.length < CONTROL_SIZE) return 0;
            return readIntBigEndianData(data, 0x0c);
        }

        public static void setRcvdId(byte[] data, int rcvdId) {
            System.arraycopy(intToBigEndian(rcvdId), 0x00, data, 0x0c, 4);
        }
    }

    public static class AudioPacket {
        /**
         * quint32 len;        // 0x00
         * quint16 type;       // 0x04
         * quint16 seq;        // 0x06
         * quint32 sentid;     // 0x08
         * quint32 rcvdid;     // 0x0c
         * <p>
         * <p>
         * //When receiving, ident=0x8116, 8106, 8006
         * quint16 ident;      // 0x10 When transmitting: if datalen=0xa0, ident=0x9781, otherwise ident=0x0080;
         * quint16 sendseq;    // 0x12
         * quint16 unused;     // 0x14
         * quint16 datalen;    // 0x16
         */
        public static boolean isAudioPacket(byte[] data) {
            if (data.length < AUDIO_HEAD_SIZE) return false;
            return data.length - AUDIO_HEAD_SIZE == readShortData(data, 0x16);
        }

        public static short getDataLen(byte[] data) {
            return readShortData(data, 0x16);
        }

        public static byte[] getAudioData(byte[] data) {
            byte[] audio = new byte[data.length - AUDIO_HEAD_SIZE];
            System.arraycopy(data, 0x18, audio, 0, audio.length);
            return audio;
        }

        public static byte[] getTxAudioPacket(byte[] audio, short seq, int sentid, int rcvdid, short sendSeq) {
            byte[] packet = new byte[audio.length + AUDIO_HEAD_SIZE];
            System.arraycopy(intToBigEndian(packet.length), 0, packet, 0, 4);//Packet length
            System.arraycopy(shortToBigEndian(seq), 0, packet, 0x06, 2);//Sequence=0
            System.arraycopy(intToBigEndian(sentid), 0, packet, 0x08, 4);//Client ID
            System.arraycopy(intToBigEndian(rcvdid), 0, packet, 0x0c, 4);//Radio ID
            if (audio.length == 0xa0) {
                System.arraycopy(shortToByte((short) 0x8197), 0, packet, 0x10, 2);
            } else {//This is the commonly used value
                System.arraycopy(shortToByte((short) 0x8000), 0, packet, 0x10, 2);//Usually this value
            }
            System.arraycopy(shortToByte(sendSeq), 0, packet, 0x12, 2);//Packet sequence number

            System.arraycopy(shortToByte((short) audio.length), 0, packet, 0x16, 2);
            System.arraycopy(audio, 0, packet, 0x18, audio.length);
            return packet;
        }
    }

    public static class CivPacket {
        /**
         * CI-V command packet
         * quint32 len;        // 0x00
         * quint16 type;       // 0x04
         * quint16 seq;        // 0x06
         * quint32 sentid;     // 0x08
         * quint32 rcvdid;     // 0x0c
         * char reply;       // 0x10, CI-V is 0xc1
         * quint16 civ_len;        // 0x11 This field is little-endian; 0x0001 is stored as 0x0100 in the array
         * quint16 sendseq;    //0x13
         * byte[] civ_data;//0x15
         */
        public static boolean checkIsCiv(byte[] data) {
            if (data.length <= 0x15) return false;
            //CI-V command conditions: length must be >= 0x15, dataLen matches actual, reply=0xc1, type!=0x01
            return (data.length - 0x15 == readShortBigEndianData(data, 0x11))
                    && (data[0x10] == (byte) 0xc1)
                    && (ControlPacket.getType(data) != CMD_RETRANSMIT);
        }

        public static byte[] getCivData(byte[] data) {
            byte[] civ = new byte[data.length - 0x15];
            System.arraycopy(data, 0x15, civ, 0, data.length - 0x15);
            return civ;
        }

        public static byte[] setCivData(short seq, int sentid, int rcvdid, short civSeq, byte[] data) {
            byte[] packet = new byte[data.length + 0x15];
            System.arraycopy(intToBigEndian(packet.length), 0, packet, 0, 4);
            System.arraycopy(shortToBigEndian(seq), 0, packet, 0x06, 2);
            System.arraycopy(intToBigEndian(sentid), 0, packet, 0x08, 4);
            System.arraycopy(intToBigEndian(rcvdid), 0, packet, 0x0c, 4);
            packet[0x10] = (byte) 0xc1;
            System.arraycopy(shortToBigEndian((short) data.length), 0, packet, 0x11, 2);
            System.arraycopy(shortToByte(civSeq), 0, packet, 0x13, 2);
            System.arraycopy(data, 0, packet, 0x15, data.length);
            return packet;
        }
    }

    public static class OpenClosePacket {
        /**
         * quint32 len;        // 0x00
         * quint16 type;       // 0x04
         * quint16 seq;        // 0x06
         * quint32 sentid;     // 0x08
         * quint32 rcvdid;     // 0x0c
         * char reply;       // 0x10, openClose is 0xc0, CI-V is 0xc1
         * quint16 civ_len;        // 0x11 This field is little-endian; 0x0001 is stored as 0x0100 in the array
         * quint16 sendseq;    //0x13
         * char magic;         // 0x15
         */
        public static byte[] toBytes(short seq, int sentId, int rcvdId, short civSeq, byte magic) {
            byte[] packet = new byte[OPENCLOSE_SIZE];
            System.arraycopy(intToBigEndian(OPENCLOSE_SIZE), 0, packet, 0, 4);
            //System.arraycopy(shortToBigEndian(type), 0, packet, 4, 2);
            System.arraycopy(shortToBigEndian(seq), 0, packet, 6, 2);
            System.arraycopy(intToBigEndian(sentId), 0, packet, 8, 4);
            System.arraycopy(intToBigEndian(rcvdId), 0, packet, 12, 4);

            packet[0x10] = (byte) 0xc0;
            System.arraycopy(shortToBigEndian((short) 0x0001), 0, packet, 0x11, 2);
            System.arraycopy(shortToByte(civSeq), 0, packet, 0x13, 2);
            packet[0x15] = magic;
            return packet;
        }
    }

    /**
     * Ping packet, 0x15.
     * If reply==0, the remote is pinging us and a reply is required. If reply==1, it is a ping reply and local pingSeq++
     */
    public static class PingPacket {
        /**
         * quint32 len;        // 0x00
         * quint16 type;       // 0x04
         * quint16 seq;        // 0x06
         * quint32 sentid;     // 0x08
         * quint32 rcvdid;     // 0x0c
         * char  reply;        // 0x10 If reply=0x00 in received data, reply with sendReplayPingData().
         * union { // This part has different definitions for sending vs receiving
         *      struct { // Ping packet
         *              quint32 time;      // 0x11
         *              };
         *      struct { // Send
         *               quint16 datalen;    // 0x11
         *               quint16 sendseq;    //0x13
         *              };
         *      }
         */
        public static boolean isPingPacket(byte[] data) {
            return readShortBigEndianData(data, 0x04) == CMD_PING;
        }

        public static byte getReply(byte[] data) {
            return data[0x10];
        }


        /**
         * Generate a ping reply packet based on the received ping packet.
         *
         * @param data     received ping packet
         * @param localID  host ID
         * @param remoteID radio ID
         * @return ping reply packet
         */
        public static byte[] sendReplayPingData(byte[] data, int localID, int remoteID) {
            byte[] packet = new byte[PING_SIZE];
            System.arraycopy(intToBigEndian(PING_SIZE), 0, packet, 0, 4);      //len  int32 0x00
            System.arraycopy(shortToBigEndian(CMD_PING), 0, packet, 4, 2);//type int16 0x04
            packet[0x6] = data[0x6];                                                    //seq  int16 0x06
            packet[0x7] = data[0x7];//Copy the original seq value
            System.arraycopy(intToBigEndian(localID), 0, packet, 8, 4);  //localID  int32  0x08
            System.arraycopy(intToBigEndian(remoteID), 0, packet, 12, 4);//remoteID int32  0x0c
            packet[0x10] = (byte) (0x01); //reply byte 0x10, reply=01 is ping reply, reply=00 is ping
            //Time
            packet[0x11] = data[0x11];
            packet[0x12] = data[0x12];
            packet[0x13] = data[0x13];
            packet[0x14] = data[0x14];
            return packet;
        }

        /**
         * Generate a ping packet for the radio. seq should be pingSeq, which is separate from
         * command packet sequence. The sequence number increments only after receiving a ping reply.
         *
         * @param localID  host ID
         * @param remoteID radio ID
         * @param seq      sequence number
         * @return data packet
         */
        public static byte[] sendPingData(int localID, int remoteID, short seq) {
            byte[] packet = new byte[PING_SIZE];
            System.arraycopy(intToBigEndian(PING_SIZE), 0, packet, 0, 4);//    len int32 0x00
            System.arraycopy(shortToBigEndian(CMD_PING), 0, packet, 4, 2);//type int16 0x04
            System.arraycopy(shortToBigEndian(seq), 0, packet, 6, 2);//seq  int16 0x06
            System.arraycopy(intToBigEndian(localID), 0, packet, 8, 4);  //localID  int32  0x08
            System.arraycopy(intToBigEndian(remoteID), 0, packet, 12, 4);//remoteID int32  0x0c
            packet[0x10] = (byte) (0x00);//Ping the remote = 0x00; reply = 0x01
            //Time
            System.arraycopy(intToBigEndian((int) System.currentTimeMillis())
                    , 0, packet, 11, 4);

            return packet;
        }
    }

    /**
     * Status (0x50) packet.
     */
    public static class StatusPacket {
        /**
        quint32 len;                // 0x00
        quint16 type;               // 0x04
        quint16 seq;                // 0x06
        quint32 sentid;             // 0x08
        quint32 rcvdid;             // 0x0c
        char unuseda[2];          // 0x10
        quint16 payloadsize;      // 0x12
        quint8 requestreply;      // 0x13
        quint8 requesttype;       // 0x14
        quint16 innerseq;         // 0x16
        char unusedb[2];          // 0x18
        quint16 tokrequest;         // 0x1a
        quint32 token;              // 0x1c
        union {
            struct {
                quint16 authstartid;    // 0x20
                char unusedd[5];        // 0x22
                quint16 commoncap;      // 0x27
                char unusede;           // 0x29
                quint8 macaddress[6];     // 0x2a
            };
            quint8 guid[GUIDLEN];                  // 0x20
        };
        quint32 error;             // 0x30
        char unusedg[12];         // 0x34
        char disc;                // 0x40
        char unusedh;             // 0x41
        quint16 civport;          // 0x42 // Sent bigendian
        quint16 unusedi;          // 0x44 // Sent bigendian
        quint16 audioport;        // 0x46 // Sent bigendian
        char unusedj[7];          // 0x49
         */
        public static boolean isStatusPacket(byte[] data) {
            return (data.length == STATUS_SIZE && readIntBigEndianData(data, 0) == STATUS_SIZE);
        }

        public static boolean getAuthOK(byte[] data) {
            return readIntBigEndianData(data, 0x30) == 0;//0x30 error=0,failed error=0xffffffff
        }

        public static boolean getIsConnected(byte[] data) {
            return data[0x40] == 0x00;
        }

        public static int getRigCivPort(byte[] data) {
            return readShortData(data, 0x42) & 0xffff;
        }

        public static int getRigAudioPort(byte[] data) {
            return readShortData(data, 0x46) & 0xffff;
        }

    }


    /**
     * 0x90 packet. Radio reply or APP reply with connection parameters.
     */
    public static class ConnInfoPacket {
        /**
        quint32 len;              // 0x00
        quint16 type;             // 0x04
        quint16 seq;              // 0x06
        quint32 sentid;           // 0x08
        quint32 rcvdid;           // 0x0c
        char unuseda[2];          // 0x10
        quint16 payloadsize;      // 0x12
        quint8 requestreply;      // 0x13
        quint8 requesttype;       // 0x14
        quint16 innerseq;         // 0x16
        char unusedb[2];          // 0x18
        quint16 tokrequest;       // 0x1a Host TOKEN
        quint32 token;            // 0x1c Radio TOKEN
        union {
            struct {
                quint16 authstartid;    // 0x20
                char unusedg[5];        // 0x22
                quint16 commoncap;      // 0x27 When commonCap==0x1080, use MAC address to identify radio; otherwise 16-byte GUID
                char unusedh;           // 0x29
                quint8 macaddress[6];     // 0x2a
            };
            quint8 guid[GUIDLEN];                  // 0x20
        };
        char unusedab[16];        // 0x30
        char name[32];                  // 0x40
        union { // This part has two types: send and receive
            struct { // Received data structure
                quint32 busy;            // 0x60
                char computer[16];        // 0x64
                char unusedi[16];         // 0x74
                quint32 ipaddress;        // 0x84
                char unusedj[8];          // 0x78
            };
            struct { // Send data structure, used to inform radio of the following parameters
                char username[16];    // 0x60 Encrypted username
                char rxenable;        // 0x70 Receive enabled=0x01
                char txenable;        // 0x71 Transmit enabled=0x01
                char rxcodec;         // 0x72 Receive codec, IcomCodecType, 0x04=LPCM 16Bit 1ch
                char txcodec;         // 0x73 Transmit codec, IcomCodecType, 0x04=LPCM 16Bit 1ch
                quint32 rxsample;     // 0x74 Receive sample rate
                quint32 txsample;     // 0x78 Transmit sample rate
                quint32 civport;      // 0x7c Host CI-V port
                quint32 audioport;    // 0x80 Host audio port
                quint32 txbuffer;     // 0x84 TX buffer; unknown unit, wfView uses 0x96
                quint8 convert;      // 0x88
                char unusedl[7];      // 0x89

         */

        public static boolean getBusy(byte[] data) {
            return data[0x60] != 0x00;
        }

        public static byte[] getMacAddress(byte[] data) {
            byte[] mac = new byte[6];
            System.arraycopy(data, 0x2a, mac, 0, 6);//macAddress
            return mac;
        }

        public static String getRigName(byte[] data) {
            byte[] name = new byte[32];
            System.arraycopy(data, 0x40, name, 0, 32);//rig Name
            return new String(name).trim();
        }

        public static byte[] connectRequestPacket(short seq, int localSID, int remoteSID
                , byte requestReply, byte requestType
                , short authInnerSendSeq, short tokRequest, int token
                , byte[] macAddress
                , String rigName, String userName, int sampleRate
                , int civPort, int audioPort, int txBufferSize) {
            byte[] packet = new byte[CONNINFO_SIZE];
            System.arraycopy(intToBigEndian(CONNINFO_SIZE), 0, packet, 0, 4);//len
            System.arraycopy(shortToBigEndian((short) 0), 0, packet, 4, 2);  //type=0
            System.arraycopy(shortToBigEndian(seq), 0, packet, 6, 2);        //seq
            System.arraycopy(intToBigEndian(localSID), 0, packet, 8, 4);     //localID
            System.arraycopy(intToBigEndian(remoteSID), 0, packet, 12, 4);   //remoteID
            //System.arraycopy({0x00,0x00}, 0, packet, 16, 2);                  //unuseda byte[2]
            System.arraycopy(shortToByte((short) (CONNINFO_SIZE - 0x10))
                    , 0, packet, 18, 2);//payloadsize
            packet[20] = requestReply;//0x01;//requestReply;
            packet[21] = requestType;//requestType; should be 0x03, replying with our required parameters.
            System.arraycopy(shortToByte(authInnerSendSeq), 0, packet, 22, 2);//innerSeq
            //System.arraycopy(unusedB, 0, packet, 24, 2);//unusedb
            System.arraycopy(shortToByte(tokRequest), 0, packet, 26, 2);//tokRequest host TOKEN
            System.arraycopy(intToByte(token), 0, packet, 28, 4);//Radio TOKEN
            packet[0x26] = 0x10;
            packet[0x27] = (byte) 0x80;//These two bytes are the commCap field, default 0x1080
            System.arraycopy(macAddress, 0, packet, 0x28, 6);//macAddress

            System.arraycopy(stringToByte(rigName, 32), 0, packet, 64, 32);//Radio name
            System.arraycopy(passCode(userName), 0, packet, 96, 16);//Encrypted username
            packet[0x70] = 0x01;//rxEnable, receive supported
            packet[0x71] = 0x01;//txEnable, transmit supported
            packet[0x72] = IcomCodecType.LPCM_1CH_16BIT;//rxCodec,0x04:LPcm 16Bit 1ch,0x02:LPcm 8Bit 1ch
            packet[0x73] = IcomCodecType.LPCM_1CH_16BIT;//txCodec,0x04:LPcm 16Bit 1ch,0x02:LPcm 8Bit 1ch
            System.arraycopy(intToByte(sampleRate), 0, packet, 0x74, 4);//rxSampleRate, sample rate
            System.arraycopy(intToByte(sampleRate), 0, packet, 0x78, 4);//txSampleRate, sample rate
            System.arraycopy(intToByte(civPort), 0, packet, 0x7c, 4);//civPort, local CI-V port
            System.arraycopy(intToByte(audioPort), 0, packet, 0x80, 4);//audioPort, local audio port
            System.arraycopy(intToByte(txBufferSize), 0, packet, 0x84, 4);//txBuffer, TX buffer
            packet[0x88] = 0x01;//convert

            return packet;
        }

        public static byte[] connInfoPacketData(byte[] rigData,
                                                short seq, int localSID, int remoteSID
                , byte requestReply, byte requestType
                , short authInnerSendSeq, short tokRequest, int token
                , String rigName, String userName, int rx_sampleRate,int tx_sampleRate
                , int civPort, int audioPort, int txBufferSize) {
            byte[] packet = new byte[CONNINFO_SIZE];
            System.arraycopy(intToBigEndian(CONNINFO_SIZE), 0, packet, 0, 4);//len
            System.arraycopy(shortToBigEndian((short) 0), 0, packet, 4, 2);  //type=0
            System.arraycopy(shortToBigEndian(seq), 0, packet, 6, 2);        //seq
            System.arraycopy(intToBigEndian(localSID), 0, packet, 8, 4);     //localID
            System.arraycopy(intToBigEndian(remoteSID), 0, packet, 12, 4);   //remoteID
            //System.arraycopy({0x00,0x00}, 0, packet, 16, 2);                  //unuseda byte[2]
            System.arraycopy(shortToByte((short) (CONNINFO_SIZE - 0x10))
                    , 0, packet, 18, 2);//payloadsize
            packet[20] = requestReply;//0x01;//requestReply;
            packet[21] = requestType;//requestType; should be 0x03, replying with our required parameters.
            System.arraycopy(shortToByte(authInnerSendSeq), 0, packet, 22, 2);//innerSeq
            //System.arraycopy(unusedB, 0, packet, 24, 2);//unusedb
            System.arraycopy(shortToByte(tokRequest), 0, packet, 26, 2);//tokRequest host TOKEN
            System.arraycopy(intToByte(token), 0, packet, 28, 4);//Radio TOKEN
            //Copy the radio's data back:
            //00 00 00 00 00 00 00 10 80 00 00 90 c7 0f b6 ed
            //autID|unusedG       |comcp|uH|macAddress       |
            //00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            //|unusedB total 16 bytes                          |
            System.arraycopy(rigData, 32, packet, 32, 32);

            System.arraycopy(stringToByte(rigName, 32), 0, packet, 64, 32);//Radio name
            System.arraycopy(passCode(userName), 0, packet, 96, 16);//Encrypted username
            packet[0x70] = 0x01;//rxEnable, receive supported
            packet[0x71] = 0x01;//txEnable, transmit supported
            packet[0x72] = IcomCodecType.LPCM_1CH_16BIT;//rxCodec,0x04:LPcm 16Bit 1ch,0x02:LPcm 8Bit 1ch
            packet[0x73] = IcomCodecType.LPCM_1CH_16BIT;//txCodec,0x04:LPcm 16Bit 1ch,0x02:LPcm 8Bit 1ch
            System.arraycopy(intToByte(rx_sampleRate), 0, packet, 0x74, 4);//rxSampleRate, sample rate
            System.arraycopy(intToByte(tx_sampleRate), 0, packet, 0x78, 4);//txSampleRate, sample rate
            System.arraycopy(intToByte(civPort), 0, packet, 0x7c, 4);//civPort, local CI-V port
            System.arraycopy(intToByte(audioPort), 0, packet, 0x80, 4);//audioPort, local audio port
            System.arraycopy(intToByte(txBufferSize), 0, packet, 0x84, 4);//txBuffer, TX buffer
            packet[0x88] = 0x01;//convert

            return packet;
        }
    }


    /**
     * 0xA8 packet, radio replies with its parameter info; 0x42+0x66, 0x42 is capabilities header, 0x66 is radioCap
     */
    public static class CapCapabilitiesPacket {
        /**
         * Get the number of radioCap entries
         *
         * @param data data packet
         * @return count
         */
        public static int getRadioPacketCount(byte[] data) {
            int count;
            count = (data.length - CAPABILITIES_SIZE) / RADIO_CAP_SIZE;
            if (count > 0) {
                return count;
            } else {
                return count;
            }
        }

        /**
         * Get radioCap packet. There may be more than one, so use index to specify which one.
         *
         * @param data  data packet
         * @param index packet index, starting from 0
         * @return radioCap data packet
         */
        public static byte[] getRadioCapPacket(byte[] data, int index) {
            if (data.length < (CAPABILITIES_SIZE + RADIO_CAP_SIZE * (index + 1)))
                return null;//If less than minimum length, return null
            byte[] packet = new byte[0x66];
            System.arraycopy(data, CAPABILITIES_SIZE + RADIO_CAP_SIZE * index, packet, 0, RADIO_CAP_SIZE);
            return packet;
        }
    }

    /**
     * Radio parameter packet (0x66 length), follows the Capabilities (0x42 length) packet; if one, total length is 0xA8.
     */
    public static class RadioCapPacket {
        /**
         union {
            struct {
                quint8 unusede[7];          // 0x00
                quint16 commoncap;          // 0x07
                quint8 unused;              // 0x09
                quint8 macaddress[6];       // 0x0a
            };
            quint8 guid[GUIDLEN];           // 0x0
        };
        char name[32];            // 0x10
        char audio[32];           // 0x30
        quint16 conntype;         // 0x50
        char civ;                 // 0x52
        quint16 rxsample;         // 0x53
        quint16 txsample;         // 0x55
        quint8 enablea;           // 0x57
        quint8 enableb;           // 0x58
        quint8 enablec;           // 0x59
        quint32 baudrate;         // 0x5a
        quint16 capf;             // 0x5e
        char unusedi;             // 0x60
        quint16 capg;             // 0x61
        char unusedj[3];          // 0x63
         */

        /**
         * Get radio name
         *
         * @param data 0x66 data packet
         * @return name
         */
        public static String getRigName(byte[] data) {
            byte[] rigName = new byte[32];
            System.arraycopy(data, 0x10, rigName, 0, 32);
            return new String(rigName).trim();
        }

        public static String getAudioName(byte[] data) {
            byte[] audioName = new byte[32];
            System.arraycopy(data, 0x30, audioName, 0, 32);
            return new String(audioName).trim();
        }

        public static byte getCivAddress(byte[] data) {
            return data[0x52];
        }

        public static short getRxSupportSample(byte[] data) {
            return readShortData(data, 0x53);
        }

        public static short getTxSupportSample(byte[] data) {
            return readShortData(data, 0x55);
        }

        public static boolean getSupportTX(byte[] data) {
            return data[0x57] == 0x01;
        }

    }

    /**
     * TOKEN (0x40) packet.
     * When sending: requestType=0x02 is token confirm, 0x01 is token delete.
     * When receiving: requestType=0x05 with requestReply=0x02 and type=0x01 means token renewal from radio.
     * response=0x00000000 means renewal success, 0xFFFFFFFF means renewal rejected; save RemoteId,
     * Token, tokRequest values, close all ports, and restart login.
     */
    public static class TokenPacket {
        /**
        public int len = TOKEN_SIZE;    // 0x00 (int32)
        public short type;              // 0x04(int16)
        public short seq;               // 0x06(int16)
        public int sentId;              // 0x08(int32) MyID, related to local IP address and port
        public int rcvdId;              // 0x0c(int32)
        public byte[] unusedA = new byte[2];       // 0x10 char[2]//Possibly used for command sequence number
        public short payloadSize = TOKEN_SIZE - 0x10;// 0x12(int16) Payload length = packet length - 16 byte header
        public byte requestReply;        // 0x14(int8)
        public byte requestType;         // 0x15(int8)
        public short innerSeq;           // 0x16(int16)
        public byte[] unusedB = new byte[2];// 0x18(char[2]
        public short tokRequest;         // 0x1a(int16)
        public int token;                // 0x1c(int32)
        public short tokRequest;        // 0x20
        public byte[] unusedG = new byte[5];  // 0x22
        public short commonCap;//0x27
        public byte unusedH;//0x29
        public byte[] macAddress=new byte[6];//0x2a
        public byte[] guid=new byte[16];//0x20
        public int response;//0x30
        public byte[] unusedE=new byte[12];//0x34
         */

        /**
         * Generate Token packet
         *
         * @param seq              sequence number
         * @param localSID         host ID
         * @param remoteSID        radio ID
         * @param requestType      0x02=token confirm, 0x01=token delete. If received 0x05 with requestReply=0x02 and type=0x01
         *                         means radio token renewal succeeded
         * @param authInnerSendSeq inner sequence number
         * @param tokRequest       host TOKEN
         * @param token            radio TOKEN
         * @return byte array
         */
        public static byte[] getTokenPacketData(
                short seq, int localSID, int remoteSID, byte requestType
                , short authInnerSendSeq, short tokRequest, int token) {
            byte[] packet = new byte[TOKEN_SIZE];
            System.arraycopy(intToBigEndian(TOKEN_SIZE), 0, packet, 0, 4); //len  int32 0x00
            //System.arraycopy(shortToBigEndian((short) 0), 0, packet, 4, 2);//type int16 0x04
            System.arraycopy(shortToBigEndian(seq), 0, packet, 6, 2);      //seq  int16 0x06
            System.arraycopy(intToBigEndian(localSID), 0, packet, 8, 4);   //localId  int32 0x08
            System.arraycopy(intToBigEndian(remoteSID), 0, packet, 12, 4); //remoteId int32 0x0c
            //System.arraycopy({0x00,0x00}, 0, packet, 16, 2);               //unusedA byte[2] 0x10
            System.arraycopy(shortToByte((short) (TOKEN_SIZE - 0x10))
                    , 0, packet, 18, 2);                         //payloadSize int16  0x12
            packet[20] = 0x01;        //requestReply byte 0x14
            packet[21] = requestType; //requestType  byte 0x15
            System.arraycopy(shortToByte(authInnerSendSeq), 0, packet, 22, 2);//innerSeq int16 0x16
            //System.arraycopy(unusedB, 0, packet, 24, 2);                            //unusedB byte[2] 0x18
            System.arraycopy(shortToByte(tokRequest), 0, packet, 26, 2);//tokRequest int16 0x1a
            System.arraycopy(intToByte(token), 0, packet, 28, 4);      //token int32 0x1c
            //Remaining bytes are zero-filled
            return packet;
        }

        public static byte[] getRenewalPacketData(
                short seq, int localSID, int remoteSID
                , short authInnerSendSeq, short tokRequest, int token
        ) {
            return getTokenPacketData(seq, localSID, remoteSID, TOKEN_TYPE_RENEWAL, authInnerSendSeq, tokRequest, token);
        }

        /**
         * Check if token renewal succeeded. Conditions: requestType=0x05, requestReply=0x02, type=0x01, response=0x00
         *
         * @param data data packet
         * @return whether renewal succeeded
         */
        public static boolean TokenRenewalOK(byte[] data) {
            byte requestType = data[0x15];
            byte requestReply = data[0x14];
            short type = readShortBigEndianData(data, 0x04);
            int response = readIntData(data, 0x30);
            return requestType == 0x05 && requestReply == 0x02 && type == 0x01 && response == 0x00;
        }

        public static byte getRequestType(byte[] data) {
            return data[21];//0x15
        }

        public static byte getRequestReply(byte[] data) {
            return data[20];//0x14
        }

        public static int getResponse(byte[] data) {
            return readIntData(data, 0x30);
        }

        public static short getTokRequest(byte[] data) {
            return readShortData(data, 26);//0x1a
        }

        public static int getToken(byte[] data) {
            return readIntData(data, 28);//0x1c
        }
    }


    /**
     * Login response packet, 0x60 packet, length 96.
     */
    public static class LoginResponsePacket {
        /**
        public int len;// 0x00 (int32)
        public short type;              // 0x04(int16)
        public short seq;               // 0x06(int16)
        public int sentId;              // 0x08(int32) MyID, related to local IP address and port
        public int rcvdId;              // 0x0c(int32)
        public byte[] unusedA = new byte[2];       // 0x10 char[2]//Possibly used for command sequence number
        public short payloadSize;// 0x12(int16) Payload length = packet length - 16 byte header
        public byte requestReply;        // 0x14(int8)
        public byte requestType;         // 0x15(int8)
        public short innerSeq;           // 0x16(int16)
        public byte[] unusedB = new byte[2];// 0x18(char[2]
        public short tokRequest;         // 0x1a(int16)
        public int token;                // 0x1c(int32)
        public short authStartId;        // 0x20
        public byte[] unusedD = new byte[14];  // 0x22
        public int error;              // 0x30
        public byte[] unusedE = new byte[12];     // 0x34
        public byte[] connection = new byte[16];  // 0x40
        public byte[] unusedF = new byte[16];     // 0x50
         */

        public static boolean authIsOK(byte[] data) {
            return readIntData(data, 0x30) == 0x00;
        }

        public static int errorNum(byte[] data) {
            return readIntData(data, 0x30);
        }

        public static int getToken(byte[] data) {
            return readIntData(data, 0x1c);
        }

        public static String getConnection(byte[] data) {
            byte[] connection = new byte[16];
            System.arraycopy(data, 0x40, connection, 0, 16);
            return new String(connection).trim();
        }

    }


    /**
     * Login packet, 0x80 packet, length 128
     */
    public static class LoginPacket {
        /**
        public int len = LOGIN_SIZE;    // 0x00 (int32)
        public short type;              // 0x04(int16)
        public short seq;               // 0x06(int16)
        public int sentId;              // 0x08(int32) MyID, related to local IP address and port
        public int rcvdId;              // 0x0c(int32)
        public byte[] unusedA = new byte[2];       // 0x10 char[2]//Possibly used for command sequence number
        public short payloadSize = LOGIN_SIZE - 0x10;// 0x12(int16) Payload length = packet length - 16 byte header
        public byte requestReply;        // 0x14(int8)
        public byte requestType;         // 0x15(int8)
        public short innerSeq;           // 0x16(int16)
        public byte[] unusedB = new byte[2];// 0x18(char[2]
        public short tokRequest;         // 0x1a(int16)
        public int token;                // 0x1c(int32)
        public byte[] unusedC = new byte[32];    // 0x20(char[32])
        private byte[] userName = new byte[16];  // 0x40(char[16])
        private byte[] password = new byte[16];  // 0x50(char[16])
        private final byte[] name = new byte[16];// 0x60(char[16])
        public byte[] unusedF = new byte[16];    // 0x70(char[16])
         */


        /**
         * Generate Login (0x80) packet
         *
         * @param seq              sequence number
         * @param localSID         host ID
         * @param remoteSID        radio ID
         * @param authInnerSendSeq inner sequence number
         * @param tokRequest       my TOKEN
         * @param userName         username (plaintext)
         * @param password         password (plaintext)
         * @param name             terminal name
         * @return data packet
         */
        public static byte[] loginPacketData(short seq, int localSID, int remoteSID, short authInnerSendSeq
                , short tokRequest, int token, String userName, String password, String name) {
            byte[] packet = new byte[LOGIN_SIZE];
            System.arraycopy(intToBigEndian(LOGIN_SIZE), 0, packet, 0, 4); //len int32     0x00
            System.arraycopy(shortToBigEndian((short) 0), 0, packet, 4, 2);//type int16    0x04
            System.arraycopy(shortToBigEndian(seq), 0, packet, 6, 2);      //seq int 16    0x06
            System.arraycopy(intToBigEndian(localSID), 0, packet, 8, 4);   //localId int32 0x08
            System.arraycopy(intToBigEndian(remoteSID), 0, packet, 12, 4); //remoteId int32  0x0c
            //System.arraycopy({0x00,0x00}, 0, packet, 16, 2);                      //unusedA byte[2] 0x10
            System.arraycopy(shortToByte((short) (LOGIN_SIZE - 0x10))
                    , 0, packet, 18, 2);//payloadSize, int16, big-endian, packet length - 16 byte header  0x12
            packet[20] = 0x01;//requestReply byte 0x14
            packet[21] = 0x00;//requestType  byte 0x15
            System.arraycopy(shortToByte(authInnerSendSeq), 0, packet, 22, 2);//innerSeq int16 x016
            //System.arraycopy(unusedB, 0, packet, 24, 2);  //unusedB byte[2] 0x18
            System.arraycopy(shortToByte(tokRequest), 0, packet, 26, 2);//tokRequest int16 0x1a, my token
            System.arraycopy(intToByte(token), 0, packet, 28, 4);//token int32 0x1c, radio token
            //System.arraycopy(unusedC, 0, packet, 32, 32);               //unusedC byte[32] 0x0x20
            System.arraycopy(passCode(userName), 0, packet, 64, 16);//userName byte[16] 0x40, encrypted username
            System.arraycopy(passCode(password), 0, packet, 80, 16);//password byte[16] 0x50, encrypted password
            System.arraycopy(stringToByte(name, 16), 0, packet, 96, 16);//name byte[16] 0x60, host name
            //System.arraycopy(unusedF, 0, packet, 112, 16);                         //unusedF byte[16] 0x70
            return packet;
        }

    }


    /**
     * Username/password encryption algorithm, supports up to 16 characters.
     *
     * @param pass username or password
     * @return encrypted text, max 16 bytes
     */
    public static byte[] passCode(String pass) {
        byte[] sequence =//Dictionary
                {
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        0x47, 0x5d, 0x4c, 0x42, 0x66, 0x20, 0x23, 0x46, 0x4e, 0x57, 0x45, 0x3d, 0x67, 0x76, 0x60, 0x41, 0x62, 0x39, 0x59, 0x2d, 0x68, 0x7e,
                        0x7c, 0x65, 0x7d, 0x49, 0x29, 0x72, 0x73, 0x78, 0x21, 0x6e, 0x5a, 0x5e, 0x4a, 0x3e, 0x71, 0x2c, 0x2a, 0x54, 0x3c, 0x3a, 0x63, 0x4f,
                        0x43, 0x75, 0x27, 0x79, 0x5b, 0x35, 0x70, 0x48, 0x6b, 0x56, 0x6f, 0x34, 0x32, 0x6c, 0x30, 0x61, 0x6d, 0x7b, 0x2f, 0x4b, 0x64, 0x38,
                        0x2b, 0x2e, 0x50, 0x40, 0x3f, 0x55, 0x33, 0x37, 0x25, 0x77, 0x24, 0x26, 0x74, 0x6a, 0x28, 0x53, 0x4d, 0x69, 0x22, 0x5c, 0x44, 0x31,
                        0x36, 0x58, 0x3b, 0x7a, 0x51, 0x5f, 0x52, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0

                };

        byte[] passBytes = pass.getBytes();
        byte[] enCodePass = new byte[16];//Encrypted text cannot exceed 16 bytes

        for (int i = 0; i < passBytes.length && i < 16; i++) {
            int p = (passBytes[i] + i) & 0xff;//Prevent negative values
            if (p > 126) {
                p = 32 + p % 127;
            }
            enCodePass[i] = sequence[p];
        }
        return enCodePass;
    }

    /**
     * Convert string to byte array of specified length
     *
     * @param s   string
     * @param len length
     * @return byte array
     */
    public static byte[] stringToByte(String s, int len) {
        byte[] buf = new byte[len];
        byte[] temp = s.getBytes();
        for (int i = 0; i < temp.length; i++) {
            if (i >= len) break;
            buf[i] = temp[i];
        }
        return buf;
    }

    /**
     * Convert int to little-endian (high byte at end)
     *
     * @param n int
     * @return byte array
     */
    public static byte[] intToBigEndian(int n) {
        byte[] b = new byte[4];
        b[0] = (byte) (n & 0xff);
        b[1] = (byte) (n >> 8 & 0xff);
        b[2] = (byte) (n >> 16 & 0xff);
        b[3] = (byte) (n >> 24 & 0xff);
        return b;
    }

    /**
     * Convert int32 to 4-byte array
     *
     * @param n int32
     * @return byte array
     */
    public static byte[] intToByte(int n) {
        byte[] b = new byte[4];
        b[3] = (byte) (n & 0xff);
        b[2] = (byte) (n >> 8 & 0xff);
        b[1] = (byte) (n >> 16 & 0xff);
        b[0] = (byte) (n >> 24 & 0xff);
        return b;
    }

    /**
     * Read little-endian Int32 from stream data
     *
     * @param data  stream data
     * @param start start position
     * @return Int32
     */
    public static int readIntBigEndianData(byte[] data, int start) {
        if (data.length - start < 4) return 0;
        return (int) data[start] & 0xff
                | ((int) data[start + 1] & 0xff) << 8
                | ((int) data[start + 2] & 0xff) << 16
                | ((int) data[start + 3] & 0xff) << 24;
    }

    public static int readIntData(byte[] data, int start) {
        if (data.length - start < 4) return 0;
        return (int) data[start + 3] & 0xff
                | ((int) data[start + 2] & 0xff) << 8
                | ((int) data[start + 1] & 0xff) << 16
                | ((int) data[start] & 0xff) << 24;
    }

    /**
     * Read little-endian Short from stream data
     *
     * @param data  stream data
     * @param start start position
     * @return Int16
     */
    public static short readShortBigEndianData(byte[] data, int start) {
        if (data.length - start < 2) return 0;
        return (short) ((short) data[start] & 0xff
                | ((short) data[start + 1] & 0xff) << 8);
    }

    /**
     * Convert bytes to short without endian conversion!
     *
     * @param data byte data
     * @return short
     */
    public static short readShortData(byte[] data, int start) {
        if (data.length - start < 2) return 0;
        return (short) ((short) data[start + 1] & 0xff
                | ((short) data[start] & 0xff) << 8);
    }

    /**
     * Convert short to little-endian (high byte at end)
     *
     * @param n short
     * @return byte array
     */
    public static byte[] shortToBigEndian(short n) {
        byte[] b = new byte[2];
        b[0] = (byte) (n & 0xff);
        b[1] = (byte) (n >> 8 & 0xff);
        return b;
    }

    /**
     * Convert short to byte array
     *
     * @param n short
     * @return byte array
     */
    public static byte[] shortToByte(short n) {
        byte[] b = new byte[2];
        b[1] = (byte) (n & 0xff);
        b[0] = (byte) (n >> 8 & 0xff);
        return b;
    }

    /**
     * Display hexadecimal content
     *
     * @param data byte array
     * @return hex string
     */
    public static String byteToStr(byte[] data) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            s.append(String.format("%02x ", data[i] & 0xff));
        }
        return s.toString();
    }
}
