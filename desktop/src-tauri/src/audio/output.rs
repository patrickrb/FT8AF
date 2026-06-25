//! TX audio playback: render a 12 kHz mono FT8 waveform to an output device,
//! resampling to the device rate and fanning the mono sample across channels.
//!
//! Playback is split into two phases so the engine can key the rig with minimal
//! dead air and stop a transmission mid-stream:
//!   * [`prepare`] does the heavy lifting — open the device, resample the whole
//!     waveform, build the (paused) output stream. The caller runs this *before*
//!     raising PTT so the rig isn't keyed during the resample (which is slow on
//!     weak CPUs).
//!   * [`PreparedTx::start`] is cheap: seek past any leading clip and unpause the
//!     stream. The caller runs it right after PTT settles.
//! The returned handle is polled (`is_done`/`timed_out`) and can be `abort`ed or
//! simply dropped to stop audio immediately (dropping the stream halts the cpal
//! callback). The `cpal::Stream` is `!Send` on Windows, so the handle must live
//! on the thread that created it (the engine thread).

use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, StreamTrait};
use cpal::{FromSample, SampleFormat, SizedSample};

use super::devices::find_output_device;
use super::resample::resample_buffer;
use crate::dsp::SAMPLE_RATE;

/// Slack added to the expected playback duration before the stuck-device
/// deadline trips and PTT is force-released.
const DEADLINE_MARGIN_SECS: f32 = 2.0;

/// Cursor (in device-rate mono samples) to start playback from, given a leading
/// clip of `skip_ms` of the original 12 kHz waveform. Clamped to `total` so an
/// over-long clip simply starts at the end (plays nothing) rather than panicking.
fn skip_cursor(skip_ms: i64, device_rate: u32, total: usize) -> usize {
    let skip = (skip_ms.max(0) * device_rate as i64 / 1000) as usize;
    skip.min(total)
}

/// Seconds of audio left to play from `cursor` to the end of a `total`-sample
/// device-rate buffer. Drives the stuck-device deadline.
fn remaining_secs(total: usize, cursor: usize, device_rate: u32) -> f32 {
    total.saturating_sub(cursor) as f32 / device_rate.max(1) as f32
}

/// A built-but-not-yet-playing TX waveform plus the shared state its cpal
/// callback drives. Hold it on the engine thread; poll [`is_done`]/[`timed_out`]
/// each loop tick and call [`finalize`-by-drop] (drop) when finished or stopping.
pub struct PreparedTx {
    // Kept alive for the life of the transmission; dropping it stops the audio.
    _stream: cpal::Stream,
    cursor: Arc<AtomicUsize>,
    done: Arc<AtomicBool>,
    abort: Arc<AtomicBool>,
    total: usize,
    device_rate: u32,
    /// Set when playback starts; bounds how long we'll hold PTT if the device
    /// callback never reaches the end (so a stalled stream can't key forever).
    deadline: Option<Instant>,
}

impl PreparedTx {
    /// Begin playback, skipping the first `skip_ms` of the (12 kHz) waveform.
    /// Cheap — meant to run immediately after PTT settles. Idempotent-safe.
    pub fn start(&mut self, skip_ms: i64) -> anyhow::Result<()> {
        let skip = skip_cursor(skip_ms, self.device_rate, self.total);
        self.cursor.store(skip, Ordering::Relaxed);
        self._stream.play()?;
        let remaining = remaining_secs(self.total, skip, self.device_rate);
        self.deadline = Some(Instant::now() + Duration::from_secs_f32(remaining + DEADLINE_MARGIN_SECS));
        Ok(())
    }

    /// True once the whole buffer has been clocked out (or playback was aborted).
    pub fn is_done(&self) -> bool {
        self.done.load(Ordering::Relaxed)
    }

    /// True if playback has been running longer than its expected duration plus a
    /// safety margin — a stuck-device backstop so PTT is never held indefinitely.
    pub fn timed_out(&self) -> bool {
        matches!(self.deadline, Some(d) if Instant::now() > d)
    }

    /// Signal the callback to stop emitting samples (fills silence, marks done).
    /// Dropping the handle also stops audio; this just makes the intent explicit.
    pub fn abort(&self) {
        self.abort.store(true, Ordering::Relaxed);
    }
}

/// Render a 12 kHz mono buffer for `device_name`, applying `gain`, and build the
/// (paused) output stream. Does NOT key audio — call [`PreparedTx::start`] to
/// begin. This is the slow phase (device open + full-buffer resample); run it
/// before raising PTT.
pub fn prepare(
    device_name: Option<&str>,
    samples_12k: &[f32],
    gain: f32,
) -> anyhow::Result<PreparedTx> {
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
    let abort = Arc::new(AtomicBool::new(false));

    let err_fn = |e| log::error!("audio output stream error: {e}");

    let stream = match sample_format {
        SampleFormat::F32 => build_output::<f32>(&device, &config, channels, &buffer, &cursor, &done, &abort, err_fn)?,
        SampleFormat::I16 => build_output::<i16>(&device, &config, channels, &buffer, &cursor, &done, &abort, err_fn)?,
        SampleFormat::U16 => build_output::<u16>(&device, &config, channels, &buffer, &cursor, &done, &abort, err_fn)?,
        other => anyhow::bail!("unsupported output sample format: {other:?}"),
    };

    Ok(PreparedTx {
        _stream: stream,
        cursor,
        done,
        abort,
        total,
        device_rate,
        deadline: None,
    })
}

#[allow(clippy::too_many_arguments)]
fn build_output<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    channels: usize,
    buffer: &Arc<Vec<f32>>,
    cursor: &Arc<AtomicUsize>,
    done: &Arc<AtomicBool>,
    abort: &Arc<AtomicBool>,
    err_fn: impl FnMut(cpal::StreamError) + Send + 'static,
) -> anyhow::Result<cpal::Stream>
where
    T: SizedSample + FromSample<f32>,
{
    let buffer = buffer.clone();
    let cursor = cursor.clone();
    let done = done.clone();
    let abort = abort.clone();
    let stream = device.build_output_stream(
        config,
        move |out: &mut [T], _| {
            // Stop TX: emit silence and report done so the engine drops PTT.
            if abort.load(Ordering::Relaxed) {
                done.store(true, Ordering::Relaxed);
                out.fill(T::from_sample(0.0f32));
                return;
            }
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn skip_cursor_converts_ms_to_device_samples() {
        // 100 ms at 48 kHz = 4800 samples.
        assert_eq!(skip_cursor(100, 48_000, 1_000_000), 4_800);
        // The normal case: no leading clip → start at sample 0.
        assert_eq!(skip_cursor(0, 48_000, 1_000_000), 0);
        // Negative is treated as zero, never a huge usize.
        assert_eq!(skip_cursor(-500, 48_000, 1_000_000), 0);
    }

    #[test]
    fn skip_cursor_clamps_to_total() {
        // A clip longer than the buffer starts at the end (plays silence), never
        // overruns the buffer.
        assert_eq!(skip_cursor(60_000, 48_000, 1_000), 1_000);
    }

    #[test]
    fn remaining_secs_measures_unplayed_audio() {
        // 48000 samples left at 48 kHz = 1.0 s.
        assert_eq!(remaining_secs(96_000, 48_000, 48_000), 1.0);
        // Past the end clamps to zero rather than going negative.
        assert_eq!(remaining_secs(48_000, 96_000, 48_000), 0.0);
        // Zero device rate can't divide-by-zero.
        assert!(remaining_secs(48_000, 0, 0).is_finite());
    }
}
