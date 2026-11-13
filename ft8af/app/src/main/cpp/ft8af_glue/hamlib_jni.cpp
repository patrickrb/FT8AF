/*
 * JNI bridge to hamlib, with a loopback-TCP transport shim.
 *
 * WHY A LOOPBACK SOCKET
 *   hamlib owns the CAT *protocol* (per-radio framing); the app owns the *byte
 *   transport* (its existing USB-serial CableSerialPort). hamlib on Android
 *   cannot open a tty, so instead of patching hamlib's serial layer we present
 *   each rig to hamlib as a network port: we listen on 127.0.0.1:<ephemeral>
 *   and set the rig pathname to that address. hamlib auto-detects the IP:port
 *   (src/rig.c) and uses RIG_PORT_NETWORK, so its serial/termios code is never
 *   touched and NO hamlib source patch is required.
 *
 * DATA FLOW (full duplex over the accepted server socket `srv_fd`)
 *   hamlib writes a CAT command  -> readable on srv_fd -> pump thread reads it
 *       -> Java sink.onBytesToRig(bytes) -> CableSerialPort -> radio
 *   radio replies -> Java onReceiveData -> nativeFeedFromRig(bytes)
 *       -> write(srv_fd) -> hamlib's blocking read() gets the ack
 *
 * THREADING
 *   hamlib's rig_* calls are synchronous (write-then-read-ack). They are invoked
 *   from HamlibRig's dedicated worker thread (never the UI thread, and never the
 *   serial read thread that delivers nativeFeedFromRig) so the ack can arrive
 *   while a rig_* call blocks. The pump thread services hamlib's outbound bytes.
 */
#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>
#include <pthread.h>
#include <atomic>
#include <cstring>
#include <cstdlib>

#include <hamlib/rig.h>

#include "hamlib_feed.h"

#define TAG "HamlibJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *g_vm = nullptr;

// Lazily cache the JavaVM from any JNI call (avoids a JNI_OnLoad that would
// collide with other translation units in libft8af.so).
static void ensure_vm(JNIEnv *env) {
    if (g_vm == nullptr) {
        env->GetJavaVM(&g_vm);
    }
}

struct HamlibBridge {
    RIG *rig = nullptr;
    // fds + running are touched from the worker thread (open/close), the pump
    // thread (accept/read), and nativeFeedFromRig (write) — std::atomic gives
    // the needed atomicity + visibility that `volatile` does not.
    std::atomic<int> listen_fd{-1};
    std::atomic<int> srv_fd{-1};   // accepted server side; pump reads, feed writes
    pthread_t pump = 0;
    std::atomic<bool> running{false};
    jobject sink = nullptr;   // global ref to HamlibNative.HamlibSink
    jmethodID onBytesToRig = nullptr;
    int model_id = 0;   // for logging only; set in nativeCreate, read in nativeRigOpen
    bool opened = false;   // rig_open() succeeded; nativeClose only rig_close()s then
};

// ---- pump thread: hamlib's outbound CAT bytes -> Java sink ------------------
static void *pump_main(void *arg) {
    auto *b = static_cast<HamlibBridge *>(arg);
    JNIEnv *env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("pump: AttachCurrentThread failed");
        return nullptr;
    }
    unsigned char buf[512];
    while (b->running) {
        ssize_t n = read(b->srv_fd, buf, sizeof(buf));
        if (n > 0) {
            jbyteArray arr = env->NewByteArray((jsize) n);
            if (arr == nullptr) { break; }
            env->SetByteArrayRegion(arr, 0, (jsize) n, reinterpret_cast<jbyte *>(buf));
            env->CallVoidMethod(b->sink, b->onBytesToRig, arr);
            if (env->ExceptionCheck()) { env->ExceptionClear(); }
            env->DeleteLocalRef(arr);
        } else if (n == 0) {
            break;              // hamlib closed the connection
        } else {
            if (b->running) { LOGW("pump: read error, exiting"); }
            break;
        }
    }
    g_vm->DetachCurrentThread();
    return nullptr;
}

// Acceptor runs briefly: accept the connection hamlib makes during rig_open,
// then becomes the pump loop.
static void *accept_then_pump(void *arg) {
    auto *b = static_cast<HamlibBridge *>(arg);
    int fd = accept(b->listen_fd, nullptr, nullptr);
    if (fd < 0) {
        LOGE("acceptor: accept failed");
        return nullptr;
    }
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    b->srv_fd = fd;
    return pump_main(b);
}

// ---- helpers ---------------------------------------------------------------
static HamlibBridge *handle_to_bridge(jlong h) {
    return reinterpret_cast<HamlibBridge *>(h);
}

