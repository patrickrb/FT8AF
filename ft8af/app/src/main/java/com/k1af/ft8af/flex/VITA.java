package com.k1af.ft8af.flex;
/**
 * Simple unpacking and packing operations for the VITA49 protocol. This library is no longer maintained; VITA49.java will replace it.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */
/*
public static intVH_PKT_TYPE(x)      ((x & 0xF0000000) >> 28)
public static intVH_C(x)             ((x & 0x08000000) >> 26)
public static intVH_T(x)             ((x & 0x04000000) >> 25)
public static intVH_TSI(x)           ((x & 0x00c00000) >> 21)
public static intVH_TSF(x)           ((x & 0x00300000) >> 19)
public static intVH_PKT_CNT(x)       ((x & 0x000f0000) >> 16)
public static intVH_PKT_SIZE(x)      (x & 0x0000ffff)
 */

// Enumerates for field values

import android.annotation.SuppressLint;

import java.nio.ByteBuffer;



public class VITA {
    private static final String TAG = "VITA";

    // Minimum valid VITA packet length
    private static final int VITAmin = 28;

    public static final int FRS_OUI = 0x12cd;
    //public static final int VITA_PORT = 4991;

    public static final int FLEX_CLASS_ID = 0x534C;
    public static final int FLEX_DAX_AUDIO_CLASS_ID = 0x534C03E3;
    public static final int FLEX_DAX_IQ_CLASS_ID = 0x534C00E3;
    public static final int FLEX_FFT_CLASS_ID = 0x534C8003;
    public static final int FLEX_METER_CLASS_ID = 0x534C8002;
    public static final int FLEX_Discovery_stream_ID = 0x800;


    public static final int VS_Meter = 0x8002;
    public static final int VS_PAN_FFT = 0x8003;
    public static final int VS_Waterfall = 0x8004;
    public static final int VS_Opus = 0x8005;
    public static final int DAX_IQ_24Khz = 0x00e3;
    public static final int DAX_IQ_48Khz = 0x00e4;
    public static final int DAX_IQ_96Khz = 0x00e5;
    public static final int DAX_IQ_192KHz = 0x00e6;
    public static final int VS_DAX_Audio = 0x03e3;
    public static final int VS_Discovery = 0xffff;

    //ID info related to x6100
    public  static final long XIEGU_Discovery_Class_Id = 0x005849454755FFFFL;
    public  static final int XIEGU_Discovery_Stream_Id = 0x00000800;
    public static final long XIEGU_AUDIO_CLASS_ID = 0x00584945475500A1L;
    public static final int XIEGU_PING_Stream_Id= 0x00000600;
    public static final long XIEGU_PING_CLASS_ID = 0x58494547550006L;
    public static final long XIEGU_METER_CLASS_ID = 0x58494547550007L;
    public static final int XIEGU_METER_Stream_Id= 0x00000700;



    private byte[] buffer;
    public boolean streamIdPresent;//Whether stream identifier is present
    public VitaPacketType packetType;
    public boolean classIdPresent;//Indicates whether the packet contains a class identifier (class ID) field
    public boolean trailerPresent;//Indicates whether the packet contains a trailer
    public VitaTSI tsi;//Timestamp type
    public VitaTSF tsf;//Fractional timestamp type
    public int packetCount;//Packet counter for consecutive IF data packets with the same Stream Identifier and packet type
    public int packetSize;//Number of 32-bit words in the IF Data packet
    public int streamId;//Stream ID, 32-bit
    //Timestamp has two parts: fractional and integer. Integer part has second resolution, 32-bit; fractional part is 64-bit.
    public int integerTimestamp;//u_int32, long is 64-bit
    public long fracTimeStamp;
    public long oui;
    public int informationClassCode;//Deprecated, replaced by classId
    public int packetClassCode;//Deprecated, replaced by classId
    public int classId;//For FLEX this should be 0x534CFFF, combining informationClassCode and packetClassCode
    public long classId64;

    public byte[] payload = null;
    public long trailer;
    public boolean isAvailable = false;//Whether the radio object is valid

