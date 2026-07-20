package com.k1af.ft8af.ft8transmit;
/**
 * Class for generating FT8 audio signals. Audio data is a 32-bit float array.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.ModeProfile;
import com.k1af.ft8af.R;
import com.k1af.ft8af.ft8signal.FT8Package;
import com.k1af.ft8af.ui.ToastMessage;

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



        // Field Day messages (i3=0, n3=3 or n3=4) skip the callsign-format
        // override logic below — they use their own dedicated packer.
        boolean isFieldDay = (msg.i3 == 0 && (msg.n3 == 3 || msg.n3 == 4));

        if (msg.i3 != 0 && !isFieldDay) {// supports i3=1, i3=2, i3=4, i3=0&&n3=0, i3=0&&n3=3/4 (FD)
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
        } else if (isFieldDay) {
            packed = FT8Package.generatePack77_fd(msg);
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
        // generateA91 returns null for an invalid own callsign (<3 chars): it has already
        // shown the "callsign_error" toast and the transmit must abort gracefully. Feeding
        // that null into the native encoder below (mode.encode -> ft8_encode/ft4_encode,
        // whose JNI wrappers call GetArrayLength(null)/GetByteArrayRegion(null,...) with no
        // null guard) is an UNCATCHABLE native SIGSEGV on the TX thread. Return null instead
        // so callers take their existing null path (setPTT(false)/afterPlayAudio()), which is
        // exactly the graceful abort they were written against.
        if (a91 == null) {
            return null;
        }
        byte[] tones = new byte[mode.numTones]; // FT8: 79 symbols, FT4: 105
        // a91 is the 12-byte (91+7)/8 payload; the native encoder fills the tone array
        mode.encode(a91, tones);

        // Size the output buffer to EXACTLY the number of samples the native GFSK
        // synthesiser (synth_gfsk, gfsk.c) will write — see waveformSampleCount.
        int num_samples = waveformSampleCount(mode.numTones, mode.symbolPeriod, sample_rate);

        float[] signal = new float[num_samples];
        for (int i = 0; i < num_samples; i++)// silence all data
        {
            signal[i] = 0;
        }

        // generate audio from the symbol array
        synth_gfsk(tones, mode.numTones, frequency, mode.symbolBt, mode.symbolPeriod, sample_rate, signal, 0);
        return signal;
    }

    /**
     * Number of audio samples the native GFSK synthesiser writes for {@code numTones}
     * symbols at {@code sampleRate}: {@code numTones * samplesPerSymbol}, where
     * {@code samplesPerSymbol} is rounded the <em>same</em> way the native code rounds it
     * (see {@code synth_gfsk_dphi_alloc} in {@code gfsk.c}:
     * {@code n_spsym = (int)(0.5f + signal_rate * symbol_period)}, then
     * {@code n_wave = n_sym * n_spsym}).
     *
     * <p>The Java output buffer handed to {@code synth_gfsk} MUST be sized exactly this
     * way. Sizing it as {@code round(numTones * symbolPeriod * sampleRate)} instead — the
     * previous behaviour — rounds the total once rather than per-symbol, so the two counts
     * diverge whenever {@code sampleRate * symbolPeriod} is not an integer. When the total
     * rounds <em>down</em> while the native per-symbol product rounds up, the native writer
     * runs past the end of the Java {@code float[]} — a heap out-of-bounds write on the TX
     * thread (native memory corruption, uncatchable). This bites at any audio rate that is
     * not a clean multiple of the symbol rate, e.g. a 44100 Hz {@code audioRate} restored
     * from a settings backup while running FT4/FT2 (the UI only offers 12/24/48 kHz, where
     * {@code sampleRate * symbolPeriod} is always integral and the two formulas agree
     * bit-for-bit, so this is byte-identical for every supported configuration).
     *
     * <p>Package-visible and free of JNI/global state so the sizing is unit-testable.
     *
     * @param numTones     channel symbols in the mode (79 FT8, 105 FT4/FT2)
     * @param symbolPeriod GFSK symbol period in seconds (0.160 FT8, 0.048 FT4, 0.024 FT2)
     * @param sampleRate   output audio sample rate in Hz
     * @return the exact sample count native {@code synth_gfsk} writes, or 0 for a
     *         degenerate (non-positive) rate/period/tone count, or a product that
     *         overflows a 32-bit {@code int} (a corrupted/hand-edited backup rate)
     */
    static int waveformSampleCount(int numTones, float symbolPeriod, int sampleRate) {
        int samplesPerSymbol = Math.round(symbolPeriod * sampleRate);
        if (numTones <= 0 || samplesPerSymbol <= 0) {
            return 0;
        }
        // Compute in long so a corrupted, out-of-range sampleRate (round-tripped
        // verbatim through a settings backup) can't overflow the int multiply into a
        // negative/too-small length — which would reintroduce the very OOB/NegativeArraySize
        // failure this sizing guards against. Refuse (0) rather than hand back a bogus size.
        long total = (long) numTones * samplesPerSymbol;
        if (total > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) total;
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
