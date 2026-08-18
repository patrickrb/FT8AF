import Foundation

/// Platform-neutral mirror of the `.playAndRecord` category options the app
/// applies to `AVAudioSession`. Kept as a plain `OptionSet` here (no
/// AVFoundation) so the routing *decision* stays host-testable, exactly like
/// `AudioPortKind`; the app maps these onto `AVAudioSession.CategoryOptions`.
public struct PlayAndRecordOption: OptionSet, Sendable, Equatable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }

    /// Force output to the built-in speaker. Wanted on a bare phone (so RX is
    /// audible) but *not* when a USB audio interface is attached — it would
    /// steal TX audio away from the interface and the radio's Data-VOX never
    /// keys.
    public static let defaultToSpeaker = PlayAndRecordOption(rawValue: 1 << 0)
    /// Allow a Bluetooth HFP device to serve as input/output.
    public static let allowBluetooth = PlayAndRecordOption(rawValue: 1 << 1)
}

/// Pure routing policy for the shared audio session. The single decision the
/// TX path depends on: whether to pin output to the speaker.
public enum AudioSessionPolicy {
    /// Options for the `.playAndRecord` category given whether a USB audio
    /// device (e.g. a DigiRig) is currently connected/selected.
    ///
    /// - USB present: drop `.defaultToSpeaker` so output *follows* to the USB
    ///   device, feeding the radio so its Data-VOX keys on the TX tones.
    /// - No USB: keep `.defaultToSpeaker` so a bare iPhone/iPad still plays RX
    ///   audibly (don't regress non-radio users).
    ///
    /// `.allowBluetooth` is always included.
    public static func playAndRecordOptions(usbAudioConnected: Bool) -> PlayAndRecordOption {
        usbAudioConnected ? [.allowBluetooth] : [.defaultToSpeaker, .allowBluetooth]
    }
}
