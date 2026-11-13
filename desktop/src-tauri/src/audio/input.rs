//! Continuous audio capture: open an input device, downmix to mono, resample to
//! 12 kHz on a worker thread, and feed a `SlotAccumulator`.

use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
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
    gain: Arc<AtomicU32>, // f32 bits, read/written lock-free from the realtime callback
}

impl AudioInput {
    /// Live-adjustable RX gain (a linear multiplier applied to each downmixed
    /// sample, e.g. 1.0 = unity, 0.5 = -6 dB) -- some bands are noisier than
    /// others, so this is expected to change often while decoding, not just
    /// at startup. Lock-free: the realtime audio callback only ever loads
    /// this, never blocks on it.
    pub fn set_gain(&self, g: f32) {
        self.gain.store(g.to_bits(), Ordering::Relaxed);
    }

    pub fn start(
        device_name: Option<&str>,
        accum: Arc<SlotAccumulator>,
        initial_gain: f32,
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

        let gain = Arc::new(AtomicU32::new(initial_gain.to_bits()));

        let err_fn = |e| log::error!("audio input stream error: {e}");

        // Build a callback that downmixes interleaved frames to mono f32,
        // applies the live RX gain, and pushes into the ring. One arm per
        // supported sample format. Each arm clones `gain` independently --
        // disjoint match arms may each move their own capture of a variable
        // without conflicting (only one arm's closure is ever actually built).
        let stream = match sample_format {
            SampleFormat::F32 => {
                let gain = gain.clone();
                device.build_input_stream(
                    &config,
                    move |data: &[f32], _| push_mono(data, channels, &mut prod, &gain),
                    err_fn,
                    None,
                )?
            }
            SampleFormat::I16 => {
                let gain = gain.clone();
                device.build_input_stream(
                    &config,
                    move |data: &[i16], _| push_mono(data, channels, &mut prod, &gain),
                    err_fn,
                    None,
                )?
            }
            SampleFormat::U16 => {
                let gain = gain.clone();
                device.build_input_stream(
                    &config,
                    move |data: &[u16], _| push_mono(data, channels, &mut prod, &gain),
                    err_fn,
                    None,
                )?
            }
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
            gain,
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

/// Downmix interleaved `T` frames to mono f32, apply the live RX gain, and
/// push into the ring producer. `gain` is read fresh per sample (a relaxed
/// atomic load is cheap, and this runs on the realtime audio thread, so no
/// locking) -- lets the gain slider feel immediate rather than only taking
/// effect on the next buffer.
fn push_mono<T, P>(data: &[T], channels: usize, prod: &mut P, gain: &AtomicU32)
where
    T: Sample,
    f32: FromSample<T>,
    P: Producer<Item = f32>,
{
    if channels == 0 {
        return;
    }
    let g = f32::from_bits(gain.load(Ordering::Relaxed));
    // Clamp to full scale, same as the TX gain path (audio/output.rs) --
    // hard-clipping mirrors what a real ADC does when overdriven, rather than
    // passing arbitrarily large sample values into the resampler/decoder.
    //
    // Only *above* unity though. A gain of 1.0 or less cannot push a sample
    // past full scale that was not already there, so clamping unconditionally
    // would only ever change the samples a device delivered out of range in
    // the first place -- and F32 devices (CoreAudio, JACK/PipeWire) do hand
    // out the occasional sample a hair over 1.0. Clipping those on a default,
    // unity-gain install adds harmonics to RX audio that the capture path
    // never introduced before the gain control existed. clamp_rx_gain caps at
    // 1.0 today, so this is dormant; it keeps the guard correct if the range
    // is ever widened.
    let clip = g > 1.0;
    let limit = |x: f32| if clip { x.clamp(-1.0, 1.0) } else { x };
    if channels == 1 {
        for &s in data {
            let _ = prod.try_push(limit(f32::from_sample(s) * g));
        }
        return;
    }
    for frame in data.chunks_exact(channels) {
        let mut acc = 0.0f32;
        for &s in frame {
            acc += f32::from_sample(s);
        }
        let _ = prod.try_push(limit((acc / channels as f32) * g));
    }
}

#[cfg(test)]
mod tests {
    use super::push_mono;
    use ringbuf::traits::{Consumer, Split};
    use ringbuf::HeapRb;
    use std::sync::atomic::AtomicU32;

    /// Run `push_mono` over `data` at the given gain and collect what landed.
    fn pushed(data: &[f32], channels: usize, gain: f32) -> Vec<f32> {
        let rb = HeapRb::<f32>::new(data.len().max(1) + 1);
        let (mut prod, mut cons) = rb.split();
        push_mono(data, channels, &mut prod, &AtomicU32::new(gain.to_bits()));
        cons.pop_iter().collect()
    }

    #[test]
    fn unity_gain_is_a_pure_pass_through() {
        // The regression this guards: an unconditional clamp would hard-clip
        // the over-full-scale samples F32 devices (CoreAudio, JACK/PipeWire)
        // occasionally deliver, changing RX audio on a default install where
        // the gain control was never touched.
        let hot = [0.5, -0.5, 1.02, -1.04];
        assert_eq!(pushed(&hot, 1, 1.0), hot.to_vec());
    }

    #[test]
    fn gain_below_unity_scales_without_clipping() {
        assert_eq!(pushed(&[0.5, -0.25], 1, 0.5), vec![0.25, -0.125]);
        // Still no clamp: halving an over-scale sample leaves it over-scale,
        // exactly as the pre-gain path delivered it.
        assert_eq!(pushed(&[1.6], 1, 0.5), vec![0.8]);
        assert_eq!(pushed(&[2.4], 1, 1.0), vec![2.4]);
    }

    #[test]
    fn gain_above_unity_clips_to_full_scale() {
        // Dormant while clamp_rx_gain caps at 1.0, but the guard has to be
        // correct if that range is ever widened.
        assert_eq!(pushed(&[0.8, -0.8, 0.1], 1, 2.0), vec![1.0, -1.0, 0.2]);
    }

    #[test]
    fn multichannel_frames_are_averaged_then_gained() {
        // Two stereo frames: (0.4, 0.8) -> 0.6, (-1.0, 0.0) -> -0.5.
        assert_eq!(pushed(&[0.4, 0.8, -1.0, 0.0], 2, 1.0), vec![0.6, -0.5]);
        assert_eq!(pushed(&[0.4, 0.8, -1.0, 0.0], 2, 0.5), vec![0.3, -0.25]);
    }

    #[test]
    fn zero_channels_pushes_nothing() {
        assert!(pushed(&[0.5, 0.5], 0, 1.0).is_empty());
    }

    #[test]
    fn a_trailing_partial_frame_is_dropped() {
        // chunks_exact: three samples on a stereo stream yield one frame.
        let got = pushed(&[0.2, 0.4, 0.9], 2, 1.0);
        assert_eq!(got.len(), 1);
        assert!((got[0] - 0.3).abs() < 1e-6, "got {got:?}");
    }
}
