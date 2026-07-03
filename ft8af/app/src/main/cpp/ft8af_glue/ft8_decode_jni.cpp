// FT8 decoder JNI wrappers — from-source replacement for libft8cn.so.
//
// Replaces the prebuilt's 9 decoder JNI entry points on FT8SignalListener
// plus doSubtractSignal on ReBuildSignal. Modeled directly on
// ft2_decode_jni.cpp (the FT2/FT4 from-source decoder) with adjustments
// for FT8's 8-GFSK modulation (79 symbols, kFreqHalfWidth=4).
//
// Java side:
//   com.k1af.ft8af.ft8listener.FT8SignalListener (instance methods)
//   com.k1af.ft8af.ft8listener.ReBuildSignal (static method)

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

extern "C" {
#include "ft8/decode.h"
#include "ft8/message.h"
#include "ft8/crc.h"
#include "ft8/text.h"
#include "ft8/constants.h"
#include "common/monitor.h"
// Non-static in decode.c but absent from decode.h:
int ft8_snr(const waterfall_t* wf, const candidate_t* candidate);
}

#include "decode_params.h"
#include "ft8_fine.h"
#include "ft8_subtract.h"
#include "ft8_xslot.h"
#include "ft8_call_hash.h"

// ---------------------------------------------------------------------------
// 22-bit WSJT-X callsign hash (same as ft2_decode_jni.cpp / test_golden_encode.c).
// ---------------------------------------------------------------------------
static uint32_t ft8_compute_n22(const char* call)
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
            j = 0;
        n58 = 38 * n58 + (uint64_t)j;
    }
    for (; i < 11; ++i)
        n58 = 38 * n58;
    return (uint32_t)(((47055833459ull * n58) >> (64 - 22)) & 0x3FFFFFul);
}

static const int kMaxCandidates = FT8AF_MAX_CANDIDATES;

// ---------------------------------------------------------------------------
// Per-decoder callsign hash table.
// ---------------------------------------------------------------------------
#define FT8_HASHTABLE_SIZE 256
typedef struct
{
    char callsign[12];
    uint32_t hash; // 22-bit
} ft8_hash_entry_t;

struct ft8_decoder_state
{
    monitor_t mon;
    bool mon_ready;

    float* samples;
    int num_samples;
    int num_fed;   // samples actually captured into `samples` this slot
    bool wf_dirty; // time-domain subtraction has edited `samples`; the
                   // waterfall must be recomputed before the next find_sync
    // Fine-demod context over the current `samples`, built LAZILY on the
    // first deep-pass retry that needs it (deep mode is unknown at feed
    // time, and non-deep slots must not pay its full-buffer FFT).
    // fine_tried keeps a failed init from being retried per candidate.
    ft8_fine_ctx_t* fine;
    bool fine_tried;
    int sample_rate;
    int64_t utc; // slot UTC in ms (jlong; drives cross-slot parity/aging —
                 // `long` would truncate on 32-bit ABIs)

    int ldpc_iterations;
    bool deep; // deep passes lower the sync-score threshold (FT8AF_MIN_SCORE_DEEP)

    candidate_t candidates[kMaxCandidates];
    int num_candidates;

    ftx_message_t last_message;
    bool have_last;

    // Payloads already time-subtracted this slot: the same transmission
    // decodes from several neighboring candidates and again from its own
    // residual in later passes; a second subtraction would fit the fine sync
    // to junk and pollute the residual samples.
    uint8_t sub_done[kMaxCandidates][10];
    int sub_done_count;

    ft8_hash_entry_t hashtable[FT8_HASHTABLE_SIZE];
    int hashtable_count;
};

// --- hash interface callbacks (TLS pointer to current decoder_state) --------
static __thread ft8_decoder_state* g_active = nullptr;

static void ft8_hash_save(const char* callsign, uint32_t n22)
{
    ft8_decoder_state* d = g_active;
    if (!d || callsign[0] == '\0' || callsign[0] == '<')
        return;
    uint16_t h10 = (n22 >> 12) & 0x3FF;
    int idx = (h10 * 23) % FT8_HASHTABLE_SIZE;
    while (d->hashtable[idx].callsign[0] != '\0')
    {
        if (d->hashtable[idx].hash == n22)
            return;
        idx = (idx + 1) % FT8_HASHTABLE_SIZE;
    }
    strncpy(d->hashtable[idx].callsign, callsign, 11);
    d->hashtable[idx].callsign[11] = '\0';
    d->hashtable[idx].hash = n22;
    d->hashtable_count++;
}