    /**
     * Set the VITA packet header
     * @param packet data packet
     * @return data packet
     */
    public byte[] setVitaPacketHeader(byte[] packet){
        if (packet.length < 28) return packet;
        packet[0] = (byte) (packetType.ordinal() << 4);//packetType
        if (classIdPresent) packet[0] |=0x08;
        if (trailerPresent) packet[0] |= 0x04;


        packet[1] = (byte) (packetCount & 0xf);//packet count
        packet[1] |= (byte) (tsi.ordinal() << 6);//TSI
        packet[1] |= (byte) (tsf.ordinal() << 4);//TSF

        packet[2] = (byte) ((packetSize & 0xff00 ) >> 8 & 0xff);//packetSize 1 (high 8 bits)
        packet[3] = (byte) (packetSize & 0xff);//packetSize 2 (low 8 bits)

        //-----Stream Identifier--No.2 word----
        packet[4] = (byte) (((streamId & 0x00ff000000) >> 24) & 0xff);
        packet[5] = (byte) (((streamId & 0x0000ff0000) >> 16) & 0xff);
        packet[6] = (byte) (((streamId & 0x000000ff00) >> 8) & 0xff);
        packet[7] = (byte) (streamId &   0x00000000ff);
        //----Class ID--No.3 words----

        packet[8] = 0x00;
        packet[9] = (byte)(((classId64 & 0x00ff000000000000L) >> 48)& 0xff);
        packet[10] =(byte)(((classId64 & 0x0000ff0000000000L) >> 40)& 0xff);
        packet[11] =(byte)(((classId64 & 0x000000ff00000000L) >> 32)& 0xff);

        //---Class ID--No.4 word----
        packet[12] =(byte)(((classId64 & 0x00000000ff000000L) >> 24)& 0xff);
        packet[13] =(byte)(((classId64 & 0x0000000000ff0000L) >> 16)& 0xff);
        packet[14] =(byte)(((classId64 & 0x000000000000ff00L) >> 8)& 0xff);
        packet[15] =(byte)((classId64 & 0x00000000000000ffL ));

        //---Timestamp Int--No.5 word----
        packet[16] =(byte)(((integerTimestamp & 0xff000000) >> 24)& 0xff);
        packet[17] =(byte)(((integerTimestamp & 0x00ff0000) >> 16)& 0xff);
        packet[18] =(byte)(((integerTimestamp & 0x0000ff00) >> 8)& 0xff);
        packet[19] =(byte)(integerTimestamp & 0x000000ff);

        //---Timestamp franc Int--No.6 No.7 word----
        packet[20] = (byte)(((fracTimeStamp & 0xff00000000000000L) >> 56)& 0xff);
        packet[21] = (byte)(((fracTimeStamp & 0x00ff000000000000L) >> 48)& 0xff);
        packet[22] = (byte)(((fracTimeStamp & 0x0000ff0000000000L) >> 40)& 0xff);
        packet[23] = (byte)(((fracTimeStamp & 0x000000ff00000000L) >> 32)& 0xff);
        //---Class ID--No.4 word----

        packet[24] =(byte)(((fracTimeStamp & 0x00000000ff000000L) >> 24)& 0xff);
        packet[25] =(byte)(((fracTimeStamp & 0x0000000000ff0000L) >> 16)& 0xff);
        packet[26] =(byte)(((fracTimeStamp & 0x000000000000ff00L) >> 8)& 0xff);
        packet[27] =(byte)(fracTimeStamp & 0x00000000000000ffL ) ;
        return packet;
    }

    public byte[] pingDataToVita(){
        byte[] result=new byte[28];
        return setVitaPacketHeader(result);
    }

