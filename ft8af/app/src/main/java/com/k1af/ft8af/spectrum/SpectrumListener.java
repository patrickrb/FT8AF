package com.k1af.ft8af.spectrum;
/**
 * Audio receiver for the waterfall display. Granularity is one FT8 symbol.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.MutableLiveData;

import com.k1af.ft8af.wave.HamRecorder;
import com.k1af.ft8af.wave.OnGetVoiceDataDone;

public class SpectrumListener {
    private static final String TAG = "SpectrumListener";
    private HamRecorder hamRecorder;

    private float[] dataBuffer=new float[0];
    public MutableLiveData<float[]> mutableDataBuffer = new MutableLiveData<>();

    // Post each update individually to the main thread.
    // LiveData.postValue() coalesces: if a new value arrives before the main
    // thread processes the previous one, the earlier value is silently dropped.
    // At ~6 updates/sec this causes most waterfall frames to be lost when the
    // main thread is busy with Compose layout or view rendering.
    // Handler.post + setValue bypasses this — each update gets its own message.
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final OnGetVoiceDataDone onGetVoiceDataDone=new OnGetVoiceDataDone() {
        @Override
        public void onGetDone(float[] data) {
            // Clone because VoiceDataMonitor reuses the same float[] buffer.
            // Without the clone, the main thread observer would read partially-
            // overwritten data from the next recording cycle.
            float[] copy = data.clone();
            mainHandler.post(() -> mutableDataBuffer.setValue(copy));
        }
    };

    public SpectrumListener(HamRecorder hamRecorder) {
        this.hamRecorder = hamRecorder;
        doReceiveData();
    }


    private void doReceiveData(){
        hamRecorder.getVoiceData(160,false,onGetVoiceDataDone);
    }

    public float[] getDataBuffer() {
        return dataBuffer;
    }
}
