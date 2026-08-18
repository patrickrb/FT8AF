import Foundation

/// Pure timing math for the RX decode loop: at each poll tick it decides whether
/// to start decoding a slot, which slot's audio to decode, how large a trailing
/// window to take, and how long the reply TX must wait for the next cycle
/// boundary. Kept pure (no audio/actor state) so the decisions are unit-testable
/// — the loop in `LiveEngine.runDecodeLoop` is just a thin driver around it.
///
/// Two modes, gated by the operator's `earlyDecode` setting (mirrors Android
/// `GeneralVariables.earlyDecode` + `ModeProfile.earlyDecodeMillis`):
///
/// * **off** — decode fires at the slot boundary over the whole 15 s slot that
///   just ended (the historical iOS behavior). The reply keys up immediately in
///   the slot now starting.
/// * **on** — decode fires ~`earlyDecodeMillis` (13.5 s) into the in-progress
///   slot, over the ~13.5 s captured so far, so this slot's decodes are in hand
///   ~1.5 s before the boundary (matching Android's intent: the auto-sequencer
///   has the decode ready in time for the immediate next TX slot). The reply is
///   deferred to the boundary so it still keys up cleanly at the slot start
///   rather than mid-cycle.
///
/// The early window (~13.5 s) always exceeds the 12.64 s FT8 waveform, so an
/// on-time signal is never clipped; only signals whose DT pushes them past the
/// window end are truncated — the documented early-decode trade-off (the
/// full-slot recovery pass is a separate, future change).
public enum DecodeScheduler {
    /// FT8 early-decode window in ms (mirror `ModeProfile.FT8.earlyDecodeMillis`).
    public static let earlyDecodeMillis: Int64 = 13_500

    /// Length of the FT8 waveform in ms (79 symbols × 0.160 s = 12.64 s). The
    /// early window must stay at or above this so an on-time signal is never
    /// clipped.
    public static let ft8WaveformMillis: Int64 = 12_640

    /// Trailing 12 kHz samples to feed the decoder for a window of `windowMs`.
    public static func windowSampleCount(windowMs: Int64, sampleRate: Int) -> Int {
        max(0, Int(windowMs) * sampleRate / 1000)
    }

    /// One tick's decode decision.
    public struct Plan: Equatable {
        /// The slot whose audio is decoded.
        public let audioSlotID: Int64
        /// The slot the reply targets and the UI slot index derives from
        /// (always `audioSlotID + 1`, in both modes).
        public let replySlotID: Int64
        /// Trailing 12 kHz samples to take from the accumulator this tick.
        public let windowSamples: Int

        public init(audioSlotID: Int64, replySlotID: Int64, windowSamples: Int) {
            self.audioSlotID = audioSlotID
            self.replySlotID = replySlotID
            self.windowSamples = windowSamples
        }
    }

    /// Decide whether a decode should start on this tick.
    ///
    /// - Parameters:
    ///   - nowMs: wall-clock epoch ms.
    ///   - rxOffsetMs: capture-latency compensation (see `SlotClock.rxSlotID`).
    ///   - earlyDecode: the operator's toggle.
    ///   - lastDecodedAudioSlotID: highest audio slot already decoded (seed with
    ///     `SlotClock.rxSlotID(atUtcMs:rxOffsetMs:) - 1` so the first completed
    ///     slot still fires).
    ///   - sampleRate: decoder sample rate (12 kHz).
    /// - Returns: a `Plan` when a decode should start now, else nil.
    public static func plan(
        nowMs: Int64,
        rxOffsetMs: Int64,
        earlyDecode: Bool,
        lastDecodedAudioSlotID: Int64,
        sampleRate: Int
    ) -> Plan? {
        let currentSlot = SlotClock.rxSlotID(atUtcMs: nowMs, rxOffsetMs: rxOffsetMs)
        // RX-shifted position within the current slot (audio-aligned clock).
        let msInto = SlotClock.msIntoCycle(atUtcMs: nowMs - rxOffsetMs)

        if earlyDecode {
            // Decode the in-progress slot once it is ~13.5 s along.
            let audioSlot = currentSlot
            guard audioSlot > lastDecodedAudioSlotID, msInto >= earlyDecodeMillis else {
                return nil
            }
            // Window = audio captured since slot start (>= earlyDecodeMillis).
            // Anchoring at the elapsed time — not a fixed 13.5 s — keeps the
            // slot's leading edge (the Costas sync at t=0) in the buffer even
            // when the 200 ms poll fires a little past the 13.5 s mark.
            return Plan(
                audioSlotID: audioSlot,
                replySlotID: audioSlot + 1,
                windowSamples: windowSampleCount(windowMs: msInto, sampleRate: sampleRate)
            )
        } else {
            // Decode the slot that just ended, over the full 15 s slot.
            let audioSlot = currentSlot - 1
            guard audioSlot > lastDecodedAudioSlotID else { return nil }
            return Plan(
                audioSlotID: audioSlot,
                replySlotID: audioSlot + 1,
                windowSamples: windowSampleCount(windowMs: SlotClock.cycleMs, sampleRate: sampleRate)
            )
        }
    }

    /// Milliseconds the reply TX must wait so it keys up at the *start* of its
    /// slot instead of mid-cycle. With early decode the decode fires ~1.5 s
    /// before the boundary, so the reply waits out the remainder of the cycle;
    /// without it the decode already fires at the boundary, so there is no wait.
    ///
    /// - Parameter msIntoCycle: the unshifted `SlotClock.msIntoCycle(nowMs)` (TX
    ///   uses the unshifted clock, unlike the RX-aligned `plan` above).
    public static func replyBoundaryDelayMs(earlyDecode: Bool, msIntoCycle: Int64) -> Int64 {
        guard earlyDecode else { return 0 }
        return max(0, SlotClock.cycleMs - msIntoCycle)
    }
}
