// FT8 callsign hash JNI wrappers — from-source replacement for libft8cn.so.
//
// Replaces the prebuilt's getHash10, getHash12, getHash22 JNI entry points.
// The hash algorithm is the WSJT-X 22-bit callsign hash (base-38 over an
// 11-char field, times the magic constant 47055833459, top 22 bits). Proven
// correct against golden vectors in test_golden_encode.c.
//
// Java side: com.bg7yoz.ft8cn.ft8signal.FT8Package

#include <jni.h>
#include <stdint.h>
#include <string.h>

extern "C" {
#include "ft8/text.h"
#include "ft8/constants.h"
}

// ---------------------------------------------------------------------------
// compute_n22 — the WSJT-X callsign hash.
//
// Identical to the implementation in ft2_decode_jni.cpp and test_golden_encode.c.
// Base-38 encoding over an 11-char field using FT8_CHAR_TABLE_ALPHANUM_SPACE_SLASH,
// multiplied by 47055833459, top 22 bits extracted.
//
// h22 = n22
// h12 = n22 >> 10
// h10 = n22 >> 12
// ---------------------------------------------------------------------------
static uint32_t compute_n22(const char* call)
{
    uint64_t n58 = 0;
    int i = 0;
    for (; call[i] != '\0' && i < 11; ++i)
    {
        char c = call[i];
        if (c >= 'a' && c <= 'z')
            c = (char)(c - 'a' + 'A');
        int j = nchar(c, FT8_CHAR_TABLE_ALPHANUM_SPACE_SLASH);
        if (j < 0)
            j = 0; // treat unknown chars as space (index 0)
        n58 = 38 * n58 + (uint64_t)j;
    }
    for (; i < 11; ++i) // pad to 11 chars with spaces (index 0)
        n58 = 38 * n58;
    return (uint32_t)(((47055833459ull * n58) >> (64 - 22)) & 0x3FFFFFul);
}

// ---------------------------------------------------------------------------
// getHash22: return the full 22-bit hash of a callsign.
// Java: public static native int getHash22(String callsign);
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_ft8signal_FT8Package_getHash22(
        JNIEnv* env, jclass, jstring callsign)
{
    const char* call_c = env->GetStringUTFChars(callsign, nullptr);
    if (!call_c) return 0;

    uint32_t n22 = compute_n22(call_c);
    env->ReleaseStringUTFChars(callsign, call_c);
    return (jint)n22;
}

// ---------------------------------------------------------------------------
// getHash12: return the 12-bit hash (top 12 bits of n22).
// Java: public static native int getHash12(String callsign);
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_ft8signal_FT8Package_getHash12(
        JNIEnv* env, jclass, jstring callsign)
{
    const char* call_c = env->GetStringUTFChars(callsign, nullptr);
    if (!call_c) return 0;

    uint32_t n22 = compute_n22(call_c);
    env->ReleaseStringUTFChars(callsign, call_c);
    return (jint)(n22 >> 10);
}

// ---------------------------------------------------------------------------
// getHash10: return the 10-bit hash (top 10 bits of n22).
// Java: public static native int getHash10(String callsign);
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_bg7yoz_ft8cn_ft8signal_FT8Package_getHash10(
        JNIEnv* env, jclass, jstring callsign)
{
    const char* call_c = env->GetStringUTFChars(callsign, nullptr);
    if (!call_c) return 0;

    uint32_t n22 = compute_n22(call_c);
    env->ReleaseStringUTFChars(callsign, call_c);
    return (jint)(n22 >> 12);
}
