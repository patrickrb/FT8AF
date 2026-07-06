// Libusb-backed USB Audio Class isochronous capture.
//
// The user picks "(USB direct)" in Settings → Audio Input. The Java side
// (UsbAudioDevice) opens the device via Android's UsbManager, finds the
// audio streaming interface + iso input endpoint, sets the alt-setting
// (which is what actually turns the audio stream on), and then hands
// the resulting file descriptor + interface params to nativeStart()
// here. We then drive the iso transfers via libusb on a worker thread
// and call back into Java with mono float[] samples ready for the
// FT8 decoder.
//
// We do this instead of using Android's UsbRequest API because that
// API's iso path crashes on some kernels (UsbRequest.initialize()
// returns false and queue() throws IllegalStateException). libusb
// talks to USBDEVFS directly and is much more permissive about
// what the kernel actually accepts.

#include <jni.h>
#include <android/log.h>
#include <libusb.h>

#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <vector>

#include "fir_decimator.h"
#include "rational_resampler.h"

#define TAG "ft8af_usb_capture"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Each in-flight iso URB carries this many packets. 8 is a balance between
// latency and syscall overhead — at 48k stereo 16-bit, one packet is ~192
// bytes (1ms of audio), so 8 packets per transfer ≈ 8ms latency.
constexpr int kPacketsPerTransfer = 8;
// Number of transfers we keep submitted at once. With 4 transfers × 8
// packets × 1ms/packet = 32ms of buffering, plenty for the FT8 decoder
// loop which expects a 12kHz mono float stream.
constexpr int kNumTransfers = 4;

struct CaptureSession {
    libusb_context*           ctx       = nullptr;
    libusb_device_handle*     handle    = nullptr;
    JavaVM*                   vm        = nullptr;
    jobject                   callback  = nullptr;   // global ref
    jmethodID                 onData    = nullptr;
    jmethodID                 onStopped = nullptr;

    int                       endpoint        = 0;
    int                       maxPacketSize   = 0;
    int                       inputRate       = 48000;
    int                       inputChannels   = 2;
    int                       inputBytesPerSample = 2;   // 16-bit PCM
    int                       targetRate      = 12000;
    int                       decimationRatio = 4;       // inputRate / targetRate

    std::vector<libusb_transfer*> transfers;
    std::thread                   eventThread;
    std::atomic<bool>             running{false};
    std::atomic<int>              inFlight{0};
    // Why the capture session ended, surfaced to Java via onCaptureStopped(code)
    // and logged to debug.log. 0 = clean stop (nativeStop). Otherwise the first
    // cause recorded wins (see recordStopReason / kStopReason* below): this is
    // the diagnostic that pins down why iso capture retires ~100ms in on some
    // host+adapter combos, which logcat's native tag doesn't reliably surface.
    std::atomic<int>              stopReason{0};

    // Anti-aliasing decimator: a windowed-sinc FIR low-pass + integer decimation, replacing
    // the old box-filter average. Configured with the real ratio in nativeStart. `monoScratch`
    // holds one transfer's worth of mono samples to hand to it; `outScratch` holds the decimated
    // result. Both are reused across iso callbacks (which fire every ~8ms) so the hot capture
    // path does no per-transfer heap allocation.
    FirDecimator        decimator{4};
    // True rational resampling for devices whose rate is not an integer multiple
    // of the target (issue #364): 44.1 kHz -> 12 kHz used to be floored to /3,
    // producing 14.7 kHz audio labeled 12 kHz — every FT8 tone off the 6.25 Hz
    // grid, zero decodes. When `useRational` is set, `resampler` (polyphase
    // L/M, e.g. 40/147) replaces `decimator`; the 48 kHz integer fast path is
    // untouched.
    RationalResampler   resampler;
    bool                useRational = false;
    std::vector<float>  monoScratch;
    std::vector<float>  outScratch;
};

// ----------------------------------------------------------------------------
// JNI helpers
// ----------------------------------------------------------------------------

JNIEnv* attachJniEnv(CaptureSession* s, bool* attached) {
    JNIEnv* env = nullptr;
    int rc = s->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (rc == JNI_OK) {
        *attached = false;
        return env;
    }
    if (s->vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        *attached = true;
        return env;
    }
    LOGE("AttachCurrentThread failed");
    *attached = false;
    return nullptr;
}

void detachIfNeeded(CaptureSession* s, bool attached) {
    if (attached) s->vm->DetachCurrentThread();
}

