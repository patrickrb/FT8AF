import CFT8
import Foundation

/// Subtract-and-redecode: the iOS port of Android's deep-decode weak-signal
/// recovery (FT8SignalListener's subtract loop + ReBuildSignal.doSubtractSignal
/// + DeepDecodeBudget, all over ft8af_glue/ft8_subtract.c).
///
/// A single decode pass loses a weak signal sitting under a stronger one that
/// shares its tone bins (the FT8 Costas sync arrays are identical for every
/// signal, so co-channel transmissions reinforce into one candidate and the
/// louder message wins the demod). The fix — same as WSJT-X's `subtractft8` and
/// Android's loop — is: decode, reconstruct each decoded signal's exact GFSK
/// waveform, coherently subtract it from the retained sample buffer, recompute
/// the waterfall from the residual, and decode again; repeat while new messages
/// keep surfacing, bounded so it always terminates inside the 15 s slot.
///
/// The decision/merge/budget logic here is deliberately plain Swift so it is
/// unit-testable; the only native call is `ft8_subtract_signal_time`, the exact
/// C the Android JNI path uses.
public extension FT8Decoder {

    // MARK: Budget (mirror DeepDecodeBudget)

    /// Max subtract→redecode rounds after the initial pass. Each round costs one
    /// full-buffer STFT recompute + find_sync + decode; on device the whole loop
    /// shares the slot with the next slot's capture, so it must stay small.
    /// Empirically 2-3 rounds surface essentially everything a stronger signal
    /// masks; 4 is a safe ceiling that also caps a pathological (never-converging)
    /// buffer. Android bounds by wall-clock budget instead; an explicit round cap
    /// is the deterministic, testable equivalent here.
    static let subtractMaxRounds = 4

    /// Hard ceiling on total merged messages — a second, independent guarantee of
    /// termination if a corrupt residual keeps manufacturing "new" CRC-valid junk
    /// every round. A real slot never approaches this (FT8AF_MAX_CANDIDATES is
    /// 140); it exists purely so the loop cannot run away.
    static let subtractMaxMessages = 200

    /// Full deep-decode of the slot most recently `feedSlot`-ed: the initial
    /// pass, then — only when deep mode is on (`isDeep`) — the subtract-and-
    /// redecode loop. When deep is off this is exactly one `findSync`+`decodeAll`,
    /// so it is safe to call unconditionally in place of the single-pass path.
    ///
    /// Requires `feedSlot` to have run first (it retains the samples the loop
    /// subtracts from). Returns every message found across all passes, deduped by
    /// text with the highest SNR kept (mirrors Android addMsgToList/
    /// checkMessageSame).
    func decodeSlotDeep() -> [DecodedMessage] {
        findSync()
        var all = decodeAll()
        guard isDeep else { return all }
        runSubtractLoop(into: &all)
        return all
    }

    /// The subtract-and-redecode loop, factored out so it can be exercised
    /// directly. `all` enters holding the initial pass's decodes and leaves
    /// holding the full, merged, SNR-upgraded set. `lastPass` seeds the first
    /// round's subtraction targets (the messages just decoded).
    ///
    /// Termination is triple-guaranteed: (1) the round cap, (2) the message cap,
    /// and (3) convergence — a round that subtracts nothing new, or that surfaces
    /// no new message, ends the loop (Android's `while (msgs.size() > 0)` plus the
    /// per-transmission subtract-once rule).
    internal func runSubtractLoop(into all: inout [DecodedMessage]) {
        // Each transmission is subtracted at most once: the same message decodes
        // from several neighboring candidates and again from its own residual in
        // later passes; a second subtraction fits the fine sync to junk and
        // pollutes the residual (mirrors ft8_decoder_state.sub_done in the JNI
        // glue, which lives in the wrapper, not in the shared shim function).
        var subtracted = Set<[UInt8]>()
        var lastPass = all
        var round = 0

        while round < FT8Decoder.subtractMaxRounds,
              all.count < FT8Decoder.subtractMaxMessages {
            // Subtract every not-yet-subtracted signal the last pass decoded.
            var didSubtract = false
            for m in lastPass {
                if m.a91.allSatisfy({ $0 == 0 }) { continue } // no payload to re-encode
                if subtracted.contains(m.a91) { continue }
                if subtractSignalTime(a91: m.a91, freqHz: m.freqHz, timeSec: m.timeSec) {
                    subtracted.insert(m.a91)
                    didSubtract = true
                }
            }
            if !didSubtract { break } // nothing left to remove -> converged

            // Recompute the waterfall from the residual and decode again.
            rerunMonitor()
            findSync()
            let msgs = decodeAll()

            // Merge: new messages get appended, repeats upgrade the kept SNR.
            let newOnes = mergeDecodes(into: &all, msgs)
            lastPass = msgs
            round += 1
            if newOnes.isEmpty { break } // a pass with nothing new -> converged
        }
    }
}

/// Merge `newMsgs` into `all`, the slot-wide dedup scope, keyed on decoded text.
/// A message already present upgrades the kept copy's SNR when the new one is
/// higher; a genuinely new message is appended. Returns the messages that were
/// new (appended), mirroring how Android's addMsgToList leaves `newMsg` holding
/// only the fresh decodes. Free-standing and pure so it is unit-testable without
/// a monitor.
///
/// Port of FT8SignalListener.addMsgToList + checkMessageSame: dedup by
/// getMessageText, keep the higher SNR.
@discardableResult
func mergeDecodes(into all: inout [DecodedMessage], _ newMsgs: [DecodedMessage]) -> [DecodedMessage] {
    var added: [DecodedMessage] = []
    for m in newMsgs {
        // Empty text never dedups against itself (matches decodeAll, which keeps
        // every empty-text decode); treat it as always-new so it is not dropped.
        if !m.rawText.isEmpty,
           let idx = all.firstIndex(where: { $0.rawText == m.rawText }) {
            if m.snr > all[idx].snr { all[idx].snr = m.snr }
        } else {
            all.append(m)
            added.append(m)
        }
    }
    return added
}
