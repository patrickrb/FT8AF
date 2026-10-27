package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.GeneralVariables;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Tests that {@link MicRecorder#stopRecord()} retires the AudioRecord reader
 * thread before it returns. RX is paused and resumed around every QMX over
 * (stopRecord() then start()); if stop only cleared {@code isRunning}, a
 * reader still blocked in read() would see {@code isRunning} true again after
 * a quick resume and keep reading alongside the new thread -- two readers on
 * one AudioRecord is a fatal native abort in libaudioclient.
 */
@RunWith(RobolectricTestRunner.class)
public class MicRecorderStopRetiresReaderTest {

    @Before
    public void useThePlainAudioRecordPath() {
        GeneralVariables.audioInputDeviceId = 0;
        GeneralVariables.usbAudioInputVendorId = 0;
        GeneralVariables.usbAudioInputProductId = 0;
    }

    @Test
    public void stopRecord_supersedesTheReaderGeneration() {
        MicRecorder rec = new MicRecorder();
        int before = rec.captureGenerationForTest();
        rec.stopRecord();
        assertThat(rec.captureGenerationForTest()).isGreaterThan(before);
    }

    @Test
    public void stopRecord_waitsForTheReaderThreadToExit() {
        MicRecorder rec = new MicRecorder();
        rec.start();
        Thread reader = rec.captureThreadForTest();
        assertThat(reader).isNotNull();
        rec.stopRecord();
        assertThat(reader.isAlive()).isFalse();
    }

    @Test
    public void quickStopThenStart_leavesOnlyTheNewReaderRunning() {
        MicRecorder rec = new MicRecorder();
        rec.start();
        Thread first = rec.captureThreadForTest();
        assertThat(first).isNotNull();
        rec.stopRecord();
        rec.start();
        try {
            assertThat(first.isAlive()).isFalse();
            assertThat(rec.captureThreadForTest()).isNotSameInstanceAs(first);
        } finally {
            rec.stopRecord();
        }
    }
}
