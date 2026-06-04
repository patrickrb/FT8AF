//! Cross-platform audio I/O (cpal): device enumeration, continuous 12 kHz
//! capture into a slot buffer, and TX waveform playback.

pub mod devices;
pub mod input;
pub mod output;
pub mod resample;
pub mod slot;

pub use devices::{list_input_devices, list_output_devices, AudioDevice};
pub use input::AudioInput;
pub use slot::SlotAccumulator;
