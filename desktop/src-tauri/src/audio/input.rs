//! Continuous audio capture: open an input device, downmix to mono, resample to
//! 12 kHz on a worker thread, and feed a `SlotAccumulator`.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;

use cpal::traits::{DeviceTrait, StreamTrait};
use cpal::{FromSample, Sample, SampleFormat};
use ringbuf::traits::{Consumer, Producer, Split};
use ringbuf::HeapRb;

use super::devices::find_input_device;
use super::resample::MonoResampler;
use super::slot::SlotAccumulator;
use crate::dsp::SAMPLE_RATE;

/// An active capture session. Dropping it stops capture.
pub struct AudioInput {
    _stream: cpal::Stream, // kept alive; !Send on Windows so owner stays on its thread
    stop: Arc<AtomicBool>,
    worker: Option<JoinHandle<()>>,
    pub device_name: String,
    pub device_rate: u32,
}

impl AudioInput {
    pub fn start(
        device_name: Option<&str>,
        accum: Arc<SlotAccumulator>,
    ) -> anyhow::Result<AudioInput> {
        let device = find_input_device(device_name)
            .ok_or_else(|| anyhow::anyhow!("no input audio device available"))?;
        let dev_name = device.name().unwrap_or_else(|_| "unknown".into());
        let supported = device.default_input_config()?;
        let sample_format = supported.sample_format();
        let config: cpal::StreamConfig = supported.config();
        let channels = config.channels as usize;
        let device_rate = config.sample_rate.0;

        // Ring buffer between the realtime callback and the resampling worker.
        let rb = HeapRb::<f32>::new(device_rate as usize * 2); // ~2 s headroom
        let (mut prod, mut cons) = rb.split();

        let err_fn = |e| log::error!("audio input stream error: {e}");

        // Build a callback that downmixes interleaved frames to mono f32 and
        // pushes into the ring. One arm per supported sample format.
        let stream = match sample_format {
            SampleFormat::F32 => device.build_input_stream(
                &config,
                move |data: &[f32], _| push_mono(data, channels, &mut prod),
                err_fn,
                None,
            )?,
            SampleFormat::I16 => device.build_input_stream(
                &config,
                move |data: &[i16], _| push_mono(data, channels, &mut prod),
                err_fn,
                None,
            )?,
            SampleFormat::U16 => device.build_input_stream(
                &config,
                move |data: &[u16], _| push_mono(data, channels, &mut prod),
                err_fn,
                None,
            )?,
            other => anyhow::bail!("unsupported input sample format: {other:?}"),
        };
        stream.play()?;

        // Resampling worker: device_rate mono -> 12 kHz -> SlotAccumulator.
        let stop = Arc::new(AtomicBool::new(false));
        let stop_w = stop.clone();
        let worker = std::thread::Builder::new()
            .name("ft8af-audio-resample".into())
            .spawn(move || {
                let mut resampler = MonoResampler::new(device_rate, SAMPLE_RATE as u32);
                let mut scratch = vec![0.0f32; 4096];
                while !stop_w.load(Ordering::Relaxed) {
                    let n = cons.pop_slice(&mut scratch);
                    if n == 0 {
                        std::thread::sleep(std::time::Duration::from_millis(5));
                        continue;
                    }
                    let out = resampler.process(&scratch[..n]);
                    if !out.is_empty() {
                        accum.push(&out);
                    }
                }
            })?;

        Ok(AudioInput {
            _stream: stream,
            stop,
            worker: Some(worker),
            device_name: dev_name,
            device_rate,
        })
    }
}

impl Drop for AudioInput {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(w) = self.worker.take() {
            let _ = w.join();
        }
    }
}

/// Downmix interleaved `T` frames to mono f32 and push into the ring producer.
fn push_mono<T, P>(data: &[T], channels: usize, prod: &mut P)
where
    T: Sample,
    f32: FromSample<T>,
    P: Producer<Item = f32>,
{
    if channels == 0 {
        return;
    }
    if channels == 1 {
        for &s in data {
            let _ = prod.try_push(f32::from_sample(s));
        }
        return;
    }
    for frame in data.chunks_exact(channels) {
        let mut acc = 0.0f32;
        for &s in frame {
            acc += f32::from_sample(s);
        }
        let _ = prod.try_push(acc / channels as f32);
    }
}
