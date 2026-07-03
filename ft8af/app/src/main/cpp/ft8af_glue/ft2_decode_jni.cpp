// FT2 receive: from-source decoder JNI bridge.
//
// The shipping libft8cn.so (upstream prebuilt) decodes FT8/FT4 only — its protocol
// enum has no FT2. FT2 is structurally FT4 at double the baud (0.024s symbol period),
// so we decode it with the in-tree kgoba ft8_lib (pinned at 6f528128, the same commit
// the prebuilt was built from), compiled into libft8af_usb.so (which IS built by CMake)
// with FT2 added to the protocol switch (constants.h / monitor.c / decode.c).
//
// This file is a focused adaptation of ft8af_glue/ft8_decoder.cpp (the full reconstruction
// of the prebuilt's decode wrapper) with: the ftx protocol passed in from Java
// (InitDecoderFt2 takes a `protocol` arg — FTX_PROTOCOL_FT2 or FTX_PROTOCOL_FT4); distinct
// JNI entry-point names (FT8SignalListener.*Ft2 / ReBuildSignal.doSubtractSignalFt2) so it
// never collides with the prebuilt's exported symbols; and a 4-GFSK deep-subtract geometry
// (105 symbols) shared by FT2 and FT4. FT2 *and* FT4 now reach this code (FT4 moved here for
// a corrected SNR; see ft8_snr in decode.c); only FT8 keeps using the prebuilt unchanged.
// All internal symbols are static / hidden so the two DSP copies in the process don't
// interpose each other (see CMakeLists -fvisibility=hidden).

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

#include "ft8_call_hash.h"

// ---------------------------------------------------------------------------
// 22-bit WSJT-X callsign hash (copy of jni_ft8af.cpp's compute_n22, kept local so
// this wrapper doesn't depend on the prebuilt's translation unit). Matches
// save_callsign() in ft8_lib message.c.
// ---------------------------------------------------------------------------
static uint32_t ft2_compute_n22(const char* call)
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

static const int kMaxCandidates = 140;
static const int kMinScore = 10;

// ---------------------------------------------------------------------------
// Per-decoder callsign hash table (mirrors ft8_decoder.cpp).
// ---------------------------------------------------------------------------
#define FT2_HASHTABLE_SIZE 256
typedef struct
{
    char callsign[12];
    uint32_t hash; // 22-bit
} ft2_hash_entry_t;

struct ft2_decoder_state
{
    monitor_t mon;
    bool mon_ready;

    float* samples;     // copy of the slot audio (for subtraction in deep mode)
    int num_samples;
    int sample_rate;
    long utc;

    int ldpc_iterations; // set by setDecodeMode (deep => more)

    candidate_t candidates[kMaxCandidates];
    int num_candidates;

    ftx_message_t last_message; // populated by the most recent successful Analysis
    bool have_last;

    ft2_hash_entry_t hashtable[FT2_HASHTABLE_SIZE];
    int hashtable_count;
};

// --- hash interface callbacks (operate on the current decoder_state) --------
static __thread ft2_decoder_state* g_active = nullptr;

static void ft2_hash_save(const char* callsign, uint32_t n22)
{
    ft2_decoder_state* d = g_active;
    if (!d || callsign[0] == '\0' || callsign[0] == '<')
        return;
    uint16_t h10 = (n22 >> 12) & 0x3FF;
    int idx = (h10 * 23) % FT2_HASHTABLE_SIZE;
    while (d->hashtable[idx].callsign[0] != '\0')
    {
        if (d->hashtable[idx].hash == n22)
            return; // already stored
        idx = (idx + 1) % FT2_HASHTABLE_SIZE;
    }
    strncpy(d->hashtable[idx].callsign, callsign, 11);
    d->hashtable[idx].callsign[11] = '\0';
    d->hashtable[idx].hash = n22;
    d->hashtable_count++;
}

static bool ft2_hash_lookup(ftx_callsign_hash_type_e type, uint32_t hash, char* callsign)
{
    ft2_decoder_state* d = g_active;
    if (!d)
    {
        callsign[0] = '\0';
        return false;
    }
    uint8_t shift = (type == FTX_CALLSIGN_HASH_10_BITS) ? 12 : (type == FTX_CALLSIGN_HASH_12_BITS ? 10 : 0);
    for (int i = 0; i < FT2_HASHTABLE_SIZE; ++i)
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
} FF;