    /**
     * Generate a VITA packet for audio stream
     *
     * @param count packet counter
     * @param payload audio stream data
     * @return VITA data packet
     */
    public byte[] audioShortDataToVita(int count, short[] payload){
        packetType = VitaPacketType.EXT_DATA_WITH_STREAM;
        classIdPresent = true;
        trailerPresent = false;//No trailer
        tsi = VitaTSI.TSI_OTHER;//
        tsf = VitaTSF.TSF_SAMPLE_COUNT;
        this.packetCount = count;
        this.packetSize =  (payload.length / 2) + 7;

        byte[] result=new byte[payload.length*2 + 28];
        setVitaPacketHeader(result);

        //----HEADER--No.1 word------

//        result[0] = (byte) (packetType.ordinal() << 4);//packetType
//        if (classIdPresent) result[0] |=0x08;
//        if (trailerPresent) result[0] |= 0x04;
//
//
//        result[1] = (byte) (packetCount & 0xf);//packet count
//        result[1] |= (byte) (tsi.ordinal() << 6);//TSI
//        result[1] |= (byte) (tsf.ordinal() << 4);//TSF
//
//        result[2] = (byte) ((packetSize & 0xff00 ) >> 8 & 0xff);//packetSize 1 (high 8 bits)
//        result[3] = (byte) (packetSize & 0xff);//packetSize 2 (low 8 bits)
//
//        //-----Stream Identifier--No.2 word----
//        result[4] = (byte) (((streamId & 0x00ff000000) >> 24) & 0xff);
//        result[5] = (byte) (((streamId & 0x0000ff0000) >> 16) & 0xff);
//        result[6] = (byte) (((streamId & 0x000000ff00) >> 8) & 0xff);
//        result[7] = (byte) (streamId &   0x00000000ff);
//        //----Class ID--No.3 words----
//
//        result[8] = 0x00;
//        result[9] = (byte)(((classId64 & 0x00ff000000000000L) >> 48)& 0xff);
//        result[10] =(byte)(((classId64 & 0x0000ff0000000000L) >> 40)& 0xff);
//        result[11] =(byte)(((classId64 & 0x000000ff00000000L) >> 32)& 0xff);
//
//        //---Class ID--No.4 word----
//        result[12] =(byte)(((classId64 & 0x00000000ff000000L) >> 24)& 0xff);
//        result[13] =(byte)(((classId64 & 0x0000000000ff0000L) >> 16)& 0xff);
//        result[14] =(byte)(((classId64 & 0x000000000000ff00L) >> 8)& 0xff);
//        result[15] =(byte)((classId64 & 0x00000000000000ffL ));
//
//        //---Timestamp Int--No.5 word----
//        result[16] =(byte)(((integerTimestamp & 0xff000000) >> 24)& 0xff);
//        result[17] =(byte)(((integerTimestamp & 0x00ff0000) >> 16)& 0xff);
//        result[18] =(byte)(((integerTimestamp & 0x0000ff00) >> 8)& 0xff);
//        result[19] =(byte)(integerTimestamp & 0x000000ff);
//
//        //---Timestamp franc Int--No.6 No.7 word----
//        result[20] = (byte)(((fracTimeStamp & 0xff00000000000000L) >> 56)& 0xff);
//        result[21] = (byte)(((fracTimeStamp & 0x00ff000000000000L) >> 48)& 0xff);
//        result[22] = (byte)(((fracTimeStamp & 0x0000ff0000000000L) >> 40)& 0xff);
//        result[23] = (byte)(((fracTimeStamp & 0x000000ff00000000L) >> 32)& 0xff);
//        //---Class ID--No.4 word----
//
//        result[24] =(byte)(((fracTimeStamp & 0x00000000ff000000L) >> 24)& 0xff);
//        result[25] =(byte)(((fracTimeStamp & 0x0000000000ff0000L) >> 16)& 0xff);
//        result[26] =(byte)(((fracTimeStamp & 0x000000000000ff00L) >> 8)& 0xff);
//        result[27] =(byte)(fracTimeStamp & 0x00000000000000ffL ) ;

        for (int i = 0; i < payload.length ; i++) {
            result[28+i*2]=(byte)(payload[i]&0xff) ;
            result[28+i*2+1]= (byte)((payload[i]>>8)&0xff) ;
        }
        return result;
    }