static bool ft8_hash_lookup(ftx_callsign_hash_type_e type, uint32_t hash, char* callsign)
{
    ft8_decoder_state* d = g_active;
    if (!d)
    {
        callsign[0] = '\0';
        return false;
    }
    uint8_t shift = (type == FTX_CALLSIGN_HASH_10_BITS) ? 12 : (type == FTX_CALLSIGN_HASH_12_BITS ? 10 : 0);
    for (int i = 0; i < FT8_HASHTABLE_SIZE; ++i)
    {
        if (d->hashtable[i].callsign[0] == '\0')
            continue;
        if (((d->hashtable[i].hash & 0x3FFFFFu) >> shift) == hash)
        {
            strcpy(callsign, d->hashtable[i].callsign);
            return true;
        }
    }
    callsign[0] = '\0';
    return false;
}

// ---------------------------------------------------------------------------
// Cached Ft8Message field IDs.
// ---------------------------------------------------------------------------
static struct
{
    bool ready;
    jfieldID isValid, snr, time_sec, freq_hz, score, messageHash, i3, n3, report;
    jfieldID callsignFrom, callsignTo, extraInfo, maidenGrid;
    jfieldID callFromHash10, callFromHash12, callFromHash22;
    jfieldID callToHash10, callToHash12, callToHash22;
    jfieldID rtty_tu, rtty_state, r_flag, eu_serial, arrl_class, arrl_rac, dx_call_to2;
} FF8;

static void ft8_cache_fields(JNIEnv* env, jobject msg)
{
    if (FF8.ready)
        return;
    jclass c = env->GetObjectClass(msg);
    FF8.isValid = env->GetFieldID(c, "isValid", "Z");
    FF8.snr = env->GetFieldID(c, "snr", "I");
    FF8.time_sec = env->GetFieldID(c, "time_sec", "F");
    FF8.freq_hz = env->GetFieldID(c, "freq_hz", "F");
    FF8.score = env->GetFieldID(c, "score", "I");
    FF8.messageHash = env->GetFieldID(c, "messageHash", "I");
    FF8.i3 = env->GetFieldID(c, "i3", "I");
    FF8.n3 = env->GetFieldID(c, "n3", "I");
    FF8.report = env->GetFieldID(c, "report", "I");
    FF8.callsignFrom = env->GetFieldID(c, "callsignFrom", "Ljava/lang/String;");
    FF8.callsignTo = env->GetFieldID(c, "callsignTo", "Ljava/lang/String;");
    FF8.extraInfo = env->GetFieldID(c, "extraInfo", "Ljava/lang/String;");
    FF8.maidenGrid = env->GetFieldID(c, "maidenGrid", "Ljava/lang/String;");
    FF8.callFromHash10 = env->GetFieldID(c, "callFromHash10", "J");
    FF8.callFromHash12 = env->GetFieldID(c, "callFromHash12", "J");
    FF8.callFromHash22 = env->GetFieldID(c, "callFromHash22", "J");
    FF8.callToHash10 = env->GetFieldID(c, "callToHash10", "J");
    FF8.callToHash12 = env->GetFieldID(c, "callToHash12", "J");
    FF8.callToHash22 = env->GetFieldID(c, "callToHash22", "J");
    FF8.rtty_tu = env->GetFieldID(c, "rtty_tu", "I");
    FF8.rtty_state = env->GetFieldID(c, "rtty_state", "Ljava/lang/String;");
    FF8.r_flag = env->GetFieldID(c, "r_flag", "I");
    FF8.eu_serial = env->GetFieldID(c, "eu_serial", "I");
    FF8.arrl_class = env->GetFieldID(c, "arrl_class", "Ljava/lang/String;");
    FF8.arrl_rac = env->GetFieldID(c, "arrl_rac", "Ljava/lang/String;");
    FF8.dx_call_to2 = env->GetFieldID(c, "dx_call_to2", "Ljava/lang/String;");
    FF8.ready = true;
}

static void ft8_set_string(JNIEnv* env, jobject msg, jfieldID fid, const char* s)
{
    jstring js = env->NewStringUTF(s ? s : "");
    env->SetObjectField(msg, fid, js);
    env->DeleteLocalRef(js);
}

static bool ft8_looks_like_grid(const char* s)
{
    return s && strlen(s) == 4 &&
           s[0] >= 'A' && s[0] <= 'R' && s[1] >= 'A' && s[1] <= 'R' &&
           s[2] >= '0' && s[2] <= '9' && s[3] >= '0' && s[3] <= '9';
}

static void ft8_set_call_hashes(JNIEnv* env, jobject msg, const char* call,
                                jfieldID f10, jfieldID f12, jfieldID f22)
{
    if (call && call[0] != '\0' && call[0] != '<')
    {
        uint32_t n22 = ft8_compute_n22(call);
        env->SetLongField(msg, f22, (jlong)n22);
        env->SetLongField(msg, f12, (jlong)(n22 >> 10));
        env->SetLongField(msg, f10, (jlong)(n22 >> 12));
    }
    else
    {
        env->SetLongField(msg, f22, 0);
        env->SetLongField(msg, f12, 0);
        env->SetLongField(msg, f10, 0);
    }
}

