// Native USB audio capture stub. Phase 1: just confirms the JNI bridge
// works. Phase 2 will wire in libusb + isochronous capture so the raw
// USB Audio Class path can survive on hosts where Android's UsbRequest
// can't drive iso transfers (notably automotive Android variants).

#include <jni.h>
#include <android/log.h>
#include <libusb.h>
#include <cstdio>
#include <string>

#define TAG "ft8af_usb_native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_k1af_ft8af_wave_UsbAudioNative_nativeBuildString(
        JNIEnv *env, jclass /* clazz */) {
    // Calls into libusb to confirm static linkage worked. If libusb's
    // symbols are stripped, this won't compile; if it loads but libusb
    // is broken, the version line still tells us which build we shipped.
    const libusb_version *v = libusb_get_version();
    char buf[160];
    std::snprintf(buf, sizeof(buf),
                  "ft8af_usb (libusb %d.%d.%d.%d%s)",
                  v->major, v->minor, v->micro, v->nano,
                  v->rc ? v->rc : "");
    LOGI("nativeBuildString -> %s", buf);
    return env->NewStringUTF(buf);
}
