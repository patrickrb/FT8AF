package com.bg7yoz.ft8cn.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link MicRecorder#safeBufferSize} — the validation applied to
 * {@code AudioRecord.getMinBufferSize()} results before constructing an
 * AudioRecord. The system call can return ERROR (-1) or ERROR_BAD_VALUE (-2)
 * when the audio subsystem is unavailable; the helper must return a safe
 * fallback instead of passing the error through (which would crash).
 */
public class MicRecorderBufferSizeTest {

    @Test
    public void validBufferSize_passedThrough() {
        assertThat(MicRecorder.safeBufferSize(1920)).isEqualTo(1920);
    }

    @Test
    public void errorMinusOne_fallsBack() {
        // AudioRecord.ERROR == -1
        assertThat(MicRecorder.safeBufferSize(-1)).isEqualTo(MicRecorder.FALLBACK_BUFFER_SIZE);
    }

    @Test
    public void errorBadValue_fallsBack() {
        // AudioRecord.ERROR_BAD_VALUE == -2
        assertThat(MicRecorder.safeBufferSize(-2)).isEqualTo(MicRecorder.FALLBACK_BUFFER_SIZE);
    }

    @Test
    public void zero_fallsBack() {
        assertThat(MicRecorder.safeBufferSize(0)).isEqualTo(MicRecorder.FALLBACK_BUFFER_SIZE);
    }
}
