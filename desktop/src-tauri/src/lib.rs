//! FT8AF desktop core library.
//!
//! For Milestone 1 this exposes only the DSP layer (FFI to the C ft8_lib core).
//! Audio, scheduling, rig control, persistence and the Tauri shell are added in
//! later milestones.

pub mod audio;
pub mod bands;
// Build-time arch-selection helpers, shared with `build.rs` via `include!`.
// Unused by the library itself (only build.rs calls them), so silence dead_code;
// they exist here purely so `cargo test` runs their unit tests.
#[allow(dead_code)]
mod build_support;
pub mod db;
pub mod dsp;
pub mod engine;
pub mod qso;
pub mod rig;
pub mod timesync;
pub mod util;
pub mod wf;
