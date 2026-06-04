//! FT8 encode path — safe wrappers over pack77 / ft8_encode / synth_gfsk_offset,
//! mirroring `GenerateFT8.generateFt8` on the Java side.

use super::ffi;
use std::ffi::CString;

pub const FT8_NN: usize = 79; // total channel symbols
pub const FT8_SYMBOL_PERIOD: f32 = 0.160;
pub const FT8_SYMBOL_BT: f32 = 2.0;

/// Pack a structured FT8 message into a 10-byte (77-bit) payload.
/// Returns None if pack77 rejects the message (nonzero status).
pub fn pack77(msg: &str) -> Option<[u8; 10]> {
    let c = CString::new(msg).ok()?;
    let mut payload = [0u8; 10];
    let rc = unsafe { ffi::pack77(c.as_ptr(), payload.as_mut_ptr()) };
    if rc == 0 {
        Some(payload)
    } else {
        None
    }
}

/// Force free-text (i3=0, n3=0) packing regardless of structure.
pub fn pack_free_text(msg: &str) -> [u8; 10] {
    let c = CString::new(msg).unwrap_or_default();
    let mut payload = [0u8; 10];
    unsafe { ffi::packtext77(c.as_ptr(), payload.as_mut_ptr()) };
    payload
}

/// Encode a 10-byte payload into 79 FT8 tones (CRC + LDPC added internally).
pub fn encode_tones(payload: &[u8; 10]) -> [u8; FT8_NN] {
    let mut tones = [0u8; FT8_NN];
    unsafe { ffi::ft8_encode(payload.as_ptr(), tones.as_mut_ptr()) };
    tones
}

/// Add the 14-bit CRC, producing the 12-byte a91 (77-bit payload + CRC).
pub fn add_crc(payload: &[u8; 10]) -> [u8; 12] {
    let mut a91 = [0u8; 12];
    unsafe { ffi::ftx_add_crc(payload.as_ptr(), a91.as_mut_ptr()) };
    a91
}

/// Render `tones` as a GFSK waveform into `signal[offset..]`.
pub fn synth_gfsk(tones: &[u8], f0: f32, sample_rate: i32, signal: &mut [f32], offset: usize) {
    unsafe {
        ffi::synth_gfsk_offset(
            tones.as_ptr(),
            tones.len() as i32,
            f0,
            FT8_SYMBOL_BT,
            FT8_SYMBOL_PERIOD,
            sample_rate,
            signal.as_mut_ptr(),
            offset as i32,
        );
    }
}

/// Full encode: structured message text -> 12.64 s of mono `f32` audio at
/// `sample_rate`, centered on `base_freq_hz`. Returns None if the text can't be
/// packed as a structured message. Mirrors `GenerateFT8.generateFt8`.
pub fn generate_ft8(text: &str, base_freq_hz: f32, sample_rate: i32) -> Option<Vec<f32>> {
    let payload = pack77(text)?;
    let tones = encode_tones(&payload);
    let num_samples = (0.5 + FT8_NN as f32 * FT8_SYMBOL_PERIOD * sample_rate as f32) as usize;
    let mut signal = vec![0.0f32; num_samples];
    synth_gfsk(&tones, base_freq_hz, sample_rate, &mut signal, 0);
    Some(signal)
}
