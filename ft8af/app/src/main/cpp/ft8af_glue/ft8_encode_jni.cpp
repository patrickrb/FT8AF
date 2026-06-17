// FT8/FT4 encode JNI wrappers — from-source replacements for libft8cn.so.
//
// Replaces the prebuilt's pack77, packFreeTextTo77, ft8_encode, ft4_encode,
// and synth_gfsk JNI entry points. All calls route to the in-tree kgoba ft8_lib
// (pinned at 6f528128) and the ft8af_glue GFSK synthesizer.
//
// Java side: com.k1af.ft8af.ft8transmit.GenerateFT8

#include <jni.h>
#include <stdint.h>
#include <string.h>

extern "C" {
#include "ft8/pack.h"
#include "ft8/encode.h"
#include "ft8/constants.h"

// Declared in ft8af_glue/gfsk.c (no separate header).
void synth_gfsk_offset(const uint8_t* symbols, int n_sym, float f0,
                       float symbol_bt, float symbol_period,
                       int signal_rate, float* signal, int offset);
}

// ---------------------------------------------------------------------------
// pack77: parse a structured FT8/FT4 message into a 77-bit payload.
// Java: private static native int pack77(String msg, byte[] c77);
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_k1af_ft8af_ft8transmit_GenerateFT8_pack77(
        JNIEnv* env, jclass, jstring msg, jbyteArray c77)
{
    const char* msg_c = env->GetStringUTFChars(msg, nullptr);
    if (!msg_c) return -1;

    uint8_t payload[10] = {0};
    int rc = pack77(msg_c, payload);
    env->ReleaseStringUTFChars(msg, msg_c);

    if (env->GetArrayLength(c77) >= 10) {
        env->SetByteArrayRegion(c77, 0, 10,
                                reinterpret_cast<const jbyte*>(payload));
    }
    return rc;
}

// ---------------------------------------------------------------------------
// packFreeTextTo77: pack arbitrary text as a free-text (i3=0, n3=0) payload.
// Java: private static native int packFreeTextTo77(String msg, byte[] c77);
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_k1af_ft8af_ft8transmit_GenerateFT8_packFreeTextTo77(
        JNIEnv* env, jclass, jstring msg, jbyteArray c77)
{
    const char* msg_c = env->GetStringUTFChars(msg, nullptr);
    if (!msg_c) return -1;

    uint8_t payload[10] = {0};
    packtext77(msg_c, payload);
    env->ReleaseStringUTFChars(msg, msg_c);

    if (env->GetArrayLength(c77) >= 10) {
        env->SetByteArrayRegion(c77, 0, 10,
                                reinterpret_cast<const jbyte*>(payload));
    }
    return 0;
}

// ---------------------------------------------------------------------------
// ft8_encode: encode a 77-bit payload into 79 FT8 tones (8-GFSK, 0..7).
// Java: private static native void ft8_encode(byte[] payload, byte[] tones);
// JNI name: ft8_1encode (underscore in Java method name → _1 in JNI).
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ft8transmit_GenerateFT8_ft8_1encode(
        JNIEnv* env, jclass, jbyteArray payload, jbyteArray tones)
{
    uint8_t payload_c[10] = {0};
    jsize plen = env->GetArrayLength(payload);
    env->GetByteArrayRegion(payload, 0, plen < 10 ? plen : 10,
                            reinterpret_cast<jbyte*>(payload_c));

    uint8_t tones_c[FT8_NN]; // 79
    ft8_encode(payload_c, tones_c);

    if (env->GetArrayLength(tones) >= FT8_NN) {
        env->SetByteArrayRegion(tones, 0, FT8_NN,
                                reinterpret_cast<const jbyte*>(tones_c));
    }
}

// ---------------------------------------------------------------------------
// ft4_encode: encode a 77-bit payload into 105 FT4 tones (4-GFSK, 0..3).
// Java: private static native void ft4_encode(byte[] payload, byte[] tones);
// JNI name: ft4_1encode.
// Absorbs the standalone ft4_encode_jni.cpp (which bridged to the prebuilt).
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ft8transmit_GenerateFT8_ft4_1encode(
        JNIEnv* env, jclass, jbyteArray payload, jbyteArray tones)
{
    uint8_t payload_c[10] = {0};
    jsize plen = env->GetArrayLength(payload);
    env->GetByteArrayRegion(payload, 0, plen < 10 ? plen : 10,
                            reinterpret_cast<jbyte*>(payload_c));

    uint8_t tones_c[FT4_NN]; // 105
    ft4_encode(payload_c, tones_c);

    if (env->GetArrayLength(tones) >= FT4_NN) {
        env->SetByteArrayRegion(tones, 0, FT4_NN,
                                reinterpret_cast<const jbyte*>(tones_c));
    }
}

// ---------------------------------------------------------------------------
// synth_gfsk: synthesize a GFSK audio waveform from a tone sequence.
// Java: private static native void synth_gfsk(byte[] symbols, int n_sym,
//           float f0, float symbol_bt, float symbol_period, int signal_rate,
//           float[] signal, int offset);
// JNI name: synth_1gfsk (underscore → _1).
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ft8transmit_GenerateFT8_synth_1gfsk(
        JNIEnv* env, jclass,
        jbyteArray symbols, jint n_sym,
        jfloat f0, jfloat symbol_bt, jfloat symbol_period,
        jint signal_rate, jfloatArray signal, jint offset)
{
    if (!symbols || !signal) return;

    jsize sym_len = env->GetArrayLength(symbols);
    if (sym_len <= 0 || n_sym <= 0) return;
    int count = (n_sym < sym_len) ? n_sym : sym_len;

    // Get the tone bytes (Java byte is signed; cast to uint8_t for the C API).
    jbyte* sym_j = env->GetByteArrayElements(symbols, nullptr);
    if (!sym_j) return;

    uint8_t* sym_c = reinterpret_cast<uint8_t*>(sym_j);

    // Get the output signal buffer.
    jfloat* sig = env->GetFloatArrayElements(signal, nullptr);
    if (!sig) {
        env->ReleaseByteArrayElements(symbols, sym_j, JNI_ABORT);
        return;
    }

    synth_gfsk_offset(sym_c, count, f0, symbol_bt, symbol_period,
                      signal_rate, sig, offset);

    env->ReleaseFloatArrayElements(signal, sig, 0); // commit changes
    env->ReleaseByteArrayElements(symbols, sym_j, JNI_ABORT);
}
