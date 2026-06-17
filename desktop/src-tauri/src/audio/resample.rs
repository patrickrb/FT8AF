//! Streaming sample-rate conversion to/from the FT8 codec's fixed 12 kHz rate.
//!
//! Desktop hardware rarely offers 12 kHz directly (Android forced it on
//! AudioRecord), so we resample the device's native rate. Uses rubato's
//! `FftFixedIn` with a fixed input chunk; when in/out rates match it's a no-op.

use rubato::{FftFixedIn, Resampler};

const CHUNK_IN: usize = 1024;
const SUB_CHUNKS: usize = 2;

pub struct MonoResampler {
    inner: Option<FftFixedIn<f32>>,
    pending: Vec<f32>,
    in_rate: u32,
    out_rate: u32,
}

impl MonoResampler {
    pub fn new(in_rate: u32, out_rate: u32) -> Self {
        let inner = if in_rate == out_rate {
            None
        } else {
            match FftFixedIn::<f32>::new(in_rate as usize, out_rate as usize, CHUNK_IN, SUB_CHUNKS, 1) {
                Ok(r) => Some(r),
                Err(e) => {
                    log::error!("failed to create resampler ({in_rate} -> {out_rate}): {e}");
                    None
                }
            }
        };
        MonoResampler {
            inner,
            pending: Vec::with_capacity(CHUNK_IN * 2),
            in_rate,
            out_rate,
        }
    }

    /// Push mono input samples (at `in_rate`); returns any 12 kHz output produced.
    pub fn process(&mut self, input: &[f32]) -> Vec<f32> {
        if self.inner.is_none() {
            // Rates match — legitimate identity pass-through.
            if self.in_rate == self.out_rate {
                return input.to_vec();
            }
            // Rates differ but the resampler failed to initialise — returning
            // un-resampled samples would feed wrong-rate data into downstream
            // DSP and produce incorrect decoding/timing.  Fail closed.
            return Vec::new();
        }
        self.pending.extend_from_slice(input);
        let resampler = self.inner.as_mut().unwrap();
        let mut out = Vec::new();
        let needed = resampler.input_frames_next();
        while self.pending.len() >= needed {
            let chunk: Vec<f32> = self.pending.drain(..needed).collect();
            if let Ok(mut produced) = resampler.process(&[chunk], None) {
                if let Some(ch) = produced.pop() {
                    out.extend_from_slice(&ch);
                }
            }
        }
        out
    }

    pub fn in_rate(&self) -> u32 {
        self.in_rate
    }
    pub fn out_rate(&self) -> u32 {
        self.out_rate
    }
}

/// One-shot resample of a whole buffer (used for TX: 12 kHz -> device rate).
pub fn resample_buffer(input: &[f32], in_rate: u32, out_rate: u32) -> Vec<f32> {
    if in_rate == out_rate || input.is_empty() {
        return input.to_vec();
    }
    let mut r = match FftFixedIn::<f32>::new(
        in_rate as usize,
        out_rate as usize,
        CHUNK_IN,
        SUB_CHUNKS,
        1,
    ) {
        Ok(r) => r,
        Err(e) => {
            log::error!("resample_buffer: failed to create resampler ({in_rate} -> {out_rate}): {e}");
            return Vec::new();
        }
    };
    let mut out = Vec::with_capacity(input.len() * out_rate as usize / in_rate as usize + CHUNK_IN);
    let mut pos = 0;
    loop {
        let needed = r.input_frames_next();
        if pos + needed > input.len() {
            // pad the final partial chunk with silence
            let mut chunk = input[pos..].to_vec();
            chunk.resize(needed, 0.0);
            if let Ok(mut produced) = r.process(&[chunk], None) {
                if let Some(ch) = produced.pop() {
                    out.extend_from_slice(&ch);
                }
            }
            break;
        }
        let chunk = input[pos..pos + needed].to_vec();
        pos += needed;
        if let Ok(mut produced) = r.process(&[chunk], None) {
            if let Some(ch) = produced.pop() {
                out.extend_from_slice(&ch);
            }
        }
    }
    out
}