// ---------------------------------------------------------------------------
// InitDecoder: allocate decoder state and initialize monitor.
// Java: public native long InitDecoder(long utcTime, int sampleRate,
//                                      int num_samples, boolean isFt8);
//
// Note: The isFt8 parameter controls FT8 vs FT4. When isFt8==true, uses
// FTX_PROTOCOL_FT8; when false, uses FTX_PROTOCOL_FT4. However, FT4 is now
// handled by the from-source ft2_decode_jni.cpp (InitDecoderFt2), so in
// practice isFt8 is always true when this entry point is reached.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_k1af_ft8af_ft8listener_FT8SignalListener_InitDecoder(
        JNIEnv*, jobject, jlong utcTime, jint sampleRate, jint num_samples, jboolean isFt8)
{
    ft8_decoder_state* d = (ft8_decoder_state*)calloc(1, sizeof(ft8_decoder_state));
    if (!d)
        return 0;
    d->sample_rate = sampleRate;
    d->num_samples = num_samples;
    d->utc = utcTime;
    d->ldpc_iterations = FT8AF_LDPC_ITERS_FAST;
    d->deep = false;

    ftx_protocol_t proto = isFt8 ? FTX_PROTOCOL_FT8 : FTX_PROTOCOL_FT4;

    monitor_config_t cfg;
    cfg.f_min = 100;
    cfg.f_max = 3500;
    cfg.sample_rate = sampleRate;
    cfg.time_osr = FT8AF_TIME_OSR;
    cfg.freq_osr = FT8AF_FREQ_OSR;
    cfg.protocol = proto;
    monitor_init(&d->mon, &cfg);
    d->mon_ready = true;

    d->samples = (float*)calloc(num_samples > 0 ? num_samples : 1, sizeof(float));
    return (jlong)(intptr_t)d;
}

// ---------------------------------------------------------------------------
// DeleteDecoder: free decoder state.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ft8listener_FT8SignalListener_DeleteDecoder(
        JNIEnv*, jobject, jlong handle)
{
    ft8_decoder_state* d = (ft8_decoder_state*)(intptr_t)handle;
    if (!d)
        return;
    if (d->mon_ready)
        monitor_free(&d->mon);
    ft8_fine_free(d->fine);
    free(d->samples);
    free(d);
}

// Feed the whole slot through the monitor and keep a copy for subtraction.
static void ft8_feed(ft8_decoder_state* d, const float* data, int n)
{
    if (!d || !d->mon_ready)
        return;
    d->num_fed = 0;
    d->wf_dirty = false;
    d->sub_done_count = 0;
    if (d->samples && n <= d->num_samples)
    {
        memcpy(d->samples, data, sizeof(float) * n);
        d->num_fed = n;
    }
    monitor_reset(&d->mon);
    for (int pos = 0; pos + d->mon.block_size <= n; pos += d->mon.block_size)
        monitor_process(&d->mon, data + pos);

    // Invalidate only: the context is rebuilt lazily when a deep pass
    // actually needs it (see DecoderFt8Analysis).
    ft8_fine_free(d->fine);
    d->fine = NULL;
    d->fine_tried = false;
}

// ---------------------------------------------------------------------------
// DecoderMonitorPress: feed int16 audio samples (as int[]).
// Java: public native void DecoderMonitorPress(int[] buffer, long decoder);
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ft8listener_FT8SignalListener_DecoderMonitorPress(
        JNIEnv* env, jobject, jintArray buffer, jlong decoder)
{
    ft8_decoder_state* d = (ft8_decoder_state*)(intptr_t)decoder;
    if (!d)
        return;
    jsize n = env->GetArrayLength(buffer);
    jint* data = env->GetIntArrayElements(buffer, nullptr);
    if (!data)
        return;

    // Convert int16 samples to float
    float* fbuf = (float*)malloc(n * sizeof(float));
    if (fbuf) {
        for (jsize i = 0; i < n; ++i)
            fbuf[i] = (float)data[i] / 32768.0f;
        ft8_feed(d, fbuf, n);
        free(fbuf);
    }
    env->ReleaseIntArrayElements(buffer, data, JNI_ABORT);
}

// ---------------------------------------------------------------------------
// DecoderMonitorPressFloat: feed float audio samples.
// Java: public native void DecoderMonitorPressFloat(float[] buffer, long decoder);
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ft8listener_FT8SignalListener_DecoderMonitorPressFloat(
        JNIEnv* env, jobject, jfloatArray buffer, jlong decoder)
{
    ft8_decoder_state* d = (ft8_decoder_state*)(intptr_t)decoder;
    if (!d)
        return;
    jsize n = env->GetArrayLength(buffer);
    jfloat* data = env->GetFloatArrayElements(buffer, nullptr);
    ft8_feed(d, data, n);
    env->ReleaseFloatArrayElements(buffer, data, JNI_ABORT);
}

