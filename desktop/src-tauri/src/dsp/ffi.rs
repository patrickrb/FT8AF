//! Hand-written FFI to the C FT8 DSP core (ft8_lib @ 6f528128 + gfsk.c).
//!
//! The `#[repr(C)]` structs mirror the layouts in `common/monitor.h`,
//! `ft8/decode.h`, `ft8/message.h` and `ft8/constants.h` byte-for-byte. The pin
//! is fixed, so this is a one-time alignment; `dsp::layout_tests` asserts the
//! sizes the C side expects.
#![allow(non_camel_case_types)]

use std::os::raw::{c_char, c_int, c_void};

// --- enums (C enums are `int`-sized) ----------------------------------------

// ftx_protocol_t
pub const FTX_PROTOCOL_FT4: c_int = 0;
pub const FTX_PROTOCOL_FT8: c_int = 1;

// ftx_message_type_t (declaration order in message.h)
pub const FTX_MESSAGE_TYPE_FREE_TEXT: c_int = 0;
pub const FTX_MESSAGE_TYPE_STANDARD: c_int = 6;
pub const FTX_MESSAGE_TYPE_NONSTD_CALL: c_int = 8;

// ftx_callsign_hash_type_e
pub const FTX_CALLSIGN_HASH_22_BITS: c_int = 0;
pub const FTX_CALLSIGN_HASH_12_BITS: c_int = 1;
pub const FTX_CALLSIGN_HASH_10_BITS: c_int = 2;

// ftx_message_rc_t
pub const FTX_MESSAGE_RC_OK: c_int = 0;

// --- structs ----------------------------------------------------------------

#[repr(C)]
pub struct monitor_config_t {
    pub f_min: f32,
    pub f_max: f32,
    pub sample_rate: c_int,
    pub time_osr: c_int,
    pub freq_osr: c_int,
    pub protocol: c_int, // ftx_protocol_t
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct waterfall_t {
    pub max_blocks: c_int,
    pub num_blocks: c_int,
    pub num_bins: c_int,
    pub time_osr: c_int,
    pub freq_osr: c_int,
    pub mag: *mut u8,
    pub block_stride: c_int,
    pub protocol: c_int, // ftx_protocol_t
}

#[repr(C)]
pub struct monitor_t {
    pub symbol_period: f32,
    pub min_bin: c_int,
    pub max_bin: c_int,
    pub block_size: c_int,
    pub subblock_size: c_int,
    pub nfft: c_int,
    pub fft_norm: f32,
    pub window: *mut f32,
    pub last_frame: *mut f32,
    pub wf: waterfall_t,
    pub max_mag: f32,
    pub fft_work: *mut c_void,
    pub fft_cfg: *mut c_void, // kiss_fftr_cfg (opaque pointer)
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct candidate_t {
    pub score: i16,
    pub time_offset: i16,
    pub freq_offset: i16,
    pub time_sub: u8,
    pub freq_sub: u8,
    pub snr: i16,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct decode_status_t {
    pub ldpc_errors: c_int,
    pub crc_extracted: u16,
    pub crc_calculated: u16,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct ftx_message_t {
    pub payload: [u8; 10], // FTX_PAYLOAD_LENGTH_BYTES
    pub hash: u16,
}

pub type lookup_hash_fn = extern "C" fn(hash_type: c_int, hash: u32, callsign: *mut c_char) -> bool;
pub type save_hash_fn = extern "C" fn(callsign: *const c_char, n22: u32);

#[repr(C)]
pub struct ftx_callsign_hash_interface_t {
    pub lookup_hash: Option<lookup_hash_fn>,
    pub save_hash: Option<save_hash_fn>,
}

// --- function declarations --------------------------------------------------

extern "C" {
    pub fn monitor_init(me: *mut monitor_t, cfg: *const monitor_config_t);
    pub fn monitor_reset(me: *mut monitor_t);
    pub fn monitor_process(me: *mut monitor_t, frame: *const f32);
    pub fn monitor_free(me: *mut monitor_t);

    pub fn ft8_find_sync(
        power: *const waterfall_t,
        num_candidates: c_int,
        heap: *mut candidate_t,
        min_score: c_int,
    ) -> c_int;

    pub fn ft8_decode(
        power: *const waterfall_t,
        cand: *const candidate_t,
        max_iterations: c_int,
        message: *mut ftx_message_t,
        status: *mut decode_status_t,
    ) -> bool;

    // Non-static in decode.c but absent from decode.h (declared here as in ft8_decoder.cpp).
    pub fn ft8_snr(wf: *const waterfall_t, candidate: *const candidate_t) -> c_int;

    pub fn ftx_message_get_i3(msg: *const ftx_message_t) -> u8;
    pub fn ftx_message_get_n3(msg: *const ftx_message_t) -> u8;
    pub fn ftx_message_get_type(msg: *const ftx_message_t) -> c_int;

    pub fn ftx_message_decode(
        msg: *const ftx_message_t,
        hash_if: *mut ftx_callsign_hash_interface_t,
        message: *mut c_char,
    ) -> c_int;
    pub fn ftx_message_decode_std(
        msg: *const ftx_message_t,
        hash_if: *mut ftx_callsign_hash_interface_t,
        call_to: *mut c_char,
        call_de: *mut c_char,
        extra: *mut c_char,
    ) -> c_int;
    pub fn ftx_message_decode_nonstd(
        msg: *const ftx_message_t,
        hash_if: *mut ftx_callsign_hash_interface_t,
        call_to: *mut c_char,
        call_de: *mut c_char,
        extra: *mut c_char,
    ) -> c_int;
    pub fn ftx_message_decode_free(msg: *const ftx_message_t, text: *mut c_char);

    // encode side
    pub fn pack77(msg: *const c_char, c77: *mut u8) -> c_int;
    pub fn packtext77(text: *const c_char, b77: *mut u8);
    pub fn ft8_encode(payload: *const u8, tones: *mut u8);
    pub fn ftx_add_crc(payload: *const u8, a91: *mut u8);

    // gfsk glue
    pub fn synth_gfsk_offset(
        symbols: *const u8,
        n_sym: c_int,
        f0: f32,
        symbol_bt: f32,
        symbol_period: f32,
        signal_rate: c_int,
        signal: *mut f32,
        offset: c_int,
    );
}