static void ft2_cache_fields(JNIEnv* env, jobject msg)
{
    if (FF.ready)
        return;
    jclass c = env->GetObjectClass(msg);
    FF.isValid = env->GetFieldID(c, "isValid", "Z");
    FF.snr = env->GetFieldID(c, "snr", "I");
    FF.time_sec = env->GetFieldID(c, "time_sec", "F");
    FF.freq_hz = env->GetFieldID(c, "freq_hz", "F");
    FF.score = env->GetFieldID(c, "score", "I");
    FF.messageHash = env->GetFieldID(c, "messageHash", "I");
    FF.i3 = env->GetFieldID(c, "i3", "I");
    FF.n3 = env->GetFieldID(c, "n3", "I");
    FF.report = env->GetFieldID(c, "report", "I");
    FF.callsignFrom = env->GetFieldID(c, "callsignFrom", "Ljava/lang/String;");
    FF.callsignTo = env->GetFieldID(c, "callsignTo", "Ljava/lang/String;");
    FF.extraInfo = env->GetFieldID(c, "extraInfo", "Ljava/lang/String;");
    FF.maidenGrid = env->GetFieldID(c, "maidenGrid", "Ljava/lang/String;");
    FF.callFromHash10 = env->GetFieldID(c, "callFromHash10", "J");
    FF.callFromHash12 = env->GetFieldID(c, "callFromHash12", "J");
    FF.callFromHash22 = env->GetFieldID(c, "callFromHash22", "J");
    FF.callToHash10 = env->GetFieldID(c, "callToHash10", "J");
    FF.callToHash12 = env->GetFieldID(c, "callToHash12", "J");
    FF.callToHash22 = env->GetFieldID(c, "callToHash22", "J");
    FF.rtty_tu = env->GetFieldID(c, "rtty_tu", "I");
    FF.rtty_state = env->GetFieldID(c, "rtty_state", "Ljava/lang/String;");
    FF.r_flag = env->GetFieldID(c, "r_flag", "I");
    FF.eu_serial = env->GetFieldID(c, "eu_serial", "I");
    FF.arrl_class = env->GetFieldID(c, "arrl_class", "Ljava/lang/String;");
    FF.arrl_rac = env->GetFieldID(c, "arrl_rac", "Ljava/lang/String;");
    FF.dx_call_to2 = env->GetFieldID(c, "dx_call_to2", "Ljava/lang/String;");
    FF.ready = true;
}

static void ft2_set_string(JNIEnv* env, jobject msg, jfieldID fid, const char* s)
{
    jstring js = env->NewStringUTF(s ? s : "");
    env->SetObjectField(msg, fid, js);
    env->DeleteLocalRef(js);
}

static bool ft2_looks_like_grid(const char* s)
{
    return s && strlen(s) == 4 &&
           s[0] >= 'A' && s[0] <= 'R' && s[1] >= 'A' && s[1] <= 'R' &&
           s[2] >= '0' && s[2] <= '9' && s[3] >= '0' && s[3] <= '9';
}

