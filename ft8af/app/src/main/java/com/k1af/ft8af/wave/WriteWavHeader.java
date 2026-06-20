package com.k1af.ft8af.wave;
/**
 * Write the WAV file header.
 * Deprecated. FT8CN no longer performs audio file operations.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.media.AudioFormat;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

public class WriteWavHeader {
    private int samplesBits;
    private final int channels;
    private final int totalAudioLen;
    private final int longSampleRate;
    String TAG = "HamAudioRecorder";//debug tag

    public WriteWavHeader( int totalAudioLen, int longSampleRate, int channels, int samplesBits) {
        if (samplesBits == AudioFormat.ENCODING_PCM_16BIT)
            this.samplesBits = 16;
        else if (samplesBits == AudioFormat.ENCODING_PCM_8BIT)
            this.samplesBits = 8;

        if (channels == AudioFormat.CHANNEL_IN_STEREO)
            this.channels = 2;
        else
            this.channels = 1;
        this.totalAudioLen = totalAudioLen;
        this.longSampleRate = longSampleRate;

    }

    private byte[] makeWaveHeader(){
        int file_size = totalAudioLen + 44 - 8;//file size, excluding the preceding RIFF and file_size fields
        byte[] header = new byte[44];
        header[0] = 'R'; // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (file_size & 0xff);
        header[5] = (byte) ((file_size >> 8) & 0xff);
        header[6] = (byte) ((file_size >> 16) & 0xff);
        header[7] = (byte) ((file_size >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f'; // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16; // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1; // format = 1 indicates PCM encoding
        header[21] = 0;
        header[22] = (byte) channels;//1 = mono, 2 = stereo
        header[23] = 0;
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);

        header[28] = (byte) (samplesBits & 0xff);
        header[29] = (byte) ((samplesBits >> 8) & 0xff);
        header[30] = (byte) ((samplesBits >> 16) & 0xff);
        header[31] = (byte) ((samplesBits >> 24) & 0xff);

        //2-byte block length (bytes per sample = channels * bits per sample / 8)
        if (samplesBits==AudioFormat.ENCODING_PCM_16BIT) {
            header[32] = (byte) (channels * samplesBits);
            header[33] = 0;
            header[34] = 16; // bits per sample
            header[35] = 0;
        }else if (samplesBits==AudioFormat.ENCODING_PCM_8BIT){
            header[32] = (byte) (channels );
            header[33] = 0;
            header[34] = 8; // bits per sample
            header[35] = 0;
        }else {
            header[32] = (byte) (channels * samplesBits / 8);
            header[33] = 0;
            header[34] = (byte) samplesBits; //bits per sample
            header[35] = 0;
        }


        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        //PCM audio data size
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);
        return header;
    }

    public void writeHeader(DataOutputStream dos) {

        try {
            dos.write(makeWaveHeader());
        } catch (IOException e) {
            Log.e(TAG, String.format("Error creating WAV file header (WriteWavHeader)! %s", e.getMessage()));
        }

    }
    public void modifyHeader(String fileName) {

        try {
            RandomAccessFile raf=new RandomAccessFile(fileName,"rw");
            raf.seek(0);
            raf.write(makeWaveHeader());
            raf.close();
        } catch (IOException e) {
            Log.e(TAG, String.format("Error modifying WAV file header (modifyHeader)! %s", e.getMessage()));
        }

    }

}
