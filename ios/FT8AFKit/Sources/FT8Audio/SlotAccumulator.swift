import FT8DSP
import Foundation

/// Rolling buffer of 12 kHz mono samples — the iOS equivalent of the Android
/// `HamRecorder.VoiceDataMonitor` (port of desktop audio/slot.rs). Audio capture
/// runs continuously and pushes here from the real-time tap; the engine copies
/// the most recent 15 s slot at each UTC boundary. Thread-safe: the RT tap
/// `push`es while the engine thread `takeSlot`s / `peekRecent`s.
public final class SlotAccumulator {
    private let lock = NSLock()
    private var buf: [Float]
    private let cap: Int

    public init() {
        // Hold a little over one slot so a boundary copy always has a full slot.
        cap = FT8.slotSamples + Int(FT8.sampleRate) // ~16 s
        buf = []
        buf.reserveCapacity(cap)
    }

    /// Append freshly captured 12 kHz samples, evicting the oldest beyond `cap`.
    public func push(_ samples: [Float]) {
        lock.lock(); defer { lock.unlock() }
        buf.append(contentsOf: samples)
        if buf.count > cap {
            buf.removeFirst(buf.count - cap)
        }
    }

    /// Copy the most recent full slot (`FT8.slotSamples`), front-padding with
    /// silence if fewer samples have been captured so far (mirror take_slot).
    public func takeSlot() -> [Float] {
        lock.lock(); defer { lock.unlock() }
        var out = [Float](repeating: 0, count: FT8.slotSamples)
        let n = min(buf.count, FT8.slotSamples)
        let startDst = FT8.slotSamples - n // most recent n land at the end
        let srcStart = buf.count - n
        for i in 0..<n { out[startDst + i] = buf[srcStart + i] }
        return out
    }

    /// Copy the most recent `n` samples (for the live waterfall FFT) without
    /// disturbing the buffer. Returns fewer than `n` only during warm-up.
    public func peekRecent(_ n: Int) -> [Float] {
        lock.lock(); defer { lock.unlock() }
        let take = min(n, buf.count)
        if take == 0 { return [] }
        return Array(buf[(buf.count - take)...])
    }

    public var count: Int {
        lock.lock(); defer { lock.unlock() }
        return buf.count
    }

    public var isEmpty: Bool { count == 0 }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        buf.removeAll(keepingCapacity: true)
    }
}
