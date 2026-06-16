//! FT8 DSP: safe Rust over the C ft8_lib core.

pub mod decoder;
pub mod encode;
pub mod ffi;
pub mod hashtable;

pub use decoder::{DecodedMessage, Decoder};

/// Sample rate the FT8 codec runs at (fixed).
pub const SAMPLE_RATE: i32 = 12000;
/// Samples in one 15 s FT8 slot at `SAMPLE_RATE`.
pub const SLOT_SAMPLES: usize = (SAMPLE_RATE as usize) * 15;

#[cfg(test)]
mod layout_tests {
    use super::ffi;
    use std::mem::size_of;

    // Guard against `#[repr(C)]` drift from the pinned C headers. These sizes
    // are stable for the 6f528128 pin on LP64/LLP64 targets.
    #[test]
    fn struct_sizes() {
        assert_eq!(size_of::<ffi::candidate_t>(), 10, "candidate_t");
        assert_eq!(size_of::<ffi::ftx_message_t>(), 12, "ftx_message_t");
        assert_eq!(size_of::<ffi::decode_status_t>(), 8, "decode_status_t");
        // waterfall_t: 5*int(20) + pad(4) + ptr(8) + int(4) + int/enum(4) = 40
        // with 8-byte pointer alignment (LLP64/LP64).
        assert_eq!(size_of::<ffi::waterfall_t>(), 40, "waterfall_t");
        assert_eq!(size_of::<ffi::monitor_config_t>(), 24, "monitor_config_t");
    }
}
