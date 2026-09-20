// Native USB audio capture stub. Phase 1: just confirms the JNI bridge
// works. Phase 2 will wire in libusb + isochronous capture so the raw
// USB Audio Class path can survive on hosts where Android's UsbRequest
// can't drive iso transfers (notably automotive Android variants).

#include <jni.h>
#include <android/log.h>
#include <libusb.h>
#include <cerrno>
#include <cstdio>
#include <string>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

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

// Force a USB port-level reset (USBDEVFS_RESET) on an open device fd from
// UsbDeviceConnection.getFileDescriptor(). Equivalent to an unplug/replug
// without touching the cable: the kernel re-enumerates the device and rebuilds
// its endpoint state. Used by the CAT auto-reconnect escalation — a CP210x
// that RF has wedged into a state where the port re-opens but every URB submit
// fails ("Queueing USB request failed") only recovers through re-enumeration,
// never through open/close (2026-09-20 field log: 17 consecutive sub-second
// reopen-deaths, cleared only by a physical replug).
//
// Plain ioctl, not libusb: we have no libusb session for the serial device,
// and the one-shot reset needs none.
extern "C" JNIEXPORT jint JNICALL
Java_com_k1af_ft8af_wave_UsbAudioNative_nativeResetDevice(
        JNIEnv * /* env */, jclass /* clazz */, jint fd) {
    int rc = ioctl(fd, USBDEVFS_RESET);
    if (rc < 0) {
        int err = errno;
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "USBDEVFS_RESET failed: rc=%d errno=%d", rc, err);
        return err > 0 ? -err : -1;
    }
    LOGI("USBDEVFS_RESET ok (fd=%d)", fd);
    return 0;
}
