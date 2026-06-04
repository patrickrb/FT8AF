//! Audio device enumeration via cpal (WASAPI / CoreAudio / ALSA).

use cpal::traits::{DeviceTrait, HostTrait};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AudioDevice {
    /// Stable-ish identifier (the device name; cpal has no opaque id).
    pub id: String,
    pub name: String,
    pub is_default: bool,
    pub default_sample_rate: u32,
    pub channels: u16,
}

fn collect(
    devices: impl Iterator<Item = cpal::Device>,
    default_name: Option<String>,
    input: bool,
) -> Vec<AudioDevice> {
    let mut out = Vec::new();
    for d in devices {
        let name = match d.name() {
            Ok(n) => n,
            Err(_) => continue,
        };
        let cfg = if input {
            d.default_input_config().ok()
        } else {
            d.default_output_config().ok()
        };
        let (rate, channels) = match cfg {
            Some(c) => (c.sample_rate().0, c.channels()),
            None => (0, 0),
        };
        out.push(AudioDevice {
            is_default: default_name.as_deref() == Some(name.as_str()),
            id: name.clone(),
            name,
            default_sample_rate: rate,
            channels,
        });
    }
    out
}

pub fn list_input_devices() -> Vec<AudioDevice> {
    let host = cpal::default_host();
    let default = host.default_input_device().and_then(|d| d.name().ok());
    match host.input_devices() {
        Ok(devs) => collect(devs, default, true),
        Err(_) => Vec::new(),
    }
}

pub fn list_output_devices() -> Vec<AudioDevice> {
    let host = cpal::default_host();
    let default = host.default_output_device().and_then(|d| d.name().ok());
    match host.output_devices() {
        Ok(devs) => collect(devs, default, false),
        Err(_) => Vec::new(),
    }
}

/// Find an input device by name, or the system default if `name` is None/unmatched.
pub fn find_input_device(name: Option<&str>) -> Option<cpal::Device> {
    let host = cpal::default_host();
    if let Some(want) = name {
        if let Ok(devs) = host.input_devices() {
            for d in devs {
                if d.name().ok().as_deref() == Some(want) {
                    return Some(d);
                }
            }
        }
    }
    host.default_input_device()
}

/// Find an output device by name, or the system default if `name` is None/unmatched.
pub fn find_output_device(name: Option<&str>) -> Option<cpal::Device> {
    let host = cpal::default_host();
    if let Some(want) = name {
        if let Ok(devs) = host.output_devices() {
            for d in devs {
                if d.name().ok().as_deref() == Some(want) {
                    return Some(d);
                }
            }
        }
    }
    host.default_output_device()
}