    /**
     * Generate a VITA packet for audio stream; ID should be assigned by the radio's create stream
     * @param data audio stream data
     * @return VITA data packet
     */
    public byte[] audioFloatDataToVita(int count, float[] data) {
        byte[] result = new byte[data.length * 4 + 28];//One float takes 4 bytes, 28 bytes is the header length (7 words)
        packetType = VitaPacketType.EXT_DATA_WITH_STREAM;
        classIdPresent = true;
        trailerPresent = false;//No trailer
        // Timestamp-integer type MUST be TSI_UTC (1) to match the DAX-audio packets the
        // radio itself sends/accepts. We were sending TSI_OTHER (3); the radio rejected
        // those packets, so it keyed but had no modulation (0 W). Confirmed by logging the
        // working RX format (tsi=1) vs our TX format (tsi=3) — the only differing field.
        tsi = VitaTSI.TSI_UTC;
        tsf = VitaTSF.TSF_SAMPLE_COUNT;
        this.packetCount = count;
        // VITA packet_size is the TOTAL number of 32-bit words in the packet:
        // 7 header words + one word per float sample (each float = 4 bytes = 1 word).
        // The old "(data.length / 2) + 7" under-counted by half, so the declared size
        // (135 words) didn't match the real datagram (263 words); a strict VITA
        // receiver (SmartSDR) drops a packet whose declared size disagrees with its
        // length, which left the keyed radio with no modulation (ALC at the noise
        // floor, 0 W forward).
        this.packetSize = data.length + 7;


        //----HEADER--No.1 word------

        result[0] = (byte) (packetType.ordinal() << 4);//packetType
        if (classIdPresent) result[0] |=0x08;
        if (trailerPresent) result[0] |= 0x04;


        result[1] = (byte) (packetCount & 0xf);//packet count
        result[1] |= (byte) (tsi.ordinal() << 6);//TSI
        result[1] |= (byte) (tsf.ordinal() << 4);//TSF

        result[2] = (byte) ((packetSize & 0xff00 ) >> 8 & 0xff);//packetSize 1 (high 8 bits)
        result[3] = (byte) (packetSize & 0xff);//packetSize 2 (low 8 bits)

        //-----Stream Identifier--No.2 word----
        result[4] = (byte) (((streamId & 0x00ff000000) >> 24) & 0xff);
        result[5] = (byte) (((streamId & 0x0000ff0000) >> 16) & 0xff);
        result[6] = (byte) (((streamId & 0x000000ff00) >> 8) & 0xff);
        result[7] = (byte) (streamId &   0x00000000ff);
        //----Class ID--No.3 words----

        result[8] = 0x00;
        result[9] = (byte)(((classId64 & 0x00ff000000000000L) >> 48)& 0xff);
        result[10] =(byte)(((classId64 & 0x0000ff0000000000L) >> 40)& 0xff);
        result[11] =(byte)(((classId64 & 0x000000ff00000000L) >> 32)& 0xff);

        //---Class ID--No.4 word----
        result[12] =(byte)(((classId64 & 0x00000000ff000000L) >> 24)& 0xff);
        result[13] =(byte)(((classId64 & 0x0000000000ff0000L) >> 16)& 0xff);
        result[14] =(byte)(((classId64 & 0x000000000000ff00L) >> 8)& 0xff);
        result[15] =(byte)((classId64 & 0x00000000000000ffL ));

        //---Timestamp Int--No.5 word----
        result[16] =(byte)(((integerTimestamp & 0xff000000) >> 24)& 0xff);
        result[17] =(byte)(((integerTimestamp & 0x00ff0000) >> 16)& 0xff);
        result[18] =(byte)(((integerTimestamp & 0x0000ff00) >> 8)& 0xff);
        result[19] =(byte)(integerTimestamp & 0x000000ff);

        //---Timestamp franc Int--No.6 No.7 word----
        result[20] = (byte)(((fracTimeStamp & 0xff00000000000000L) >> 56)& 0xff);
        result[21] = (byte)(((fracTimeStamp & 0x00ff000000000000L) >> 48)& 0xff);
        result[22] = (byte)(((fracTimeStamp & 0x0000ff0000000000L) >> 40)& 0xff);
        result[23] = (byte)(((fracTimeStamp & 0x000000ff00000000L) >> 32)& 0xff);
        //---Class ID--No.4 word----

        result[24] =(byte)(((fracTimeStamp & 0x00000000ff000000L) >> 24)& 0xff);
        result[25] =(byte)(((fracTimeStamp & 0x0000000000ff0000L) >> 16)& 0xff);
        result[26] =(byte)(((fracTimeStamp & 0x000000000000ff00L) >> 8)& 0xff);
        result[27] =(byte)(fracTimeStamp & 0x00000000000000ffL ) ;


        for (int i = 0; i < data.length; i++) {
            byte[] bytes = ByteBuffer.allocate(4).putFloat(data[i]).array();//float to byte[]
            result[i * 4 + 28] = bytes[0];
            result[i * 4 + 29] = bytes[1];
            result[i * 4 + 30] = bytes[2];
            result[i * 4 + 31] = bytes[3];
        }




        return result;
    }


    public VITA() {
    }

    public VITA( VitaPacketType packetType
            , VitaTSI tsi, VitaTSF tsf
            , int packetCount
            , int streamId, long classId64) {
        this.streamIdPresent = true;
        this.packetType = packetType;
        this.classIdPresent = true;
        this.trailerPresent = false;
        this.tsi = tsi;
        this.tsf = tsf;
        this.packetCount = packetCount;
        this.streamId = streamId;
        this.classId64 = classId64;
        this.isAvailable = true;
    }