void emitAudioData(CaptureSession* s, const float* data, int length) {
    bool attached = false;
    JNIEnv* env = attachJniEnv(s, &attached);
    if (!env) return;
    jfloatArray arr = env->NewFloatArray(length);
    if (arr) {
        env->SetFloatArrayRegion(arr, 0, length, data);
        env->CallVoidMethod(s->callback, s->onData, arr, length);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(arr);
    }
    detachIfNeeded(s, attached);
}

void emitStopped(CaptureSession* s, int code) {
    bool attached = false;
    JNIEnv* env = attachJniEnv(s, &attached);
    if (!env) return;
    env->CallVoidMethod(s->callback, s->onStopped, code);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    detachIfNeeded(s, attached);
}

// ----------------------------------------------------------------------------
// Iso transfer callback
// ----------------------------------------------------------------------------
//
// libusb invokes this from libusb_handle_events() on our event thread once an
// iso transfer completes (or errors / is cancelled). For each packet that
// returned actual data we convert little-endian int16 samples to mono float
// and feed them into the decimation accumulator. When the accumulator has
// enough samples for a 12kHz block we emit a callback to Java.

// Stop-reason encoding, decoded by UsbAudioDevice.describeCaptureStopCode() on
// the Java side and written to debug.log. The bases keep the three failure
// families distinguishable while still carrying the underlying code:
//   1000 + libusb_transfer_status  — a transfer completed with a terminal
//          status and was not resubmitted (e.g. 1005 = NO_DEVICE, 1003 =
//          CANCELLED, 1001 = ERROR).
//   2000 + (-libusb_error)         — libusb_submit_transfer() refused to
//          re-arm a transfer (e.g. 2005 = NO_DEVICE, 2001 = IO).
//   3000 + (-libusb_error)         — libusb_handle_events() itself failed.
//   1                              — all transfers retired with no specific
//          terminal cause recorded (transient errors that stopped resubmitting).
constexpr int kStopReasonRetiredUnknown       = 1;
constexpr int kStopReasonBaseTransferStatus   = 1000;
constexpr int kStopReasonBaseResubmit         = 2000;
constexpr int kStopReasonBaseHandleEvents     = 3000;

// Record why capture is ending — first cause wins, so the originating failure
// isn't overwritten by the teardown it triggers.
inline void recordStopReason(CaptureSession* s, int code) {
    int expected = 0;
    s->stopReason.compare_exchange_strong(expected, code);
}

void LIBUSB_CALL onTransferComplete(libusb_transfer* xfer) {
    auto* s = static_cast<CaptureSession*>(xfer->user_data);

    if (!s->running.load(std::memory_order_acquire)) {
        s->inFlight.fetch_sub(1, std::memory_order_acq_rel);
        return;
    }

    if (xfer->status != LIBUSB_TRANSFER_COMPLETED) {
        LOGW("iso transfer status=%d (non-fatal; resubmitting)", xfer->status);
        // For NO_DEVICE / CANCELLED we don't resubmit — let the event loop
        // tear down. For other transient errors (TIMED_OUT, STALL) we keep
        // pumping; isochronous is best-effort by spec.
        if (xfer->status == LIBUSB_TRANSFER_NO_DEVICE
                || xfer->status == LIBUSB_TRANSFER_CANCELLED) {
            recordStopReason(s, kStopReasonBaseTransferStatus + xfer->status);
            s->running.store(false, std::memory_order_release);
            s->inFlight.fetch_sub(1, std::memory_order_acq_rel);
            return;
        }
    }

    s->monoScratch.clear();
    for (int p = 0; p < xfer->num_iso_packets; ++p) {
        libusb_iso_packet_descriptor& pkt = xfer->iso_packet_desc[p];
        if (pkt.status != LIBUSB_TRANSFER_COMPLETED) continue;
        if (pkt.actual_length == 0) continue;

        const uint8_t* buf = libusb_get_iso_packet_buffer_simple(xfer, p);
        if (!buf) continue;

        // Walk the PCM bytes, converting little-endian int16 → float in
        // [-1, 1]. For stereo we average the channels (FT8 only needs mono);
        // for mono we just copy.
        int bytesPerFrame = s->inputBytesPerSample * s->inputChannels;
        int frames = pkt.actual_length / bytesPerFrame;
        for (int i = 0; i < frames; ++i) {
            const uint8_t* frame = buf + i * bytesPerFrame;
            int16_t l = static_cast<int16_t>(frame[0] | (frame[1] << 8));
            float sample;
            if (s->inputChannels == 2) {
                int16_t r = static_cast<int16_t>(frame[2] | (frame[3] << 8));
                sample = ((float)l + (float)r) * (0.5f / 32768.0f);
            } else {
                sample = (float)l / 32768.0f;
            }
            s->monoScratch.push_back(sample);
        }
    }

    // Anti-alias + resample to the target rate. Both stages carry state across transfers, so
    // partial input is fine — they just emit ~frames*L/M output samples per call.
    if (!s->monoScratch.empty()) {
        // Reused buffer: clear() keeps the capacity from prior callbacks, so process()'s
        // push_backs don't reallocate on the hot path.
        s->outScratch.clear();
        if (s->useRational) {
            s->resampler.process(s->monoScratch.data(),
                                 (int)s->monoScratch.size(), s->outScratch);
        } else {
            s->decimator.process(s->monoScratch.data(),
                                 (int)s->monoScratch.size(), s->outScratch);
        }
        if (!s->outScratch.empty()) {
            emitAudioData(s, s->outScratch.data(), (int)s->outScratch.size());
        }
    }

    // Re-submit this transfer.
    int rc = libusb_submit_transfer(xfer);
    if (rc != 0) {
        LOGW("libusb_submit_transfer failed: %s", libusb_error_name(rc));
        recordStopReason(s, kStopReasonBaseResubmit + (-rc));
        s->running.store(false, std::memory_order_release);
        s->inFlight.fetch_sub(1, std::memory_order_acq_rel);
    }
}

