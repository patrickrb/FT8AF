//! TX audio playback: render a 12 kHz mono FT8 waveform to an output device,
//! resampling to the device rate and fanning the mono sample across channels.

use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;

use cpal::traits::{DeviceTrait, StreamTrait};
use cpal::{FromSample, SampleFormat, SizedSample};

use super::devices::find_output_device;
use super::resample::resample_buffer;
use crate::dsp::SAMPLE_RATE;

/// Play a 12 kHz mono buffer to completion (blocking). `gain` scales amplitude.
/// Returns when the whole buffer has been clocked out (plus a short tail).
pub fn play_blocking(
    device_name: Option<&str>,
    samples_12k: &[f32],
    gain: f32,
) -> anyhow::Result<()> {
    let device = find_output_device(device_name)
        .ok_or_else(|| anyhow::anyhow!("no output audio device available"))?;
    let supported = device.default_output_config()?;
    let sample_format = supported.sample_format();
    let config: cpal::StreamConfig = supported.config();
    let channels = config.channels as usize;
    let device_rate = config.sample_rate.0;

    let mut data = resample_buffer(samples_12k, SAMPLE_RATE as u32, device_rate);
    if gain != 1.0 {
        for s in data.iter_mut() {
            *s = (*s * gain).clamp(-1.0, 1.0);
        }
    }
    let total = data.len();
    let buffer = Arc::new(data);
    let cursor = Arc::new(AtomicUsize::new(0));
    let done = Arc::new(AtomicBool::new(false));

    let err_fn = |e| log::error!("audio output stream error: {e}");

    let stream = match sample_format {
        SampleFormat::F32 => build_output::<f32>(&device, &config, channels, &buffer, &cursor, &done, err_fn)?,
        SampleFormat::I16 => build_output::<i16>(&device, &config, channels, &buffer, &cursor, &done, err_fn)?,
        SampleFormat::U16 => build_output::<u16>(&device, &config, channels, &buffer, &cursor, &done, err_fn)?,
        other => anyhow::bail!("unsupported output sample format: {other:?}"),
    };
    stream.play()?;

    // Wait for the callback to clock out the whole buffer.
    let timeout = std::time::Duration::from_secs_f32(total as f32 / device_rate as f32 + 2.0);
    let start = std::time::Instant::now();
    while !done.load(Ordering::Relaxed) {
        if start.elapsed() > timeout {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    // brief tail so the device flushes its final buffer
    std::thread::sleep(std::time::Duration::from_millis(50));
    drop(stream);
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn build_output<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    channels: usize,
    buffer: &Arc<Vec<f32>>,
    cursor: &Arc<AtomicUsize>,
    done: &Arc<AtomicBool>,
    err_fn: impl FnMut(cpal::StreamError) + Send + 'static,
) -> anyhow::Result<cpal::Stream>
where
    T: SizedSample + FromSample<f32>,
{
    let buffer = buffer.clone();
    let cursor = cursor.clone();
    let done = done.clone();
    let stream = device.build_output_stream(
        config,
        move |out: &mut [T], _| {
            for frame in out.chunks_mut(channels) {
                let idx = cursor.load(Ordering::Relaxed);
                let sample = if idx < buffer.len() {
                    cursor.store(idx + 1, Ordering::Relaxed);
                    buffer[idx]
                } else {
                    done.store(true, Ordering::Relaxed);
                    0.0
                };
                let v = T::from_sample(sample);
                for s in frame.iter_mut() {
                    *s = v;
                }
            }
        },
        err_fn,
        None,
    )?;
    Ok(stream)
}