    public VITA(byte[] data) {
        this.buffer = data;
        //If the packet length is too small or the packet is null, exit
        if (data == null) return;
        if (data.length < VITAmin) return;

        isAvailable = true;//Data length reaches 28 bytes, indicating it is valid
        packetType = VitaPacketType.values()[(data[0] >> 4) & 0x0f];
        classIdPresent = (data[0] & 0x8) == 0x8;//Indicates whether the packet contains a class identifier (class ID) field
        trailerPresent = (data[0] & 0x4) == 0x4;//Indicates whether the packet contains a trailer
        tsi = VitaTSI.values()[(data[1] >> 6) & 0x3];//If timestamp exists, indicates the type of the integer part
        tsf = VitaTSF.values()[(data[1] >> 4) & 0x3];
        packetCount = data[1] & 0x0f;
        packetSize = ((((int) data[2]) & 0x00ff) << 8) | ((int) data[3]) & 0x00ff;

        int offset = 4;//Position
        //Check for stream identifier
        streamIdPresent = packetType == VitaPacketType.IF_DATA_WITH_STREAM
                || packetType == VitaPacketType.EXT_DATA_WITH_STREAM;

        if (streamIdPresent) {//If stream ID exists, get stream ID, 32-bit
            streamId = ((((int) data[offset]) & 0x00ff) << 24) | ((((int) data[offset + 1]) & 0x00ff) << 16)
                    | ((((int) data[offset + 2]) & 0x00ff) << 8) | ((int) data[offset + 3]) & 0x00ff;
            offset += 4;
        }

        if (classIdPresent) {
            //Only take 24 bits, first 8 bits are reserved
            oui = ((((int) data[offset + 1]) & 0x00ff) << 16)
                    | ((((int) data[offset + 2]) & 0x00ff) << 8) | ((int) data[offset + 3]) & 0x00ff;

            informationClassCode = ((((int) data[offset + 4]) & 0x00ff) << 8) | ((int) data[offset + 5]) & 0x00ff;
            packetClassCode = ((((int) data[offset + 6]) & 0x00ff) << 8) | ((int) data[offset + 7]) & 0x00ff;

            classId = ((((int) data[offset + 4]) & 0x00ff) << 24) | ((((int) data[offset + 5]) & 0x00ff) << 16)
                    | ((((int) data[offset + 6]) & 0x00ff) << 8) | ((int) data[offset + 7]) & 0x00ff;

            classId64 = ((((long) data[offset + 1]) & 0x00ff) << 48)
                    | ((((long) data[offset + 2]) & 0x00ff) << 40)
                    | ((((long) data[offset + 3]) & 0x00ff)<<32)
                    | ((((long) data[offset + 4]) & 0x00ff) << 24)
                    | ((((long) data[offset + 5]) & 0x00ff) << 16)
                    | ((((long) data[offset + 6]) & 0x00ff) << 8)
                    | ((long) data[offset + 7]) & 0x00ff;
            //Log.d(TAG,String.format("classId64:%X",classId64));
            offset += 8;
        }
        //Log.e(TAG, "VITA: "+String.format("id: 0x%x, classIdPresent:0x%x,packetSize:%d",streamId,classId,packetSize) );

        //Get timestamp in seconds, 32-bit.
        //Timestamp has two parts: fractional and integer. Integer part has second resolution, 32-bit, mainly for UTC or GPS time.
        //Fractional part has three types: sample-count (minimum resolution is sampling period), real-time (minimum unit is ps), and free-running count. The first two can be directly added to the integer part; the third cannot guarantee a constant relationship with the integer part. The first two can cover a time range of years.
        //Fractional timestamp is 64-bit and can be used without the integer part.
        //All timestamps reference a single sample data point.
        if (tsi != VitaTSI.TSI_NONE) {//32-bit
            integerTimestamp = ((((int) data[offset]) & 0x00ff) << 24) | ((((int) data[offset + 1]) & 0x00ff) << 16)
                    | ((((int) data[offset + 2]) & 0x00ff) << 8) | ((int) data[offset + 3]) & 0x00ff;
            offset += 4;
        }
        //Get the fractional part of the timestamp, 64-bit
        if (tsf != VitaTSF.TSF_NONE) {
            fracTimeStamp = ((((long) data[offset]) & 0x00ff) << 56) | ((((long) data[offset + 1]) & 0x00ff) << 48)
                    | ((((long) data[offset + 2]) & 0x00ff) << 40) | ((((long) data[offset + 3]) & 0x00ff) << 32)
                    | ((((long) data[offset + 4]) & 0x00ff) << 24) | ((((int) data[offset + 5]) & 0x00ff) << 16)
                    | ((((int) data[offset + 6]) & 0x00ff) << 8) | ((int) data[offset + 7]) & 0x00ff;
            offset += 8;
        }


        //Log.e(TAG, String.format("VITA: data length:%d,offset:%d",data.length,offset) );
        if (offset < data.length) {
            payload = new byte[data.length - offset - (trailerPresent ? 2 : 0)];//If there is a trailer, subtract one word position
            System.arraycopy(data, offset, payload, 0, payload.length);
        }
        if (trailerPresent) {
            trailer = ((((int) data[data.length - 2]) & 0x00ff) << 8) | ((int) data[data.length - 1]) & 0x00ff;
        }
    }

