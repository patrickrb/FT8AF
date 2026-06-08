// FT4 encode JNI bridge.
//
// The shipping libft8cn.so is an upstream prebuilt (app/libs/<abi>/libft8cn.so,
// not compiled here). It exposes an FT8 encode JNI entry point
// (GenerateFT8.ft8_encode) but no FT4 equivalent — even though the FT4 DSP is
// present in the binary as the plain C symbol `ft4_encode` (kgoba ft8_lib).
//
// Rather than rebuild the closed prebuilt, we add the missing JNI wrapper here in
// libft8af_usb.so (which IS built by CMake) and link it against the prebuilt so the
// wrapper can call the existing `ft4_encode`. GenerateFT8 loads ft8af_usb so this
// symbol is resolvable. When the native reconstruction in ft8cn_glue/ eventually
// replaces the prebuilt, this shim should move there and be removed from here.
//
// Java side: GenerateFT8.ft4_encode(byte[] payload, byte[] tones). payload is the
// 12-byte a91 (77-bit message + CRC); only the first 10 bytes are read. tones must
// be at least 105 (FT4_NN). Mirrors the prebuilt's ft8_1encode wrapper exactly.

#include <jni.h>
#include <stdint.h>

extern "C" {

// Provided by the prebuilt libft8cn.so (exported C symbol).
void ft4_encode(const uint8_t* payload, uint8_t* tones);

JNIEXPORT void JNICALL
Java_com_bg7yoz_ft8cn_ft8transmit_GenerateFT8_ft4_1encode(
        JNIEnv* env, jclass, jbyteArray payload, jbyteArray tones)
{
    uint8_t payload_c[10] = {0};
    jsize plen = env->GetArrayLength(payload);
    env->GetByteArrayRegion(payload, 0, plen < 10 ? plen : 10,
                            reinterpret_cast<jbyte*>(payload_c));

    uint8_t tones_c[105];
    ft4_encode(payload_c, tones_c);

    if (env->GetArrayLength(tones) >= 105) {
        env->SetByteArrayRegion(tones, 0, 105,
                                reinterpret_cast<const jbyte*>(tones_c));
    }
}

} // extern "C"