// ----------------------------------------------------------------------------
// Event-loop worker thread
// ----------------------------------------------------------------------------

void eventLoop(CaptureSession* s) {
    LOGI("event loop started");
    while (s->running.load(std::memory_order_acquire)) {
        timeval tv{0, 100000};  // 100ms
        int rc = libusb_handle_events_timeout_completed(s->ctx, &tv, nullptr);
        if (rc != 0 && rc != LIBUSB_ERROR_INTERRUPTED) {
            LOGW("libusb_handle_events failed: %s", libusb_error_name(rc));
            recordStopReason(s, kStopReasonBaseHandleEvents + (-rc));
            break;
        }
        if (s->inFlight.load(std::memory_order_acquire) == 0) {
            // All transfers retired without being re-submitted (e.g., device
            // gone). Exit cleanly so Java can decide whether to re-arm.
            recordStopReason(s, kStopReasonRetiredUnknown);
            LOGW("all transfers retired; ending capture (stopReason=%d)",
                 s->stopReason.load());
            break;
        }
    }
    s->running.store(false, std::memory_order_release);
    LOGI("event loop exiting; cancelling outstanding transfers");

    // Drain any still-pending transfers so libusb can free them safely.
    for (auto* t : s->transfers) {
        libusb_cancel_transfer(t);
    }
    // Process cancellations.
    while (s->inFlight.load(std::memory_order_acquire) > 0) {
        timeval tv{0, 50000};
        libusb_handle_events_timeout_completed(s->ctx, &tv, nullptr);
    }

    // Report why capture ended. 0 means running was cleared externally
    // (nativeStop) with no transfer-level failure — a clean, requested stop.
    emitStopped(s, s->stopReason.load());
}

}  // namespace

