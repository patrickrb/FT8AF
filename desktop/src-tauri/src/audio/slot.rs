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

    /// Copy a slot aligned to the slot *start*: the most recent `elapsed_samples`
    /// captured samples (the audio belonging to the current slot so far) placed at
    /// the FRONT of a `SLOT_SAMPLES` buffer, zero-padding the tail. The FT8
    /// waveform begins at the slot boundary, so it lands at sample 0 — the
    /// alignment the decoder expects (DT measured from the buffer start). This lets
    /// the decoder run as soon as the 12.64 s waveform is captured (~13 s into the
    /// cycle) instead of waiting for the full 15 s boundary, pulling decodes — and
    /// the operator's reply — a slot earlier (matches WSJT-X).
    ///
    /// At `elapsed_samples >= SLOT_SAMPLES` (a full slot) this matches `take_slot`:
    /// the most recent 15 s with the signal at the front.
    pub fn take_slot_from_start(&self, elapsed_samples: usize) -> Vec<f32> {
        let buf = self.inner.lock();
        let mut out = vec![0.0f32; SLOT_SAMPLES];
        let n = elapsed_samples.min(buf.len()).min(SLOT_SAMPLES);
        // The oldest of these n samples is the slot-start sample → position 0.
        for (i, s) in buf.iter().skip(buf.len() - n).enumerate() {
            out[i] = *s;
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn take_slot_from_start_front_aligns_partial_slot() {
        let acc = SlotAccumulator::new();
        // Simulate ~13 s of a slot: a 1.0 marker at the slot start, then a ramp.
        let elapsed = SAMPLE_RATE as usize * 13;
        let mut audio = vec![0.0f32; elapsed];
        audio[0] = 1.0; // slot-start sample
        audio[elapsed - 1] = 0.5; // most-recent sample
        acc.push(&audio);

        let slot = acc.take_slot_from_start(elapsed);
        assert_eq!(slot.len(), SLOT_SAMPLES);
        // Slot-start sample lands at index 0 (DT == 0 for the decoder).
        assert_eq!(slot[0], 1.0);
        // Most-recent captured sample lands at index elapsed-1, not at the end.
        assert_eq!(slot[elapsed - 1], 0.5);
        // The tail (after captured audio) is zero-padded, never noise.
        assert!(slot[elapsed..].iter().all(|&s| s == 0.0));
    }

    #[test]
    fn take_slot_from_start_full_slot_matches_take_slot() {
        let acc = SlotAccumulator::new();
        let audio: Vec<f32> = (0..SLOT_SAMPLES).map(|i| (i % 7) as f32).collect();
        acc.push(&audio);
        // A full slot's worth of elapsed samples reproduces the boundary copy.
        assert_eq!(acc.take_slot_from_start(SLOT_SAMPLES), acc.take_slot());
        // Asking for more than a slot is clamped, not an overrun.
        assert_eq!(acc.take_slot_from_start(SLOT_SAMPLES * 2), acc.take_slot());
    }
}
