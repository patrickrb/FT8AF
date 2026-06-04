//! FT8AF desktop core library.
//!
//! For Milestone 1 this exposes only the DSP layer (FFI to the C ft8_lib core).
//! Audio, scheduling, rig control, persistence and the Tauri shell are added in
//! later milestones.

pub mod audio;
pub mod bands;
pub mod db;
pub mod dsp;
pub mod engine;
pub mod qso;
pub mod rig;
pub mod util;
