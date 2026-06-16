//! Rolling buffer of 12 kHz mono samples, the desktop equivalent of
//! `HamRecorder.VoiceDataMonitor`: capture runs continuously and the scheduler
//! copies the most recent 15 s slot at each UTC boundary.

use parking_lot::Mutex;
use std::collections::VecDeque;

use crate::dsp::{SAMPLE_RATE, SLOT_SAMPLES};

pub struct SlotAccumulator {
    inner: Mutex<VecDeque<f32>>,
    cap: usize,
}

impl SlotAccumulator {
    pub fn new() -> Self {
        // Hold a little over one slot so a boundary copy always has a full slot.
        let cap = SLOT_SAMPLES + SAMPLE_RATE as usize; // ~16 s
        SlotAccumulator {
            inner: Mutex::new(VecDeque::with_capacity(cap)),
            cap,
        }
    }

    /// Append freshly captured 12 kHz samples, evicting the oldest beyond `cap`.
    pub fn push(&self, samples: &[f32]) {
        let mut buf = self.inner.lock();
        buf.extend(samples.iter().copied());
        while buf.len() > self.cap {
            buf.pop_front();
        }
    }

    /// Copy the most recent full slot (`SLOT_SAMPLES`), front-padding with silence
    /// if fewer samples have been captured so far.
    pub fn take_slot(&self) -> Vec<f32> {
        let buf = self.inner.lock();
        let mut out = vec![0.0f32; SLOT_SAMPLES];
        let n = buf.len().min(SLOT_SAMPLES);
        // most recent n samples land at the end of `out`
        let start_dst = SLOT_SAMPLES - n;
        for (i, s) in buf.iter().skip(buf.len() - n).enumerate() {
            out[start_dst + i] = *s;
        }
        out
    }

    /// Copy the most recent `n` samples (for the live waterfall FFT) without
    /// disturbing the buffer. Returns fewer than `n` only during warm-up.
    pub fn peek_recent(&self, n: usize) -> Vec<f32> {
        let buf = self.inner.lock();
        let len = buf.len();
        let take = n.min(len);
        buf.iter().skip(len - take).copied().collect()
    }

    pub fn len(&self) -> usize {
        self.inner.lock().len()
    }

    pub fn is_empty(&self) -> bool {
        self.inner.lock().is_empty()
    }

    pub fn clear(&self) {
        self.inner.lock().clear();
    }
}

impl Default for SlotAccumulator {
    fn default() -> Self {
        Self::new()
    }
}
