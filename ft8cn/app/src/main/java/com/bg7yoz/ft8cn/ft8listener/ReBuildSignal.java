package com.bg7yoz.ft8cn.ft8listener;

import android.util.Log;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;
import com.bg7yoz.ft8cn.wave.WaveFileWriter;

import java.util.ArrayList;

public class ReBuildSignal {
    private static String TAG = "ReBuildSignal";
    static {
        try {
            System.loadLibrary("ft8cn");
            // doSubtractSignalFt2 lives in ft8af_usb (the from-source FT2 decoder), not ft8cn.
            System.loadLibrary("ft8af_usb");
        } catch (UnsatisfiedLinkError e) {
            // Best-effort load (mirrors GenerateFT8); native methods throw if invoked without
            // the library, but class init must not crash (e.g. JVM unit tests).
            Log.w(TAG, "native library not loaded: " + e.getMessage());
        }
    }


    public static void subtractSignal(long decoder,A91List a91List){
        for (A91List.A91 a91 : a91List.list) {
            doSubtractSignal(decoder,a91.a91,FT8Common.SAMPLE_RATE,a91.freq_hz,a91.time_sec);
        }
    }

    /** Deep-decode subtraction for an FT2 decoder handle (from-source ft8_lib). */
    public static void subtractSignalFt2(long decoder,A91List a91List){
        for (A91List.A91 a91 : a91List.list) {
            doSubtractSignalFt2(decoder,a91.a91,FT8Common.SAMPLE_RATE,a91.freq_hz,a91.time_sec);
        }
    }

    private static native void doSubtractSignal(long decoder,byte[] payload,int sample_rate
            ,float frequency,float time_sec);

    private static native void doSubtractSignalFt2(long decoder,byte[] payload,int sample_rate
            ,float frequency,float time_sec);

}