static void ft2_set_call_hashes(JNIEnv* env, jobject msg, const char* call,
                                jfieldID f10, jfieldID f12, jfieldID f22)
{
    if (call && call[0] != '\0' && call[0] != '<')
    {
        uint32_t n22 = ft2_compute_n22(call);
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

#define FT2JNI(ret, name) \
    extern "C" JNIEXPORT ret JNICALL Java_com_k1af_ft8af_ft8listener_FT8SignalListener_##name

// ---------------------------------------------------------------------------
FT2JNI(jlong, InitDecoderFt2)(JNIEnv*, jobject, jlong utcTime, jint sampleRate, jint num_samples, jint protocol)
{
    ft2_decoder_state* d = (ft2_decoder_state*)calloc(1, sizeof(ft2_decoder_state));
    if (!d)
        return 0;
    d->sample_rate = sampleRate;
    d->num_samples = num_samples;
    d->utc = utcTime;
    d->ldpc_iterations = 20;

    // protocol is the ftx_protocol_t int from Java (ModeProfile.ftxProtocol): FT4=0, FT8=1,
    // FT2=2. The from-source path serves FT2 and FT4; default to FT2 for any unexpected
    // value so an older caller can't misconfigure the monitor.
    ftx_protocol_t proto = (protocol == (jint)FTX_PROTOCOL_FT4) ? FTX_PROTOCOL_FT4 : FTX_PROTOCOL_FT2;

    monitor_config_t cfg;
    cfg.f_min = 100;
    cfg.f_max = 3500;
    cfg.sample_rate = sampleRate;
    cfg.time_osr = 2;
    cfg.freq_osr = 2;
    cfg.protocol = proto;
    monitor_init(&d->mon, &cfg);
    d->mon_ready = true;

    d->samples = (float*)calloc(num_samples > 0 ? num_samples : 1, sizeof(float));
    return (jlong)(intptr_t)d;
}

FT2JNI(void, DeleteDecoderFt2)(JNIEnv*, jobject, jlong handle)
{
    ft2_decoder_state* d = (ft2_decoder_state*)(intptr_t)handle;
    if (!d)
        return;
    if (d->mon_ready)
        monitor_free(&d->mon);
    free(d->samples);
    free(d);
}

// Feed the whole slot through the monitor and keep a copy for subtraction.
static void ft2_feed(ft2_decoder_state* d, const float* data, int n)
{
    if (!d || !d->mon_ready)
        return;
    if (d->samples && n <= d->num_samples)
        memcpy(d->samples, data, sizeof(float) * n);
    monitor_reset(&d->mon);
    for (int pos = 0; pos + d->mon.block_size <= n; pos += d->mon.block_size)
        monitor_process(&d->mon, data + pos);
}

FT2JNI(void, DecoderFt2MonitorPressFloat)(JNIEnv* env, jobject, jfloatArray buffer, jlong handle)
{
    ft2_decoder_state* d = (ft2_decoder_state*)(intptr_t)handle;
    if (!d)
        return;
    jsize n = env->GetArrayLength(buffer);
    jfloat* data = env->GetFloatArrayElements(buffer, nullptr);
    ft2_feed(d, data, n);
    env->ReleaseFloatArrayElements(buffer, data, JNI_ABORT);
}

FT2JNI(void, setDecodeModeFt2)(JNIEnv*, jobject, jlong handle, jboolean isDeep)
{
    ft2_decoder_state* d = (ft2_decoder_state*)(intptr_t)handle;
    if (!d)
        return;
    d->ldpc_iterations = isDeep ? 30 : 20;
}

FT2JNI(jint, DecoderFt2FindSync)(JNIEnv*, jobject, jlong handle)
{
    ft2_decoder_state* d = (ft2_decoder_state*)(intptr_t)handle;
    if (!d || !d->mon_ready)
        return 0;
    d->num_candidates = ft8_find_sync(&d->mon.wf, kMaxCandidates, d->candidates, kMinScore);
    return d->num_candidates;
}

FT2JNI(jboolean, DecoderFt2Analysis)(JNIEnv* env, jobject, jint idx, jlong handle, jobject ft8Message)
{
    ft2_decoder_state* d = (ft2_decoder_state*)(intptr_t)handle;
    if (!d || !d->mon_ready || idx < 0 || idx >= d->num_candidates)
        return JNI_FALSE;

    ft2_cache_fields(env, ft8Message);
    const candidate_t* cand = &d->candidates[idx];

    ftx_message_t message;
    decode_status_t status;
    if (!ft8_decode(&d->mon.wf, cand, d->ldpc_iterations, &message, &status))
        return JNI_FALSE;

    d->last_message = message;
    d->have_last = true;

    // Geometry + signal metrics
    float freq_hz = (d->mon.min_bin + cand->freq_offset +
                     (float)cand->freq_sub / d->mon.wf.freq_osr) / d->mon.symbol_period;
    float time_sec = (cand->time_offset +
                      (float)cand->time_sub / d->mon.wf.time_osr) * d->mon.symbol_period;
    g_active = d;
    int snr = ft8_snr(&d->mon.wf, cand);

    env->SetFloatField(ft8Message, FF.freq_hz, freq_hz);
    env->SetFloatField(ft8Message, FF.time_sec, time_sec);
    env->SetIntField(ft8Message, FF.snr, snr);
    env->SetIntField(ft8Message, FF.score, cand->score);
    env->SetIntField(ft8Message, FF.messageHash, (jint)message.hash);
    env->SetIntField(ft8Message, FF.report, -100);

    ftx_callsign_hash_interface_t hash_if = { ft2_hash_lookup, ft2_hash_save };
    uint8_t i3 = ftx_message_get_i3(&message);
    uint8_t n3 = ftx_message_get_n3(&message);
    ftx_message_type_t type = ftx_message_get_type(&message);
    env->SetIntField(ft8Message, FF.i3, i3);
    env->SetIntField(ft8Message, FF.n3, n3);

    // Reset contest-only fields to defaults.
    env->SetIntField(ft8Message, FF.rtty_tu, 0);
    env->SetIntField(ft8Message, FF.r_flag, 0);
    env->SetIntField(ft8Message, FF.eu_serial, 0);
    ft2_set_string(env, ft8Message, FF.rtty_state, "");
    ft2_set_string(env, ft8Message, FF.arrl_class, "");
    ft2_set_string(env, ft8Message, FF.arrl_rac, "");
    ft2_set_string(env, ft8Message, FF.dx_call_to2, "");
    ft2_set_string(env, ft8Message, FF.maidenGrid, "");

    char call_to[20] = {0}, call_de[20] = {0}, extra[20] = {0};
    bool ok = false;

    if (type == FTX_MESSAGE_TYPE_FREE_TEXT)
    {
        char text[FTX_MAX_MESSAGE_LENGTH] = {0};
        ftx_message_decode_free(&message, text);
        ft2_set_string(env, ft8Message, FF.callsignTo, "");
        ft2_set_string(env, ft8Message, FF.callsignFrom, "");
        ft2_set_string(env, ft8Message, FF.extraInfo, text);
        ft2_set_call_hashes(env, ft8Message, "", FF.callToHash10, FF.callToHash12, FF.callToHash22);
        ft2_set_call_hashes(env, ft8Message, "", FF.callFromHash10, FF.callFromHash12, FF.callFromHash22);
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
    else
    {
        // Contest sub-types: decode to full text into extraInfo for now.
        char text[FTX_MAX_MESSAGE_LENGTH] = {0};
        if (ftx_message_decode(&message, &hash_if, text) == FTX_MESSAGE_RC_OK)
        {
            env->SetIntField(ft8Message, FF.i3, 0);
            env->SetIntField(ft8Message, FF.n3, 0);
            ft2_set_string(env, ft8Message, FF.callsignTo, "");
            ft2_set_string(env, ft8Message, FF.callsignFrom, "");
            ft2_set_string(env, ft8Message, FF.extraInfo, text);
            ft2_set_call_hashes(env, ft8Message, "", FF.callToHash10, FF.callToHash12, FF.callToHash22);
            ft2_set_call_hashes(env, ft8Message, "", FF.callFromHash10, FF.callFromHash12, FF.callFromHash22);
            ok = true;
        }
    }

    if (ok && (type == FTX_MESSAGE_TYPE_STANDARD || type == FTX_MESSAGE_TYPE_NONSTD_CALL))
    {
        ft2_set_string(env, ft8Message, FF.callsignTo, call_to);
        ft2_set_string(env, ft8Message, FF.callsignFrom, call_de);
        ft2_set_string(env, ft8Message, FF.extraInfo, extra);
        if (ft2_looks_like_grid(extra))
            ft2_set_string(env, ft8Message, FF.maidenGrid, extra);
        ft2_set_call_hashes(env, ft8Message, call_to, FF.callToHash10, FF.callToHash12, FF.callToHash22);
        ft2_set_call_hashes(env, ft8Message, call_de, FF.callFromHash10, FF.callFromHash12, FF.callFromHash22);

        // Recover the raw hash of an unresolved compound call ("<...>") from the
        // frame so Java's seeded MessageHashMap can resolve it (issue #392); see
        // the matching block in ft8_decode_jni.cpp for the full rationale.
        if (type == FTX_MESSAGE_TYPE_NONSTD_CALL)
        {
            uint32_t n12 = 0;
            bool is_to = false;
            if (ft8af_nonstd_hash12(&message, &n12, &is_to))
                env->SetLongField(ft8Message,
                                  is_to ? FF.callToHash12 : FF.callFromHash12,
                                  (jlong)n12);
        }
        else // FTX_MESSAGE_TYPE_STANDARD
        {
            uint32_t n22 = 0;
            if (call_to[0] == '<' && ft8af_std_hash22(&message, 0, &n22))
                env->SetLongField(ft8Message, FF.callToHash22, (jlong)n22);
            if (call_de[0] == '<' && ft8af_std_hash22(&message, 1, &n22))
                env->SetLongField(ft8Message, FF.callFromHash22, (jlong)n22);
        }
    }

    env->SetBooleanField(ft8Message, FF.isValid, ok ? JNI_TRUE : JNI_FALSE);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Return the 12-byte a91 (77-bit payload + 14-bit CRC) of the last decoded message.
FT2JNI(jbyteArray, DecoderFt2GetA91)(JNIEnv* env, jobject, jlong handle)
{
    ft2_decoder_state* d = (ft2_decoder_state*)(intptr_t)handle;
    jbyteArray result = env->NewByteArray(FTX_LDPC_K_BYTES);
    if (!d || !d->have_last)
        return result;
    uint8_t a91[FTX_LDPC_K_BYTES];
    ftx_add_crc(d->last_message.payload, a91);
    env->SetByteArrayRegion(result, 0, FTX_LDPC_K_BYTES, reinterpret_cast<const jbyte*>(a91));
    return result;
}

// ---------------------------------------------------------------------------
// ReBuildSignal.doSubtractSignalFt2 — deep-decode subtraction for FT2 and FT4. Zero the
// waterfall magnitude cells the decoded signal occupies (across the 105 4-GFSK symbol
// blocks) so the next find_sync pass can't re-detect it.
//
// The geometry is protocol-correct for both FT2 and FT4 without a per-protocol width: the
// freq/time offsets use d->mon.symbol_period (set per protocol at init), and the signal
// occupies the same number of *bins* in either mode — bin width = 1/symbol_period scales
// with the tone spacing, so ~2 bins covers half the 4-tone signal for FT2 (~167 Hz at
// 41.67 Hz/bin) and FT4 (~83 Hz at 20.83 Hz/bin) alike.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_k1af_ft8af_ft8listener_ReBuildSignal_doSubtractSignalFt2(
        JNIEnv*, jclass, jlong handle, jbyteArray /*payload*/, jint /*sample_rate*/,
        jfloat frequency, jfloat time_sec)
{
    ft2_decoder_state* d = (ft2_decoder_state*)(intptr_t)handle;
    if (!d || !d->mon_ready)
        return;
    waterfall_t* wf = &d->mon.wf;
    if (!wf->mag || wf->num_blocks <= 0)
        return;

    int freq_offset = (int)lrintf(frequency * d->mon.symbol_period) - d->mon.min_bin;
    int time_offset = (int)lrintf(time_sec / d->mon.symbol_period);

    const int kFreqHalfWidth = 2; // ~167 Hz FT2 bandwidth at ~41.67 Hz/bin
    const int kNumSymbols = 105;  // FT4_NN (FT2 shares FT4's symbol count)

    for (int blk = time_offset; blk < time_offset + kNumSymbols && blk < wf->num_blocks; ++blk)
    {
        if (blk < 0)
            continue;
        uint8_t* block_base = wf->mag + (size_t)blk * wf->block_stride;
        for (int ts = 0; ts < wf->time_osr; ++ts)
        {
            for (int fs = 0; fs < wf->freq_osr; ++fs)
            {
                uint8_t* row = block_base + (ts * wf->freq_osr + fs) * wf->num_bins;
                for (int b = freq_offset - kFreqHalfWidth; b <= freq_offset + kFreqHalfWidth; ++b)
                {
                    if (b >= 0 && b < wf->num_bins)
                        row[b] = 0;
                }
            }
        }
    }
}