extern "C" {

/*
 * nativeCreate: build the bridge and start the pump thread, but do NOT call
 * rig_open() yet. Returns an opaque handle (>0) or 0 on failure.
 *
 * Split from the open call (see nativeRigOpen below) so Java can publish the
 * handle to HamlibRig.handle BEFORE rig_open()'s own synchronous handshake
 * runs. HamlibRig.onReceiveData() only forwards radio bytes into the bridge
 * when handle != 0 — with the original single nativeOpen(), handle was only
 * set after it returned, so every byte the radio sent during rig_open()'s own
 * handshake (its ID;/FA; probes) was silently dropped on the Java side, and
 * hamlib always saw 0 chars and timed out, even against a radio that was
 * actually replying. Confirmed on real hardware: serial.recv showed real
 * traffic arriving while hamlib's own trace reported "Timed out ... after 0
 * chars" for the exact same window, every single time.
 */
JNIEXPORT jlong JNICALL
Java_com_k1af_ft8af_wave_HamlibNative_nativeCreate(JNIEnv *env, jclass,
                                                    jint modelId, jobject sink) {
    ensure_vm(env);
    auto *b = new HamlibBridge();

    // 1. loopback listener on an ephemeral port
    b->listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (b->listen_fd < 0) { LOGE("socket() failed"); delete b; return 0; }
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0; // ephemeral
    if (bind(b->listen_fd, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) < 0 ||
        listen(b->listen_fd, 1) < 0) {
        LOGE("bind/listen failed");
        close(b->listen_fd); delete b; return 0;
    }
    socklen_t alen = sizeof(addr);
    getsockname(b->listen_fd, reinterpret_cast<sockaddr *>(&addr), &alen);
    int port = ntohs(addr.sin_port);

    // 2. cache the Java sink callback
    b->sink = env->NewGlobalRef(sink);
    jclass sinkCls = env->GetObjectClass(sink);
    b->onBytesToRig = env->GetMethodID(sinkCls, "onBytesToRig", "([B)V");
    if (b->onBytesToRig == nullptr) {
        LOGE("sink.onBytesToRig([B)V not found");
        env->DeleteGlobalRef(b->sink); close(b->listen_fd); delete b; return 0;
    }

    // 3. init the rig and point it at our loopback listener
    rig_set_debug(RIG_DEBUG_WARN);
    b->rig = rig_init((rig_model_t) modelId);
    if (b->rig == nullptr) {
        LOGE("rig_init(%d) failed", modelId);
        env->DeleteGlobalRef(b->sink); close(b->listen_fd); delete b; return 0;
    }
    char path[64];
    snprintf(path, sizeof(path), "127.0.0.1:%d", port);
    rig_set_conf(b->rig, rig_token_lookup(b->rig, "rig_pathname"), path);
    rig_set_conf(b->rig, rig_token_lookup(b->rig, "timeout"), "1000");
    rig_set_conf(b->rig, rig_token_lookup(b->rig, "retry"), "2");

    // 4. start the acceptor/pump BEFORE rig_open (which connects + handshakes)
    b->running = true;
    if (pthread_create(&b->pump, nullptr, accept_then_pump, b) != 0) {
        LOGE("pthread_create(pump) failed");
        rig_cleanup(b->rig); env->DeleteGlobalRef(b->sink);
        close(b->listen_fd); delete b; return 0;
    }

    b->model_id = modelId;
    LOGI("hamlib bridge for model %d created on 127.0.0.1:%d (not yet opened)",
         modelId, port);
    return reinterpret_cast<jlong>(b);
}

/*
 * nativeRigOpen: run rig_open() on an already-created bridge (see
 * nativeCreate above for why this is split out). Java must publish the
 * handle to HamlibRig.handle before calling this, so nativeFeedFromRig can
 * deliver bytes the radio sends during rig_open()'s own handshake.
 *
 * Returns RIG_OK (0) on success, or a (negative) hamlib error code. Either
 * way the bridge is left alive: the caller (HamlibRig) owns releasing it via
 * nativeClose, exactly once, never concurrently with nativeFeedFromRig. A
 * free done here on failure raced the serial read thread, which can still be
 * inside nativeFeedFromRig(handle, …) on this same handle — the radio is
 * actively replying to the handshake when rig_open() fails, so that race was
 * not theoretical.
 */
JNIEXPORT jint JNICALL
Java_com_k1af_ft8af_wave_HamlibNative_nativeRigOpen(JNIEnv *, jclass,
                                                     jlong handle) {
    auto *b = handle_to_bridge(handle);
    if (b == nullptr) return -1;

    int rc = rig_open(b->rig);
    if (rc != RIG_OK) {
        LOGE("rig_open failed: %d (%s)", rc, rigerror(rc));
        return rc;   // caller still owns the bridge and frees it via nativeClose
    }
    b->opened = true;
    LOGI("hamlib rig %d opened", b->model_id);
    return RIG_OK;
}

/* Feed bytes received from the real radio into hamlib's read side. */
JNIEXPORT void JNICALL
Java_com_k1af_ft8af_wave_HamlibNative_nativeFeedFromRig(JNIEnv *env, jclass,
                                                        jlong handle, jbyteArray data) {
    auto *b = handle_to_bridge(handle);
    if (b == nullptr || data == nullptr || b->srv_fd < 0) return;
    jsize len = env->GetArrayLength(data);
    if (len <= 0) return;
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) {
        // The pin failed (OOM / heap pressure) and left a pending exception.
        // Clear it so the serial read thread's next JNI call isn't poisoned,
        // and drop this frame instead of dereferencing NULL in write().
        env->ExceptionClear();
        return;
    }
    hamlib_feed_write(b->srv_fd.load(),
                      reinterpret_cast<const unsigned char *>(bytes), (long) len);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_k1af_ft8af_wave_HamlibNative_nativeSetFreq(JNIEnv *, jclass,
                                                    jlong handle, jlong hz) {
    auto *b = handle_to_bridge(handle);
    if (b == nullptr || b->rig == nullptr) return -1;
    return rig_set_freq(b->rig, RIG_VFO_CURR, (freq_t) hz);
}

JNIEXPORT jlong JNICALL
Java_com_k1af_ft8af_wave_HamlibNative_nativeGetFreq(JNIEnv *, jclass, jlong handle) {
    auto *b = handle_to_bridge(handle);
    if (b == nullptr || b->rig == nullptr) return -1;
    freq_t f = 0;
    int rc = rig_get_freq(b->rig, RIG_VFO_CURR, &f);
    if (rc != RIG_OK) return -1;
    return (jlong) f;
}

/* mode is a hamlib mode string, e.g. "USB", "PKTUSB". */
JNIEXPORT jint JNICALL
Java_com_k1af_ft8af_wave_HamlibNative_nativeSetMode(JNIEnv *env, jclass,
                                                    jlong handle, jstring mode) {
    auto *b = handle_to_bridge(handle);
    if (b == nullptr || b->rig == nullptr || mode == nullptr) return -1;
    const char *s = env->GetStringUTFChars(mode, nullptr);
    if (s == nullptr) {
        // GetStringUTFChars can return NULL (with a pending exception) when the
        // JVM cannot allocate the UTF-8 copy. Clear it and bail rather than pass
        // NULL to rig_parse_mode().
        env->ExceptionClear();
        return -1;
    }
    rmode_t m = rig_parse_mode(s);
    env->ReleaseStringUTFChars(mode, s);
    if (m == RIG_MODE_NONE) return -2;
    return rig_set_mode(b->rig, RIG_VFO_CURR, m, RIG_PASSBAND_NORMAL);
}

JNIEXPORT jint JNICALL
Java_com_k1af_ft8af_wave_HamlibNative_nativeSetPtt(JNIEnv *, jclass,
                                                   jlong handle, jboolean on) {
    auto *b = handle_to_bridge(handle);
    if (b == nullptr || b->rig == nullptr) return -1;
    return rig_set_ptt(b->rig, RIG_VFO_CURR, on ? RIG_PTT_ON : RIG_PTT_OFF);
}

JNIEXPORT void JNICALL
Java_com_k1af_ft8af_wave_HamlibNative_nativeClose(JNIEnv *env, jclass, jlong handle) {
    auto *b = handle_to_bridge(handle);
    if (b == nullptr) return;
    b->running = false;
    // Unblock the pump whether it is parked in accept() (never connected) or
    // read(): close the listener first, then shut down the accepted socket.
    // Joining before this could deadlock. See nativeRigOpen's failure path.
    int lfd = b->listen_fd.exchange(-1);
    if (lfd >= 0) { shutdown(lfd, SHUT_RDWR); close(lfd); }
    int sfd = b->srv_fd.load();
    if (sfd >= 0) shutdown(sfd, SHUT_RDWR);
    if (b->pump) pthread_join(b->pump, nullptr);
    if (b->rig) {
        if (b->opened) rig_close(b->rig);
        rig_cleanup(b->rig);
    }
    sfd = b->srv_fd.load();
    if (sfd >= 0) close(sfd);
    if (b->sink) env->DeleteGlobalRef(b->sink);
    delete b;
}

} // extern "C"