    /**
     * Get the payload length; returns 0 if there is no data
     *
     * @return payload length
     */
    public int getPayloadLength() {
        if (buffer == null) {
            return 0;
        } else {
            return buffer.length;
        }
    }

    /**
     * Display extended data
     *
     * @return string
     */
    public String showPayload() {
        if (payload != null) {
            return new String(payload);//.replace(" ", "\n");
        } else {
            return "";
        }
    }

    public String showPayloadHex() {
        if (payload != null) {
            return byteToStr(payload);
        } else {
            return "";
        }
    }

    /**
     * Display VITA 49 packet header info
     *
     * @return string
     */
    @SuppressLint("DefaultLocale")
    public String showHeadStr() {
        return String.format("packetType: %s\n" +
                        "packetCount: %d\n" +
                        "packetSize: %d\n" +
                        "streamIdPresent: %s\n" +
                        "streamId: 0x%X\n" +
                        "classIdPresent: %s\n" +
                        "classId: 0x%X\n" +
                        "informationClassCode: 0x%X\n" +
                        "packetClassCode: 0x%X\n" +
                        "oui: 0x%X\n" +
                        "tsi: %s\n" +
                        "integerTimestamp: 0x%X\n" +
                        "tsf: %s\n" +
                        "fracTimeStamp: 0x%X\n" +
                        "payloadLength: %d\n" +
                        "trailerPresent: %s\n"

                , packetType.toString()
                , packetCount
                , packetSize
                , streamIdPresent ? "Yes" : "No"
                , streamId
                , classIdPresent ? "Yes" : "No"
                , classId
                , informationClassCode
                , packetClassCode
                , oui
                , tsi.toString()
                , integerTimestamp
                , tsf.toString()
                , fracTimeStamp
                , (payload == null ? 0 : payload.length)
                , trailerPresent ? "Yes" : "No"
        );
    }

    /**
     * Display VITA 49 packet data
     *
     * @return string
     */
    @SuppressLint("DefaultLocale")
    @Override
    public String toString() {
        return String.format("%spayload:\n%s\n"
                , showHeadStr()
                , (payload == null ? "" : new String(payload))
        );


    }



    public static String byteToStr(byte[] data) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            s.append(String.format("%02x ", data[i] & 0xff));
        }
        return s.toString();
    }


    /**
     * Convert bytes to short with little-endian conversion
     *
     * @param data byte data
     * @param  start start position
     * @return short
     */
    public static short readShortDataBigEnd(byte[] data, int start) {
        if (data.length - start < 2) return 0;
        return (short) ((short) data[start] & 0xff
                | ((short) data[start +1] & 0xff) << 8);
    }

    /**
     * Convert bytes to short without little-endian conversion
     *
     * @param data byte data
     * @param  start start position
     * @return short
     */
    public static short readShortData(byte[] data, int start) {
        if (data.length - start < 2) return 0;
        return (short) ((short) data[start + 1] & 0xff
                | ((short) data[start] & 0xff) << 8);
    }

    public static float readShortFloat(byte[] data, int start) {
        if (data.length - start < 2) return 0.0f;
        int accum = 0;
        accum = accum | (data[start] & 0xff) << 0;
        accum = accum | (data[start + 1] & 0xff) << 8;
        return Float.intBitsToFloat(accum);
    }

}