// ---------------------------------------------------------------------------
// setDecodeMode: set LDPC iterations (20 normal, 30 deep).
// Java: public native void setDecodeMode(long decoder, boolean isDeep);
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ft8listener_FT8SignalListener_setDecodeMode(
        JNIEnv*, jobject, jlong handle, jboolean isDeep)
{
    ft8_decoder_state* d = (ft8_decoder_state*)(intptr_t)handle;
    if (!d)
        return;
    d->deep = isDeep;
    d->ldpc_iterations = isDeep ? FT8AF_LDPC_ITERS_DEEP : FT8AF_LDPC_ITERS_FAST;
}

// ---------------------------------------------------------------------------
// DecoderFt8FindSync: find candidate signals.
// Java: public native int DecoderFt8FindSync(long decoder);
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_k1af_ft8af_ft8listener_FT8SignalListener_DecoderFt8FindSync(
        JNIEnv*, jobject, jlong handle)
{
    ft8_decoder_state* d = (ft8_decoder_state*)(intptr_t)handle;
    if (!d || !d->mon_ready)
        return 0;
    if (d->wf_dirty && d->samples && d->num_fed > 0)
    {
        // Time-domain subtraction edited the retained samples; recompute the
        // waterfall from the residual so this pass can uncover what was
        // underneath the removed signals. The fine-demod context is only
        // invalidated here — it is rebuilt lazily on first use so modes
        // that never take the fine path don't pay its full-buffer FFT.
        monitor_reset(&d->mon);
        for (int pos = 0; pos + d->mon.block_size <= d->num_fed; pos += d->mon.block_size)
            monitor_process(&d->mon, d->samples + pos);
        ft8_fine_free(d->fine);
        d->fine = NULL;
        d->fine_tried = false;
        d->wf_dirty = false;
    }
    int min_score = d->deep ? FT8AF_MIN_SCORE_DEEP : FT8AF_MIN_SCORE_FAST;
    d->num_candidates = ft8_find_sync(&d->mon.wf, kMaxCandidates, d->candidates, min_score);
    return d->num_candidates;
}

