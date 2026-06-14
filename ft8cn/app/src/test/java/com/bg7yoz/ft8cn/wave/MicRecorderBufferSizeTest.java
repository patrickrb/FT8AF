package com.bg7yoz.ft8cn.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests the buffer size validation added to MicRecorder init.
 * {@code AudioRecord.getMinBufferSize()} can return ERROR (-1) or
 * ERROR_BAD_VALUE (-2) when the audio subsystem is unavailable.
 * The recorder must use a safe fallback instead of crashing.
 */
public class MicRecorderBufferSizeTest {

    /** Replicates the validation logic from the MicRecorder constructor. */
    static int safeBufferSize(int rawBufferSize) {
        if (rawBufferSize <= 0) {
            return 4096;
        }
        return rawBufferSize;
    }

    @Test
    public void validBufferSize_passedThrough() {
        assertThat(safeBufferSize(1920)).isEqualTo(1920);
    }

    @Test
    public void errorMinusOne_fallsBack() {
        // AudioRecord.ERROR == -1
        assertThat(safeBufferSize(-1)).isEqualTo(4096);
    }

    @Test
    public void errorBadValue_fallsBack() {
        // AudioRecord.ERROR_BAD_VALUE == -2
        assertThat(safeBufferSize(-2)).isEqualTo(4096);
    }

    @Test
    public void zero_fallsBack() {
        assertThat(safeBufferSize(0)).isEqualTo(4096);
    }
}
