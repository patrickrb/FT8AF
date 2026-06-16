package com.bg7yoz.ft8cn.ft8transmit;
/**
 * Class for generating FT8 audio signals. Audio data is a 32-bit float array.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.ModeProfile;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.ft8signal.FT8Package;
import com.bg7yoz.ft8cn.ui.ToastMessage;

public class GenerateFT8 {
    private static final String TAG = "GenerateFT8";
    private static final int FTX_LDPC_K = 91;
    public static final int FTX_LDPC_K_BYTES = (FTX_LDPC_K + 7) / 8;
    private static final int FT8_NN = 79;
    private static final float FT8_SYMBOL_PERIOD = 0.160f;
    private static final float FT8_SYMBOL_BT = 2.0f;
    private static final float FT8_SLOT_TIME = 15.0f;
    private static final int Ft8num_samples = 15 * 12000;
    private static final float M_PI = 3.14159265358979323846f;

    public static final int num_tones = FT8_NN;// number of symbols: FT8 is 79, FT4 is 105
    public static final float symbol_period = FT8_SYMBOL_PERIOD;// FT8_SYMBOL_PERIOD=0.160f
    private static final float symbol_bt = FT8_SYMBOL_BT;// FT8_SYMBOL_BT=2.0f
    private static final float slot_time = FT8_SLOT_TIME;// FT8_SLOT_TIME=15f
    //public static int sample_rate = 48000;// sample rate
    //public static int sample_rate = 12000;// sample rate


    static {
        try {
            System.loadLibrary("ft8af");
        } catch (UnsatisfiedLinkError e) {
            // Best-effort load: JVM unit tests don't have the native libs on
            // java.library.path. The native methods themselves will throw if
            // actually invoked without the library; the pure-Java helpers on
            // this class stay available either way.
            Log.w(TAG, "ft8af native library not loaded: " + e.getMessage());
        }
    }


    public static int checkI3ByCallsign(String callsign) {
        if (callsign == null || callsign.length() < 2) {
            return 0;
        }
        String substring = callsign.substring(callsign.length() - 2);
        if (substring.equals("/P")) {
            if (callsign.length() <= 8) {
                return 2;// i3=2 message
            } else {
                return 4;// non-standard callsign
            }
        }
        if (substring.equals("/R")) {
            if (callsign.length() <= 8) {
                return 1;// i3=1 message
            } else {
                return 4;// non-standard callsign
            }
        }
        if (callsign.contains("/")) {// except /P and /R, all others are non-standard callsigns
            return 4;
        }
        if (callsign.length() > 6) {// callsign longer than 6 characters is also non-standard
            return 4;
        }
        if (callsign.length() == 0) {// no callsign means free text
            return 0;
        }
        return 1;
    }

    public static String byteToBinString(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder string = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            string.append(String.format(",%8s", Integer.toBinaryString(data[i] & 0xff)).replace(" ", "0"));
        }
        return string.toString();
    }

    public static String byteToHexString(byte[] data) {
        StringBuilder string = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            string.append(String.format(",%02X", data[i]));
        }
        return string.toString();
    }


    /**
     * Check if it is a standard callsign.
     *
     * @param callsign callsign
     * @return true/false
     */
    public static boolean checkIsStandardCallsign(String callsign) {
        String temp;
        if (callsign.endsWith("/P") || callsign.endsWith("/R")){
            temp=callsign.substring(0,callsign.length()-2);
        }else {
            temp=callsign;
        }
        // FT8 definition: a standard amateur callsign consists of a one or two character prefix (at least one must be a letter), followed by a decimal digit and up to three letter suffix.
        return temp.matches("[A-Z0-9]?[A-Z0-9][0-9][A-Z][A-Z0-9]?[A-Z]?");


    }

    /**
     * Check if it is a signal report.
     *
     * @param extraInfo extra info
     * @return true/false
     */
    private static boolean checkIsReport(String extraInfo) {
        if (extraInfo.equals("73") || extraInfo.equals("RRR")
                || extraInfo.equals("RR73")||extraInfo.equals("")) {
            return false;
        }
        return !extraInfo.trim().matches("[A-Z][A-Z][0-9][0-9]");
    }

    public static float[] generateFt8(Ft8Message msg, float frequency,int sample_rate){
        return generateFt8(msg,frequency,sample_rate,true);
    }

    public static byte[] generateA91(Ft8Message msg,boolean hasModifier){
        if (msg.callsignFrom.length()<3){
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.callsign_error));
            return null;
        }
        // first, pack the text data into a binary message, 12 bytes total
        byte[] packed = new byte[FTX_LDPC_K_BYTES];
        // strip "<>" characters
        msg.callsignTo = msg.callsignTo.replace("<", "").replace(">", "");
        msg.callsignFrom = msg.callsignFrom.replace("<", "").replace(">", "");
        if (hasModifier) {
            msg.modifier = GeneralVariables.toModifier;// modifier
        }else {
            msg.modifier="";
        }


        // conditions for using non-standard callsign i3=4:
        // 1. FROMCALL is a non-standard callsign, and satisfies 2 or 3
        // 2. extra info is grid, RR73, RRR, 73
        // 3. CQ, QRZ, DE



        if (msg.i3 != 0) {// currently only supports i3=1, i3=2, i3=4, i3=0 && n3=0
            if (!checkIsStandardCallsign(msg.callsignFrom)
                    && (!checkIsReport(msg.extraInfo) || msg.checkIsCQ())) {
                msg.i3 = 4;
            //} else if (msg.callsignFrom.endsWith("/P")||(msg.callsignTo.endsWith("/P"))) {
            } else if (msg.callsignFrom.endsWith("/P")// if the target has a /P suffix, use the target callsign; if not, use the sender's /P suffix
                    ||(msg.callsignTo.endsWith("/P")&&(!msg.callsignFrom.endsWith("/P")))) {
                msg.i3 = 2;
            } else {
                msg.i3 = 1;
            }
        }

        if (msg.i3 == 1 || msg.i3 == 2) {
            packed = FT8Package.generatePack77_i1(msg);
        } else if (msg.i3 == 4) {// non-standard callsign
            packed = FT8Package.generatePack77_i4(msg);
        } else {
            packFreeTextTo77(msg.getMessageText(), packed);
        }

        return packed;
    }

    /**
     * Generate FT8 signal.
     * @param msg message
     * @param frequency frequency
     * @param sample_rate sample rate
     * @param hasModifier whether it has a modifier
     * @return
     */
    public static float[] generateFt8(Ft8Message msg, float frequency,int sample_rate,boolean hasModifier) {
//        if (msg.callsignFrom.length()<3){
//            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.callsign_error));
//            return null;
//        }
//        // first, pack the text data into a binary message, 12 bytes total
//        byte[] packed = new byte[FTX_LDPC_K_BYTES];
//        // strip "<>" characters
//        msg.callsignTo = msg.callsignTo.replace("<", "").replace(">", "");
//        msg.callsignFrom = msg.callsignFrom.replace("<", "").replace(">", "");
//        if (hasModifier) {
//            msg.modifier = GeneralVariables.toModifier;// modifier
//        }else {
//            msg.modifier="";
//        }

        // conditions for using non-standard callsign i3=4:
        // 1. FROMCALL is a non-standard callsign, and satisfies 2 or 3
        // 2. extra info is grid, RR73, RRR, 73
        // 3. CQ, QRZ, DE



//        if (msg.i3 != 0) {// currently only supports i3=1, i3=2, i3=4, i3=0 && n3=0
//            if (!checkIsStandardCallsign(msg.callsignFrom)
//                    && (!checkIsReport(msg.extraInfo) || msg.checkIsCQ())) {
//                msg.i3 = 4;
//            } else if (msg.callsignFrom.endsWith("/P") || (msg.callsignTo.endsWith("/P"))) {
//                msg.i3 = 2;
//            } else {
//                msg.i3 = 1;
//            }
//        }
//
//        if (msg.i3 == 1 || msg.i3 == 2) {
//            packed = FT8Package.generatePack77_i1(msg);
//        } else if (msg.i3 == 4) {// non-standard callsign
//            packed = FT8Package.generatePack77_i4(msg);
//        } else {
//            packFreeTextTo77(msg.getMessageText(), packed);
//        }

        return generateFt8ByA91(generateA91(msg,hasModifier),frequency,sample_rate);
        //return generateFt8ByA91(packed,frequency,sample_rate);

    }

    public static float[] generateFt8ByA91(byte[] a91, float frequency,int sample_rate){
        return generateFt8ByA91(a91, frequency, sample_rate, GeneralVariables.currentMode());
    }

    /**
     * Generate the mode's audio waveform from an a91 payload. FT8 and FT4 share the same
     * 77-bit payload but differ in tone count, symbol period, BT product, and native
     * encoder (see {@link ModeProfile}). The {@code mode} parameter is explicit so this is
     * unit-testable without touching global state.
     */
    public static float[] generateFt8ByA91(byte[] a91, float frequency, int sample_rate, ModeProfile mode){
        byte[] tones = new byte[mode.numTones]; // FT8: 79 symbols, FT4: 105
        // a91 is the 12-byte (91+7)/8 payload; the native encoder fills the tone array
        mode.encode(a91, tones);

        // convert FSK tones to audio signal
        int num_samples = (int) (0.5f + mode.numTones * mode.symbolPeriod * sample_rate); // FT8: 0.5+79*0.16*12000

        float[] signal = new float[num_samples];
        for (int i = 0; i < num_samples; i++)// silence all data
        {
            signal[i] = 0;
        }

        // generate audio from the symbol array
        synth_gfsk(tones, mode.numTones, frequency, mode.symbolBt, mode.symbolPeriod, sample_rate, signal, 0);
        return signal;
    }

    /** Encode an a91 payload into 79 FT8 tones (native ft8_encode). */
    public static void ft8Encode(byte[] a91, byte[] tones) {
        ft8_encode(a91, tones);
    }

    /** Encode an a91 payload into 105 FT4 tones (native ft4_encode). */
    public static void ft4Encode(byte[] a91, byte[] tones) {
        ft4_encode(a91, tones);
    }


    private static native int packFreeTextTo77(String msg, byte[] c77);

    private static native int pack77(String msg, byte[] c77);

    private static native void ft8_encode(byte[] payload, byte[] tones);

    private static native void ft4_encode(byte[] payload, byte[] tones);

    private static native void synth_gfsk(byte[] symbols, int n_sym, float f0,
                                          float symbol_bt, float symbol_period,
                                          int signal_rate, float[] signal, int offset);
}
