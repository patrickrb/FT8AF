package com.k1af.ft8af.wave;

/**
 * Defines the callback interface for after audio recording ends.
 * There are 2 main callback interfaces: before recording starts, and after recording starts.
 * IMPORTANT! Recording uses multithreading, so these callbacks are NOT on the main thread.
 * If the callback involves UI operations, use runOnUiThread to prevent UI lockup.
 *
 * @author BG7YOZ
 * @date 2022.5.7
 */

public interface OnAudioRecorded {
    /**
     * Callback function before recording starts.
     * @param audioFileName the generated WAV filename
     */
    void beginAudioRecord(String audioFileName);

    /**
     * Callback function after recording ends.
     * @param audioFileName the WAV filename
     * @param dataSize the size of the recording data in byte[] format, excluding the WAV file header length; add 44 to get the full WAV file length
     * @param duration the actual recording duration (seconds)
     */
    void endAudioRecorded(String audioFileName,long dataSize,float duration);
}