// ---------------------------------------------------------------------------
// DecoderFt8Analysis: decode a candidate and populate the Ft8Message Java object.
// Java: public native boolean DecoderFt8Analysis(int idx, long decoder, Ft8Message ft8Message);
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_k1af_ft8af_ft8listener_FT8SignalListener_DecoderFt8Analysis(
        JNIEnv* env, jobject, jint idx, jlong handle, jobject ft8Message)
{
    ft8_decoder_state* d = (ft8_decoder_state*)(intptr_t)handle;
    if (!d || !d->mon_ready || idx < 0 || idx >= d->num_candidates)
        return JNI_FALSE;

    ft8_cache_fields(env, ft8Message);
    const candidate_t* cand = &d->candidates[idx];

    // Geometry + signal metrics
    float freq_hz = (d->mon.min_bin + cand->freq_offset +
                     (float)cand->freq_sub / d->mon.wf.freq_osr) / d->mon.symbol_period;
    float time_sec = (cand->time_offset +
                      (float)cand->time_sub / d->mon.wf.time_osr) * d->mon.symbol_period;

    ftx_message_t message;
    decode_status_t status;
    int osd_depth = d->deep ? FT8AF_OSD_DEPTH_DEEP : 0; // OSD only in deep passes
    bool decoded = ft8_decode_osd(&d->mon.wf, cand, d->ldpc_iterations, osd_depth,
                                  FT8AF_OSD_LDPC_ERR_GATE, &message, &status);
    if (!decoded && osd_depth > 0 && d->samples && d->num_fed > 0 &&
        d->mon.wf.protocol == FTX_PROTOCOL_FT8)
    {
        // Second chance for deep passes: re-demodulate from the raw samples
        // with fine dt/df sync (ft8_fine.h) and rerun BP+OSD+CRC on the
        // float LLRs. The context (one full-buffer FFT) is built lazily on
        // the first candidate that gets here, so fast-only or FT4 slots
        // never pay for it. Junk guards mirror decode_bench.c: a
        // sync-quality floor, raised for the junk-prone rare message types.
        // On success the refined freq/time replace the grid values — they
        // also flow into a91List and make the subsequent subtraction more
        // accurate.
        if (!d->fine && !d->fine_tried)
        {
            d->fine = ft8_fine_init(d->samples, d->num_fed, d->sample_rate);
            d->fine_tried = true;
        }
        float llrs[FTX_LDPC_N];
        float f_fine, t_fine, quality;
        if (ft8_fine_demod(d->fine, &d->mon, cand, llrs, &f_fine, &t_fine, &quality) &&
            quality >= FT8AF_FINE_SYNC_MIN)
        {
            if (ftx_decode_llrs(FTX_PROTOCOL_FT8, llrs, d->ldpc_iterations, osd_depth,
                                FT8AF_OSD_LDPC_ERR_GATE, &message, &status))
            {
                ftx_message_type_t mtype = ftx_message_get_type(&message);
                if (mtype == FTX_MESSAGE_TYPE_STANDARD ||
                    mtype == FTX_MESSAGE_TYPE_NONSTD_CALL ||
                    quality >= FT8AF_FINE_SYNC_MIN_RARE)
                    decoded = true;
            }
            if (!decoded)
            {
                // Cross-slot mechanism B (ft8_xslot.h): a recently decoded
                // message at this frequency and slot parity, repeated but
                // now below single-slot threshold.
                memset(&status, 0, sizeof(status));
                decoded = ft8_xslot_try_history(f_fine, d->utc, llrs, &message, NULL);
            }
            if (!decoded &&
                ft8_xslot_try_accumulate(f_fine, d->utc, llrs, d->ldpc_iterations,
                                         osd_depth, FT8AF_OSD_LDPC_ERR_GATE,
                                         &message, &status))
            {
                // Cross-slot mechanism A: this repeat's LLRs combined with
                // the previous same-parity repeat's. Fresh CRC decode —
                // same rare-type bar as the fine path.
                ftx_message_type_t mtype = ftx_message_get_type(&message);
                if (mtype == FTX_MESSAGE_TYPE_STANDARD ||
                    mtype == FTX_MESSAGE_TYPE_NONSTD_CALL ||
                    quality >= FT8AF_FINE_SYNC_MIN_RARE)
                    decoded = true;
            }
            if (decoded)
            {
                freq_hz = f_fine;
                time_sec = t_fine;
            }
            else
            {
                // Keep this repeat's information for the next one.
                ft8_xslot_store(f_fine, d->utc, llrs);
            }
        }
    }
    if (!decoded)
        return JNI_FALSE;

    if (d->mon.wf.protocol == FTX_PROTOCOL_FT8)
        ft8_xslot_remember(message.payload, freq_hz, d->utc);

    d->last_message = message;
    d->have_last = true;
    g_active = d;
    int snr = ft8_snr(&d->mon.wf, cand);

    env->SetFloatField(ft8Message, FF8.freq_hz, freq_hz);
    env->SetFloatField(ft8Message, FF8.time_sec, time_sec);
    env->SetIntField(ft8Message, FF8.snr, snr);
    env->SetIntField(ft8Message, FF8.score, cand->score);
    env->SetIntField(ft8Message, FF8.messageHash, (jint)message.hash);
    env->SetIntField(ft8Message, FF8.report, -100);

    ftx_callsign_hash_interface_t hash_if = { ft8_hash_lookup, ft8_hash_save };
    uint8_t i3 = ftx_message_get_i3(&message);
    uint8_t n3 = ftx_message_get_n3(&message);
    ftx_message_type_t type = ftx_message_get_type(&message);
    env->SetIntField(ft8Message, FF8.i3, i3);
    env->SetIntField(ft8Message, FF8.n3, n3);

    // Reset contest-only fields to defaults.
    env->SetIntField(ft8Message, FF8.rtty_tu, 0);
    env->SetIntField(ft8Message, FF8.r_flag, 0);
    env->SetIntField(ft8Message, FF8.eu_serial, 0);
    ft8_set_string(env, ft8Message, FF8.rtty_state, "");
    ft8_set_string(env, ft8Message, FF8.arrl_class, "");
    ft8_set_string(env, ft8Message, FF8.arrl_rac, "");
    ft8_set_string(env, ft8Message, FF8.dx_call_to2, "");
    ft8_set_string(env, ft8Message, FF8.maidenGrid, "");

    char call_to[20] = {0}, call_de[20] = {0}, extra[20] = {0};
    bool ok = false;

    if (type == FTX_MESSAGE_TYPE_FREE_TEXT)
    {
        char text[FTX_MAX_MESSAGE_LENGTH] = {0};
        ftx_message_decode_free(&message, text);
        ft8_set_string(env, ft8Message, FF8.callsignTo, "");
        ft8_set_string(env, ft8Message, FF8.callsignFrom, "");
        ft8_set_string(env, ft8Message, FF8.extraInfo, text);
        ft8_set_call_hashes(env, ft8Message, "", FF8.callToHash10, FF8.callToHash12, FF8.callToHash22);
        ft8_set_call_hashes(env, ft8Message, "", FF8.callFromHash10, FF8.callFromHash12, FF8.callFromHash22);
        ok = true;
    }
    else if (type == FTX_MESSAGE_TYPE_STANDARD)
    {
        ok = (ftx_message_decode_std(&message, &hash_if, call_to, call_de, extra) == FTX_MESSAGE_RC_OK);
    }
    else if (type == FTX_MESSAGE_TYPE_NONSTD_CALL)
    {
        ok = (ftx_message_decode_nonstd(&message, &hash_if, call_to, call_de, extra) == FTX_MESSAGE_RC_OK);
    }
    else if (type == FTX_MESSAGE_TYPE_ARRL_FD)
    {
        uint8_t r_flag_val = 0, num_tx_val = 0;
        char fd_class[2] = {0}, fd_section[4] = {0};
        if (ftx_message_decode_fd(&message, &hash_if, call_to, call_de,
                                  &r_flag_val, &num_tx_val, fd_class, fd_section) == FTX_MESSAGE_RC_OK)
        {
            ft8_set_string(env, ft8Message, FF8.callsignTo, call_to);
            ft8_set_string(env, ft8Message, FF8.callsignFrom, call_de);
            ft8_set_call_hashes(env, ft8Message, call_to, FF8.callToHash10, FF8.callToHash12, FF8.callToHash22);
            ft8_set_call_hashes(env, ft8Message, call_de, FF8.callFromHash10, FF8.callFromHash12, FF8.callFromHash22);

            env->SetIntField(ft8Message, FF8.r_flag, r_flag_val);
            env->SetIntField(ft8Message, FF8.eu_serial, num_tx_val);
            ft8_set_string(env, ft8Message, FF8.arrl_class, fd_class);
            ft8_set_string(env, ft8Message, FF8.arrl_rac, fd_section);

            // Build exchange text for extraInfo display fallback
            char exchange[20] = {0};
            char* dst = exchange;
            if (r_flag_val)
                dst = stpcpy(dst, "R ");
            if (num_tx_val >= 10)
                *dst++ = '0' + (num_tx_val / 10);
            *dst++ = '0' + (num_tx_val % 10);
            *dst++ = fd_class[0];
            *dst++ = ' ';
            stpcpy(dst, fd_section);
            ft8_set_string(env, ft8Message, FF8.extraInfo, exchange);

            ok = true;
        }
    }
    else if (type == FTX_MESSAGE_TYPE_DXPEDITION)
    {
        uint16_t h10_val = 0;
        int8_t rpt_val = 0;
        if (ftx_message_decode_dxped(&message, &hash_if, call_to, call_de,
                                     &h10_val, &rpt_val) == FTX_MESSAGE_RC_OK)
        {
            ft8_set_string(env, ft8Message, FF8.callsignTo, call_to);
            // call_de is the invited callsign; put it in dx_call_to2
            ft8_set_string(env, ft8Message, FF8.dx_call_to2, call_de);
            ft8_set_string(env, ft8Message, FF8.callsignFrom, "");
            ft8_set_call_hashes(env, ft8Message, call_to, FF8.callToHash10, FF8.callToHash12, FF8.callToHash22);
            ft8_set_call_hashes(env, ft8Message, call_de, FF8.callFromHash10, FF8.callFromHash12, FF8.callFromHash22);
            // Store the fox 10-bit hash so Java can look it up
            env->SetLongField(ft8Message, FF8.callFromHash10, (jlong)h10_val);
            env->SetIntField(ft8Message, FF8.report, (jint)rpt_val);

            // Build extraInfo for display fallback
            char fox_call[14] = {0};
            ft8_hash_lookup(FTX_CALLSIGN_HASH_10_BITS, h10_val, fox_call);
            char exchange[48] = {0};
            char* dst = exchange;
            dst = stpcpy(dst, "RR73; ");
            dst = stpcpy(dst, call_de);
            *dst++ = ' ';
            dst = stpcpy(dst, fox_call);
            *dst++ = ' ';
            if (rpt_val >= 0)
            {
                *dst++ = '+';
                int_to_dd(dst, rpt_val, 2, false);
            }
            else
            {
                int_to_dd(dst, rpt_val, 2, true);
            }
            ft8_set_string(env, ft8Message, FF8.extraInfo, exchange);
            ok = true;
        }
    }
    else if (type == FTX_MESSAGE_TYPE_ARRL_RTTY)
    {
        uint8_t tu_val = 0, r_flag_val = 0;
        uint16_t rpt_val = 0;
        char state_str[8] = {0};
        bool is_serial_val = false;
        if (ftx_message_decode_rtty(&message, &hash_if, call_to, call_de,
                                    &tu_val, &r_flag_val, &rpt_val,
                                    state_str, &is_serial_val) == FTX_MESSAGE_RC_OK)
        {
            ft8_set_string(env, ft8Message, FF8.callsignTo, call_to);
            ft8_set_string(env, ft8Message, FF8.callsignFrom, call_de);
            ft8_set_call_hashes(env, ft8Message, call_to, FF8.callToHash10, FF8.callToHash12, FF8.callToHash22);
            ft8_set_call_hashes(env, ft8Message, call_de, FF8.callFromHash10, FF8.callFromHash12, FF8.callFromHash22);
            env->SetIntField(ft8Message, FF8.rtty_tu, (jint)tu_val);
            env->SetIntField(ft8Message, FF8.r_flag, (jint)r_flag_val);
            env->SetIntField(ft8Message, FF8.report, (jint)rpt_val);
            ft8_set_string(env, ft8Message, FF8.rtty_state, state_str);

            // Build extraInfo for display fallback
            char exchange[40] = {0};
            char* dst = exchange;
            if (tu_val)
                dst = stpcpy(dst, "TU; ");
            if (r_flag_val)
                dst = stpcpy(dst, "R ");
            int_to_dd(dst, rpt_val, 3, false);
            dst += strlen(dst);
            *dst++ = ' ';
            stpcpy(dst, state_str);
            ft8_set_string(env, ft8Message, FF8.extraInfo, exchange);
            ok = true;
        }
    }
    else if (type == FTX_MESSAGE_TYPE_WWROF)
    {
        uint8_t tu_val = 0, r_flag_val = 0;
        int8_t rpt_val = 0;
        char grid2[4] = {0};
        if (ftx_message_decode_wwrof(&message, &hash_if, call_to, call_de,
                                     &tu_val, &r_flag_val, &rpt_val, grid2) == FTX_MESSAGE_RC_OK)
        {
            ft8_set_string(env, ft8Message, FF8.callsignTo, call_to);
            ft8_set_string(env, ft8Message, FF8.callsignFrom, call_de);
            ft8_set_call_hashes(env, ft8Message, call_to, FF8.callToHash10, FF8.callToHash12, FF8.callToHash22);
            ft8_set_call_hashes(env, ft8Message, call_de, FF8.callFromHash10, FF8.callFromHash12, FF8.callFromHash22);
            env->SetIntField(ft8Message, FF8.rtty_tu, (jint)tu_val);
            env->SetIntField(ft8Message, FF8.r_flag, (jint)r_flag_val);
            env->SetIntField(ft8Message, FF8.report, (jint)rpt_val);
            ft8_set_string(env, ft8Message, FF8.maidenGrid, grid2);
            env->SetIntField(ft8Message, FF8.eu_serial, 0);

            // Build extraInfo for display fallback
            char exchange[32] = {0};
            char* dst = exchange;
            if (tu_val)
                dst = stpcpy(dst, "TU; ");
            if (r_flag_val)
                *dst++ = 'R';
            if (rpt_val >= 0)
            {
                *dst++ = '+';
                int_to_dd(dst, rpt_val, 2, false);
            }
            else
            {
                int_to_dd(dst, rpt_val, 2, true);
            }
            dst += strlen(dst);
            *dst++ = ' ';
            stpcpy(dst, grid2);
            ft8_set_string(env, ft8Message, FF8.extraInfo, exchange);
            ok = true;
        }
    }
    else
    {
        // Contest sub-types (EU VHF 0.2, Contesting 0.6): decode to full text into extraInfo.
        char text[FTX_MAX_MESSAGE_LENGTH] = {0};
        if (ftx_message_decode(&message, &hash_if, text) == FTX_MESSAGE_RC_OK)
        {
            env->SetIntField(ft8Message, FF8.i3, 0);
            env->SetIntField(ft8Message, FF8.n3, 0);
            ft8_set_string(env, ft8Message, FF8.callsignTo, "");
            ft8_set_string(env, ft8Message, FF8.callsignFrom, "");
            ft8_set_string(env, ft8Message, FF8.extraInfo, text);
            ft8_set_call_hashes(env, ft8Message, "", FF8.callToHash10, FF8.callToHash12, FF8.callToHash22);
            ft8_set_call_hashes(env, ft8Message, "", FF8.callFromHash10, FF8.callFromHash12, FF8.callFromHash22);
            ok = true;
        }
    }

    if (ok && (type == FTX_MESSAGE_TYPE_STANDARD || type == FTX_MESSAGE_TYPE_NONSTD_CALL))
    {
        ft8_set_string(env, ft8Message, FF8.callsignTo, call_to);
        ft8_set_string(env, ft8Message, FF8.callsignFrom, call_de);
        ft8_set_string(env, ft8Message, FF8.extraInfo, extra);
        if (ft8_looks_like_grid(extra))
            ft8_set_string(env, ft8Message, FF8.maidenGrid, extra);
        ft8_set_call_hashes(env, ft8Message, call_to, FF8.callToHash10, FF8.callToHash12, FF8.callToHash22);
        ft8_set_call_hashes(env, ft8Message, call_de, FF8.callFromHash10, FF8.callFromHash12, FF8.callFromHash22);

        // A hashed compound call (e.g. answers to SV8/DM5HF) that the decoder's
        // own hash table couldn't resolve comes back as "<...>", and the call
        // above zeroed its hash fields. Recover the raw hash straight from the
        // frame so Java's seeded MessageHashMap can resolve it (issue #392).
        if (type == FTX_MESSAGE_TYPE_NONSTD_CALL)
        {
            uint32_t n12 = 0;
            bool is_to = false;
            if (ft8af_nonstd_hash12(&message, &n12, &is_to))
                env->SetLongField(ft8Message,
                                  is_to ? FF8.callToHash12 : FF8.callFromHash12,
                                  (jlong)n12);
        }
        else // FTX_MESSAGE_TYPE_STANDARD
        {
            uint32_t n22 = 0;
            if (call_to[0] == '<' && ft8af_std_hash22(&message, 0, &n22))
                env->SetLongField(ft8Message, FF8.callToHash22, (jlong)n22);
            if (call_de[0] == '<' && ft8af_std_hash22(&message, 1, &n22))
                env->SetLongField(ft8Message, FF8.callFromHash22, (jlong)n22);
        }
    }

    env->SetBooleanField(ft8Message, FF8.isValid, ok ? JNI_TRUE : JNI_FALSE);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// DecoderFt8Reset: reset the monitor for another decode pass.
// Java: public native void DecoderFt8Reset(long decoder, long utcTime, int num_samples);
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ft8listener_FT8SignalListener_DecoderFt8Reset(
        JNIEnv*, jobject, jlong handle, jlong utcTime, jint num_samples)
{
    ft8_decoder_state* d = (ft8_decoder_state*)(intptr_t)handle;
    if (!d || !d->mon_ready)
        return;
    d->utc = utcTime;
    d->num_samples = num_samples;
    d->num_fed = 0;
    d->wf_dirty = false;
    d->sub_done_count = 0;
    ft8_fine_free(d->fine);
    d->fine = NULL;
    d->fine_tried = false;
    monitor_reset(&d->mon);
}

// ---------------------------------------------------------------------------
// DecoderGetA91: return the 12-byte a91 of the last decoded message.
// Java: public native byte[] DecoderGetA91(long decoder);
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_k1af_ft8af_ft8listener_FT8SignalListener_DecoderGetA91(
        JNIEnv* env, jobject, jlong handle)
{
    ft8_decoder_state* d = (ft8_decoder_state*)(intptr_t)handle;
    jbyteArray result = env->NewByteArray(FTX_LDPC_K_BYTES);
    if (!d || !d->have_last)
        return result;
    uint8_t a91[FTX_LDPC_K_BYTES];
    ftx_add_crc(d->last_message.payload, a91);
    env->SetByteArrayRegion(result, 0, FTX_LDPC_K_BYTES, reinterpret_cast<const jbyte*>(a91));
    return result;
}

// ---------------------------------------------------------------------------
// doSubtractSignal — deep-decode subtraction for FT8. Preferred path:
// coherent time-domain subtraction from the retained float samples
// (ft8_subtract.c, WSJT-X subtractft8 approach) — removes the decoded
// signal's full spectral footprint so even a co-channel signal a few Hz away
// becomes decodable once DecoderFt8FindSync recomputes the waterfall from
// the residual. Falls back to the waterfall-domain tone replacement when no
// sample copy is available. Each payload is subtracted at most once per
// slot: re-subtracting a residual fits junk and pollutes the samples.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ft8listener_ReBuildSignal_doSubtractSignal(
        JNIEnv* env, jclass, jlong handle, jbyteArray payload, jint /*sample_rate*/,
        jfloat frequency, jfloat time_sec)
{
    ft8_decoder_state* d = (ft8_decoder_state*)(intptr_t)handle;
    if (!d || !d->mon_ready)
        return;
    if (!payload || env->GetArrayLength(payload) < 10)
        return; // need the 77-bit payload to re-encode the tone sequence

    jbyte a91[FTX_LDPC_K_BYTES] = { 0 };
    jsize n = env->GetArrayLength(payload);
    if (n > FTX_LDPC_K_BYTES)
        n = FTX_LDPC_K_BYTES;
    env->GetByteArrayRegion(payload, 0, n, a91);

    if (d->samples && d->num_fed > 0)
    {
        for (int i = 0; i < d->sub_done_count; ++i)
            if (memcmp(d->sub_done[i], a91, sizeof(d->sub_done[0])) == 0)
                return; // already subtracted this transmission this slot

        if (ft8_subtract_signal_time(&d->mon, d->samples, d->num_fed,
                                     d->sample_rate, (const uint8_t*)a91,
                                     frequency, time_sec))
        {
            d->wf_dirty = true;
            if (d->sub_done_count < kMaxCandidates)
                memcpy(d->sub_done[d->sub_done_count++], a91, sizeof(d->sub_done[0]));
            return;
        }
    }

    ft8_subtract_signal(&d->mon, (const uint8_t*)a91, frequency, time_sec);
}