// ----------------------------------------------------------------------------
// JNI entry points
// ----------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_k1af_ft8af_wave_UsbAudioNative_nativeStart(
        JNIEnv* env,
        jclass /*clazz*/,
        jint fd,
        jint /*interfaceNumber*/,
        jint /*altSetting*/,
        jint endpointAddress,
        jint maxPacketSize,
        jint inputSampleRate,
        jint inputChannels,
        jint inputBytesPerSample,
        jint targetSampleRate,
        jobject callback) {

    // The Java side has already opened the device, claimed the interface,
    // and set the alt-setting via Android's UsbManager. We don't need libusb
    // to enumerate or open anything — just wrap the existing fd.
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);

    libusb_context* ctx = nullptr;
    int rc = libusb_init(&ctx);
    if (rc != 0) {
        LOGE("libusb_init: %s", libusb_error_name(rc));
        return 0;
    }

    libusb_device_handle* handle = nullptr;
    rc = libusb_wrap_sys_device(ctx, (intptr_t)fd, &handle);
    if (rc != 0 || !handle) {
        LOGE("libusb_wrap_sys_device(fd=%d): %s", fd, libusb_error_name(rc));
        libusb_exit(ctx);
        return 0;
    }

    auto* s = new CaptureSession();
    s->ctx                 = ctx;
    s->handle              = handle;
    env->GetJavaVM(&s->vm);
    s->callback            = env->NewGlobalRef(callback);
    s->endpoint            = endpointAddress;
    s->maxPacketSize       = maxPacketSize;
    s->inputRate           = inputSampleRate;
    s->inputChannels       = inputChannels > 0 ? inputChannels : 2;
    s->inputBytesPerSample = inputBytesPerSample > 0 ? inputBytesPerSample : 2;
    s->targetRate          = targetSampleRate;
    // Integer decimation ratio (e.g. 48k/12k = 4), computed defensively so a bad
    // rate pair can't divide-by-zero or mis-rate the decoder. Warn on the
    // degenerate cases the helper folds into a pass-through ratio of 1. A rate
    // pair that isn't an integer multiple (e.g. a 44.1 kHz-only codec) gets the
    // exact polyphase rational resampler instead of a floored ratio, which used
    // to mislabel 14.7 kHz audio as 12 kHz and shift every FT8 tone off the
    // 6.25 Hz grid (issue #364).
    const int ratio = decimationRatioFor(inputSampleRate, targetSampleRate);
    if (targetSampleRate <= 0 || inputSampleRate < targetSampleRate) {
        LOGE("invalid sample rates input=%d target=%d; disabling decimation "
             "(ratio=1, audio passed through unchanged)",
             inputSampleRate, targetSampleRate);
    } else if (inputSampleRate % targetSampleRate != 0) {
        s->useRational = true;
    }
    s->decimationRatio     = ratio;
    if (s->useRational) {
        const RationalRatio lm = rationalRatioFor(inputSampleRate, targetSampleRate);
        s->resampler.configure(lm.L, lm.M);
        LOGI("input rate %d is not an integer multiple of target %d; using rational "
             "polyphase resampler L/M=%d/%d (exact output rate %d)",
             inputSampleRate, targetSampleRate, lm.L, lm.M,
             (int)((int64_t)inputSampleRate * lm.L / lm.M));
    } else {
        // Build the anti-aliasing FIR for the actual integer ratio.
        s->decimator.configure(ratio);
    }
    s->monoScratch.reserve(inputSampleRate / 100);  // ~10ms of input headroom
    s->outScratch.reserve(targetSampleRate > 0
            ? targetSampleRate / 100 + 2
            : inputSampleRate / 100 + 2);           // resampled headroom

    jclass cbClass = env->GetObjectClass(callback);
    s->onData    = env->GetMethodID(cbClass, "onAudioData",       "([FI)V");
    s->onStopped = env->GetMethodID(cbClass, "onCaptureStopped",  "(I)V");
    if (!s->onData || !s->onStopped) {
        LOGE("callback methods not found on UsbAudioNative$AudioInputCallback");
        env->DeleteGlobalRef(s->callback);
        libusb_close(handle);
        libusb_exit(ctx);
        delete s;
        return 0;
    }

    // Allocate and submit the iso transfers. Iso bandwidth is fixed by spec
    // so we use maxPacketSize as the per-packet length — short reads are
    // fine, libusb sets actual_length per packet on completion.
    s->transfers.reserve(kNumTransfers);
    s->running.store(true, std::memory_order_release);

    for (int i = 0; i < kNumTransfers; ++i) {
        libusb_transfer* xfer = libusb_alloc_transfer(kPacketsPerTransfer);
        if (!xfer) {
            LOGE("libusb_alloc_transfer failed at i=%d", i);
            continue;
        }
        int bufLen = s->maxPacketSize * kPacketsPerTransfer;
        auto* buf = (uint8_t*)std::malloc(bufLen);
        if (!buf) {
            libusb_free_transfer(xfer);
            continue;
        }
        libusb_fill_iso_transfer(
                xfer, handle, (unsigned char)s->endpoint,
                buf, bufLen, kPacketsPerTransfer,
                onTransferComplete, s, /*timeout=*/0);
        libusb_set_iso_packet_lengths(xfer, s->maxPacketSize);
        // libusb_free_transfer with LIBUSB_TRANSFER_FREE_BUFFER frees buf for us.
        xfer->flags = LIBUSB_TRANSFER_FREE_BUFFER;

        int rcSub = libusb_submit_transfer(xfer);
        if (rcSub != 0) {
            LOGE("submit transfer i=%d: %s", i, libusb_error_name(rcSub));
            libusb_free_transfer(xfer);
            continue;
        }
        s->transfers.push_back(xfer);
        s->inFlight.fetch_add(1, std::memory_order_acq_rel);
    }

    if (s->transfers.empty()) {
        LOGE("no transfers could be submitted; aborting");
        s->running.store(false, std::memory_order_release);
        env->DeleteGlobalRef(s->callback);
        libusb_close(handle);
        libusb_exit(ctx);
        delete s;
        return 0;
    }

    LOGI("started capture: fd=%d ep=0x%02x maxPkt=%d inputRate=%d ch=%d targetRate=%d "
         "L/M=%d/%d transfers=%zu packets/xfer=%d",
         fd, s->endpoint, s->maxPacketSize, s->inputRate, s->inputChannels,
         s->targetRate,
         s->useRational ? s->resampler.interpolation() : 1,
         s->useRational ? s->resampler.decimation() : s->decimationRatio,
         s->transfers.size(), kPacketsPerTransfer);

    s->eventThread = std::thread(eventLoop, s);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_wave_UsbAudioNative_nativeStop(
        JNIEnv* env, jclass /*clazz*/, jlong handlePtr) {
    if (handlePtr == 0) return;
    auto* s = reinterpret_cast<CaptureSession*>(handlePtr);

    s->running.store(false, std::memory_order_release);
    libusb_interrupt_event_handler(s->ctx);  // wake the event loop

    if (s->eventThread.joinable()) {
        s->eventThread.join();
    }

    // The event loop frees its own transfers via LIBUSB_TRANSFER_FREE_BUFFER
    // after cancellation completes. We just need to free the transfer
    // structures themselves.
    for (auto* t : s->transfers) {
        libusb_free_transfer(t);
    }
    s->transfers.clear();

    if (s->handle) libusb_close(s->handle);
    if (s->ctx)    libusb_exit(s->ctx);
    if (s->callback) env->DeleteGlobalRef(s->callback);
    delete s;
    LOGI("stopped capture");
}

