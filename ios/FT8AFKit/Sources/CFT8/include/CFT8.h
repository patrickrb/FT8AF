// Umbrella header for the CFT8 module — the Swift-visible surface of the pure-C
// ft8_lib DSP core (kgoba @ 6f528128) plus the ft8af_glue GFSK synth.
//
// Swift imports these C declarations directly, so we never re-declare the
// structs (monitor_t, waterfall_t, candidate_t, ftx_message_t, ...) on the Swift
// side — Swift sees them natively. We only (1) pull in every public header and
// (2) add prototypes for the two symbols ft8_lib defines but does not expose in
// a public header (`ft8_snr`, `synth_gfsk_offset`), mirroring desktop dsp/ffi.rs.
//
// The angle includes below resolve via the CFT8 target's header-search path
// (Package.swift -> ft8_lib root).
#ifndef FT8AF_CFT8_UMBRELLA_H
#define FT8AF_CFT8_UMBRELLA_H

#include <stdint.h>
#include <stdbool.h>

// --- ft8_lib public surface -------------------------------------------------
#include <ft8/constants.h> // FTX_LDPC_*, FT8_NN, kFT8_Gray_map, kFT8_Costas_pattern, kFTX_LDPC_Nm, ...
#include <ft8/message.h>   // ftx_message_t, ftx_message_decode*, hash interface + enums
#include <ft8/pack.h>      // pack77, packtext77
#include <ft8/encode.h>    // ft8_encode, ft4_encode
#include <ft8/crc.h>       // ftx_add_crc, ftx_extract_crc, ftx_compute_crc
#include <ft8/decode.h>    // waterfall_t, candidate_t, decode_status_t, ft8_find_sync, ft8_decode
#include <common/monitor.h> // monitor_t, monitor_config_t, monitor_init/reset/process/free

// --- ft8af_glue deep-decode subtraction -------------------------------------
// ft8_subtract_signal (waterfall-domain) + ft8_subtract_signal_time (coherent
// time-domain). Resolves via the CFT8 target's -I .../ft8af_glue flag; the
// implementations are compiled by shim_subtract.c. Swift's subtract-and-redecode
// loop (FT8Decoder.swift) drives the time-domain variant.
#include <ft8_subtract.h>

#ifdef __cplusplus
extern "C" {
#endif

// --- symbols ft8_lib defines but omits from a public header -----------------
// `ft8_snr` is non-static in decode.c yet absent from decode.h (desktop
// ffi.rs:131 declares it the same way). Needs waterfall_t/candidate_t (decode.h).
int ft8_snr(const waterfall_t* wf, const candidate_t* candidate);

// `synth_gfsk_offset` lives in ft8af_glue/gfsk.c; signature mirrors
// desktop ffi.rs:165-174 and the gfsk.c definition. Writes the GFSK waveform
// into signal[offset .. offset + n_sym*n_spsym).
void synth_gfsk_offset(const uint8_t* symbols, int n_sym, float f0,
                       float symbol_bt, float symbol_period,
                       int signal_rate, float* signal, int offset);

#ifdef __cplusplus
}
#endif

#endif // FT8AF_CFT8_UMBRELLA_H
