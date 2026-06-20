import FT8DSP
import Foundation

/// Rolling buffer of 12 kHz mono samples — the iOS equivalent of the Android
/// `HamRecorder.VoiceDataMonitor` (port of desktop audio/slot.rs). Audio capture
/// runs continuously and pushes here from the real-time tap; the engine copies
/// the most recent 15 s slot at each UTC boundary.
///
/// Backed by a fixed-size **circular buffer** so `push` is O(samples pushed) and
/// never memmoves the whole buffer — important on the RT audio thread (the
/// desktop uses a VecDeque for the same reason; a plain Array with `removeFirst`
/// would shift ~16 s of samples on every push once full). Thread-safe: the RT
/// tap `push`es while the engine thread `takeSlot`s / `peekRecent`s.
public final class SlotAccumulator: @unchecked Sendable {
    private let lock = NSLock()
    private let cap: Int
    private var storage: [Float] // length == cap
    private var head = 0         // index of the oldest valid sample
    private var filled = 0       // number of valid samples (0...cap)

    public init() {
        // Hold a little over one slot so a boundary copy always has a full slot.
        cap = FT8.slotSamples + Int(FT8.sampleRate) // ~16 s
        storage = [Float](repeating: 0, count: cap)
    }

    /// Append freshly captured 12 kHz samples, evicting the oldest beyond `cap`.
    public func push(_ samples: [Float]) {
        samples.withUnsafeBufferPointer { push($0) }
    }

    /// Pointer overload of `push` — lets the real-time audio tap hand samples
    /// over directly from the converter's channel data without first copying
    /// into a freshly allocated `[Float]` on every callback.
    public func push(_ samples: UnsafeBufferPointer<Float>) {
        let m = samples.count
        if m == 0 { return }
        guard let src = samples.baseAddress else { return }
        lock.lock(); defer { lock.unlock() }

        if m >= cap {
            // Only the most recent `cap` samples can be retained.
            let start = m - cap
            for i in 0..<cap { storage[i] = src[start + i] }
            head = 0
            filled = cap
            return
        }

        // Write `m` samples just past the newest, wrapping at the end (branch, not
        // modulo, to keep the RT path cheap).
        var write = head + filled
        if write >= cap { write -= cap }
        for i in 0..<m {
            storage[write] = src[i]
            write += 1
            if write == cap { write = 0 }
        }

        let newFilled = filled + m
        if newFilled > cap {
            head = (head + (newFilled - cap)) % cap // drop the overrun oldest
            filled = cap
        } else {
            filled = newFilled
        }
    }

    /// Copy the most recent full slot (`FT8.slotSamples`), front-padding with
    /// silence if fewer samples have been captured so far (mirror take_slot).
    public func takeSlot() -> [Float] {
        lock.lock(); defer { lock.unlock() }
        var out = [Float](repeating: 0, count: FT8.slotSamples)
        let n = min(filled, FT8.slotSamples)
        let startDst = FT8.slotSamples - n // most recent n land at the end
        copyRecent(n, into: &out, dstOffset: startDst)
        return out
    }

    /// Copy the most recent `n` samples (for the live waterfall FFT) without
    /// disturbing the buffer. Returns fewer than `n` only during warm-up, and an
    /// empty array for `n <= 0`.
    public func peekRecent(_ n: Int) -> [Float] {
        lock.lock(); defer { lock.unlock() }
        let take = max(0, min(n, filled))
        if take == 0 { return [] }
        var out = [Float](repeating: 0, count: take)
        copyRecent(take, into: &out, dstOffset: 0)
        return out
    }

    public var count: Int {
        lock.lock(); defer { lock.unlock() }
        return filled
    }

    public var isEmpty: Bool { count == 0 }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        head = 0
        filled = 0
    }

    /// Copy the most recent `n` valid samples (n <= filled) into `out` starting at
    /// `dstOffset`, in chronological order. Caller holds `lock`.
    private func copyRecent(_ n: Int, into out: inout [Float], dstOffset: Int) {
        var src = (head + filled - n) % cap
        for i in 0..<n {
            out[dstOffset + i] = storage[src]
            src += 1
            if src == cap { src = 0 }
        }
    }
}