// ----------------------------------------------------------------------------
// Synchronous OUTPUT write
// ----------------------------------------------------------------------------
//
// FT8TransmitSignal hands us a fully-formatted PCM byte buffer (interleaved
// little-endian int16, already at the device's native sample rate and
// channel count) plus the iso OUT endpoint info. We push it out through
// libusb iso transfers and block until the kernel has drained the entire
// buffer — same shape as the existing UsbRequest-based writeAudio() in
// Java, but via libusb so the car-dash kernel doesn't reject us.
//
// Implementation: keep kNumOutputTransfers transfers in flight, each
// carrying kPacketsPerOutputTransfer packets. Each completion callback
// refills its transfer with the next chunk and resubmits, until the input
// buffer is exhausted; the JNI thread spins libusb_handle_events_timeout_*
// until the in-flight count drops to zero.

namespace {

constexpr int kPacketsPerOutputTransfer = 8;
constexpr int kNumOutputTransfers       = 4;

struct OutputSession {
    libusb_context*       ctx        = nullptr;
    libusb_device_handle* handle     = nullptr;
    int                   endpoint   = 0;
    // Bytes the device expects per USB iso frame. Computed from the audio
    // format (sampleRate * channels * bytesPerSample / 1000 for USB FS).
    // NOT the same as the endpoint's wMaxPacketSize, which is the device's
    // upper bound — sending wMaxPacketSize per frame makes the device clock
    // out samples at ~wMaxPacketSize/realFrame samples-per-frame, i.e.
    // faster than the negotiated sample rate, shifting every audio tone up
    // by the same ratio. For FT8 that pushes the message off WSJT-X's
    // tone grid (6.25 Hz spacing → ~6.51 Hz) and decoders see noise.
    int                   bytesPerFrame = 0;
    const uint8_t*        pcm        = nullptr;
    int                   pcmLen     = 0;
    int                   pcmOffset  = 0;
    std::atomic<int>      inFlight{0};
    std::atomic<int>      firstError{0};   // non-zero = first libusb error code seen
};

// Cancel signal for an in-progress nativeWrite. Set from the main thread via
// nativeCancelWrite() when the user presses STOP; observed by the transmit
// worker thread blocked in nativeWrite's drain loop and by onOutputComplete,
// which together stop resubmitting iso transfers and cancel the in-flight ones
// so audio to the device halts (well under 100ms later). g_outputCtx lets the
// cancel JNI wake the drain loop immediately instead of waiting out its 50ms
// timeout. There is only ever one nativeWrite in flight (one TX per cycle), so
// a single global pair is sufficient.
std::atomic<bool>            g_outputCancel{false};
std::atomic<libusb_context*> g_outputCtx{nullptr};

// Live TX output gain in [0, 1], applied per-sample as PCM is copied into the
// iso transfer buffers (both the initial prime in nativeWrite and the refills
// in onOutputComplete). Updated from Java via nativeSetTxVolume() whenever the
// volume slider / hardware buttons / ALC auto-volume move, so a level change
// takes effect within the buffered-ahead window (~32ms) instead of next cycle.
// This is what lets the operator pull drive down mid-over to protect the rig.
std::atomic<float>           g_outputVolume{1.0f};

// Scale a block of interleaved little-endian int16 PCM in place by the current
// g_outputVolume. byteLen must be even (it always is: bytesPerFrame and pcmLen
// are even). At unity (1.0f) this is a no-op fast path so the common case costs
// nothing. Reads the atomic once per block — sub-ms blocks, so per-block is
// effectively per-sample for responsiveness while staying cheap.
void scalePcm16(uint8_t* buf, int byteLen) {
    float vol = g_outputVolume.load(std::memory_order_relaxed);
    if (vol >= 0.999f) return;            // unity: leave bytes untouched
    if (vol < 0.0f) vol = 0.0f;
    int samples = byteLen / 2;
    for (int i = 0; i < samples; ++i) {
        int16_t s = static_cast<int16_t>(buf[i * 2] | (buf[i * 2 + 1] << 8));
        int scaled = static_cast<int>(s * vol);
        if (scaled > 32767) scaled = 32767;
        else if (scaled < -32768) scaled = -32768;
        buf[i * 2]     = static_cast<uint8_t>(scaled & 0xFF);
        buf[i * 2 + 1] = static_cast<uint8_t>((scaled >> 8) & 0xFF);
    }
}

void LIBUSB_CALL onOutputComplete(libusb_transfer* xfer) {
    auto* s = static_cast<OutputSession*>(xfer->user_data);

    // User pressed STOP: retire this transfer instead of refilling it. Once all
    // in-flight transfers retire this way, nativeWrite's drain loop exits and
    // its cleanup cancels anything still pending.
    if (g_outputCancel.load(std::memory_order_acquire)) {
        s->inFlight.fetch_sub(1, std::memory_order_acq_rel);
        return;
    }

    if (xfer->status != LIBUSB_TRANSFER_COMPLETED) {
        int expect = 0;
        s->firstError.compare_exchange_strong(expect, xfer->status);
        // For terminal errors we let the in-flight counter drop so the
        // caller exits. Iso has best-effort delivery semantics, but a
        // NO_DEVICE / CANCELLED is unrecoverable.
        if (xfer->status == LIBUSB_TRANSFER_NO_DEVICE
                || xfer->status == LIBUSB_TRANSFER_CANCELLED) {
            s->inFlight.fetch_sub(1, std::memory_order_acq_rel);
            return;
        }
        // Other statuses (STALL, TIMED_OUT, OVERFLOW): try to keep going,
        // but only if PCM remains.
    }

    if (s->pcmOffset >= s->pcmLen) {
        // Done; let this transfer retire.
        s->inFlight.fetch_sub(1, std::memory_order_acq_rel);
        return;
    }

    int chunkBytes = kPacketsPerOutputTransfer * s->bytesPerFrame;
    int remaining  = s->pcmLen - s->pcmOffset;
    int thisChunk  = std::min(chunkBytes, remaining);

    std::memcpy(xfer->buffer, s->pcm + s->pcmOffset, thisChunk);
    if (thisChunk < chunkBytes) {
        std::memset(xfer->buffer + thisChunk, 0, chunkBytes - thisChunk);
    }
    // Apply the live TX gain to the copy (the source PCM is full-scale now;
    // Java no longer pre-scales). Padding bytes are zero so scaling them is a
    // no-op; scale the whole chunk for simplicity.
    scalePcm16(xfer->buffer, thisChunk);
    s->pcmOffset += thisChunk;

    int rc = libusb_submit_transfer(xfer);
    if (rc != 0) {
        int expect = 0;
        s->firstError.compare_exchange_strong(expect, rc);
        s->inFlight.fetch_sub(1, std::memory_order_acq_rel);
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_k1af_ft8af_wave_UsbAudioNative_nativeWrite(
        JNIEnv* env,
        jclass /*clazz*/,
        jint fd,
        jint /*interfaceNumber*/,
        jint /*altSetting*/,
        jint endpointAddress,
        jint maxPacketSize,
        jint outputSampleRate,
        jint outputChannels,
        jint outputBytesPerSample,
        jbyteArray pcmArray) {

    if (!pcmArray) return LIBUSB_ERROR_INVALID_PARAM;
    if (maxPacketSize <= 0) return LIBUSB_ERROR_INVALID_PARAM;

    // Clear any stale cancel from a prior TX so this transmission isn't
    // immediately aborted before it starts.
    g_outputCancel.store(false, std::memory_order_release);

    // USB full-speed iso = 1 packet per 1ms frame. The amount of audio data
    // per packet must equal the *negotiated* sample rate × channels × bytes,
    // not the endpoint's wMaxPacketSize. The previous code used maxPktSize
    // for both buffer sizing and packet length, which made the device clock
    // out samples at maxPktSize/realFrameBytes faster than negotiated — e.g.
    // 200/192 = 1.0417× at 48 kHz stereo 16-bit, time-stretching FT8 tones
    // by 4% and pushing them off WSJT-X's 6.25 Hz grid (decoders see noise).
    int bytesPerFrame = (outputSampleRate * outputChannels * outputBytesPerSample) / 1000;
    if (bytesPerFrame <= 0 || bytesPerFrame > maxPacketSize) {
        // Bad format args, or device's max is somehow smaller than the data
        // rate. Fall back to the old behavior; better wrong-pitch audio than
        // silent abort. Log so the dev screen surfaces it.
        LOGE("nativeWrite: bad audio format rate=%d ch=%d bps=%d maxPkt=%d, "
             "falling back to maxPkt for packet length (audio will play at "
             "wrong rate; expect undecodable FT8)",
             outputSampleRate, outputChannels, outputBytesPerSample,
             maxPacketSize);
        bytesPerFrame = maxPacketSize;
    }

    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);

    libusb_context* ctx = nullptr;
    int rc = libusb_init(&ctx);
    if (rc != 0) {
        LOGE("nativeWrite: libusb_init: %s", libusb_error_name(rc));
        return rc;
    }
    // Publish the context so nativeCancelWrite() can wake our drain loop.
    g_outputCtx.store(ctx, std::memory_order_release);

    libusb_device_handle* handle = nullptr;
    rc = libusb_wrap_sys_device(ctx, (intptr_t)fd, &handle);
    if (rc != 0 || !handle) {
        LOGE("nativeWrite: libusb_wrap_sys_device(fd=%d): %s",
             fd, libusb_error_name(rc));
        libusb_exit(ctx);
        return rc != 0 ? rc : LIBUSB_ERROR_NO_DEVICE;
    }

    jsize pcmLen = env->GetArrayLength(pcmArray);
    // Critical lock: PCM bytes are read directly into transfer buffers
    // during this call; we don't need to keep them after we return.
    jbyte* pcmBytes = env->GetByteArrayElements(pcmArray, nullptr);
    if (!pcmBytes) {
        libusb_close(handle);
        libusb_exit(ctx);
        return LIBUSB_ERROR_NO_MEM;
    }

    OutputSession s;
    s.ctx           = ctx;
    s.handle        = handle;
    s.endpoint      = endpointAddress;
    s.bytesPerFrame = bytesPerFrame;
    s.pcm           = reinterpret_cast<const uint8_t*>(pcmBytes);
    s.pcmLen        = (int)pcmLen;
    s.pcmOffset     = 0;

    libusb_transfer* xfers[kNumOutputTransfers] = {nullptr};
    int submitted = 0;

    for (int i = 0; i < kNumOutputTransfers && s.pcmOffset < s.pcmLen; ++i) {
        xfers[i] = libusb_alloc_transfer(kPacketsPerOutputTransfer);
        if (!xfers[i]) {
            int expect = 0;
            s.firstError.compare_exchange_strong(expect, LIBUSB_ERROR_NO_MEM);
            continue;
        }
        int chunkBytes = kPacketsPerOutputTransfer * s.bytesPerFrame;
        auto* buf = static_cast<uint8_t*>(std::malloc(chunkBytes));
        if (!buf) {
            libusb_free_transfer(xfers[i]);
            xfers[i] = nullptr;
            int expect = 0;
            s.firstError.compare_exchange_strong(expect, LIBUSB_ERROR_NO_MEM);
            continue;
        }

        int remaining = s.pcmLen - s.pcmOffset;
        int thisChunk = std::min(chunkBytes, remaining);
        std::memcpy(buf, s.pcm + s.pcmOffset, thisChunk);
        if (thisChunk < chunkBytes) {
            std::memset(buf + thisChunk, 0, chunkBytes - thisChunk);
        }
        scalePcm16(buf, thisChunk);   // live TX gain (source PCM is full-scale)
        s.pcmOffset += thisChunk;

        libusb_fill_iso_transfer(
                xfers[i], handle, (unsigned char)s.endpoint,
                buf, chunkBytes, kPacketsPerOutputTransfer,
                onOutputComplete, &s, /*timeout=*/0);
        libusb_set_iso_packet_lengths(xfers[i], s.bytesPerFrame);
        xfers[i]->flags = LIBUSB_TRANSFER_FREE_BUFFER;

        int rcSub = libusb_submit_transfer(xfers[i]);
        if (rcSub != 0) {
            LOGE("nativeWrite: submit i=%d: %s", i, libusb_error_name(rcSub));
            int expect = 0;
            s.firstError.compare_exchange_strong(expect, rcSub);
            libusb_free_transfer(xfers[i]);
            xfers[i] = nullptr;
            continue;
        }
        s.inFlight.fetch_add(1, std::memory_order_acq_rel);
        submitted++;
    }

    LOGI("nativeWrite: %d transfers submitted, pcmLen=%d ep=0x%02x maxPkt=%d",
         submitted, (int)pcmLen, endpointAddress, maxPacketSize);

    if (submitted == 0) {
        env->ReleaseByteArrayElements(pcmArray, pcmBytes, JNI_ABORT);
        libusb_close(handle);
        libusb_exit(ctx);
        return s.firstError.load();
    }

    // Drain. Iso transfers are paced by the device's bandwidth allocation,
    // so this naturally takes ~ pcmLen / (sampleRate * channels * 2) seconds.
    while (s.inFlight.load(std::memory_order_acquire) > 0) {
        if (g_outputCancel.load(std::memory_order_acquire)) {
            LOGI("nativeWrite: cancel requested, aborting TX");
            break;  // cleanup below cancels the still-in-flight transfers
        }
        timeval tv{0, 50000};  // 50ms
        rc = libusb_handle_events_timeout_completed(ctx, &tv, nullptr);
        if (rc != 0 && rc != LIBUSB_ERROR_INTERRUPTED) {
            LOGE("nativeWrite: handle_events: %s", libusb_error_name(rc));
            int expect = 0;
            s.firstError.compare_exchange_strong(expect, rc);
            break;
        }
    }

    // Cancel any still-pending transfers (in error paths).
    for (int i = 0; i < kNumOutputTransfers; ++i) {
        if (xfers[i]) libusb_cancel_transfer(xfers[i]);
    }
    while (s.inFlight.load(std::memory_order_acquire) > 0) {
        timeval tv{0, 50000};
        libusb_handle_events_timeout_completed(ctx, &tv, nullptr);
    }
    for (int i = 0; i < kNumOutputTransfers; ++i) {
        if (xfers[i]) libusb_free_transfer(xfers[i]);
    }

    env->ReleaseByteArrayElements(pcmArray, pcmBytes, JNI_ABORT);
    // Unpublish before exit so a late cancel can't poke a freed context.
    g_outputCtx.store(nullptr, std::memory_order_release);
    libusb_close(handle);
    libusb_exit(ctx);

    int err = s.firstError.load();
    LOGI("nativeWrite: done, firstError=%d (%s)",
         err, err == 0 ? "OK" : libusb_error_name(err));
    return err;
}

// Abort an in-progress nativeWrite as fast as possible. Safe to call from any
// thread (and a no-op if no write is running). Sets the cancel flag, then nudges
// the event loop so the drain loop notices now rather than after its 50ms tick.
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_wave_UsbAudioNative_nativeCancelWrite(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    g_outputCancel.store(true, std::memory_order_release);
    libusb_context* ctx = g_outputCtx.load(std::memory_order_acquire);
    if (ctx) {
        libusb_interrupt_event_handler(ctx);
    }
}

// Set the live TX output gain read by nativeWrite's drain loop. Clamped to
// [0, 1]. Safe to call from any thread at any time (a relaxed store); if no
// write is in flight it just sets the value the next write starts from.
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_wave_UsbAudioNative_nativeSetTxVolume(
        JNIEnv* /*env*/, jclass /*clazz*/, jfloat volume) {
    float v = volume;
    if (v < 0.0f) v = 0.0f;
    else if (v > 1.0f) v = 1.0f;
    g_outputVolume.store(v, std::memory_order_relaxed);
}
