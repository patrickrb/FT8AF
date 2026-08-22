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

    /// Options to put the session into `.playAndRecord` with *before* the USB
    /// check can be trusted. `AVAudioSession.availableInputs` (and the current
    /// route's inputs) only reflect input-capable ports once the session is in
    /// an input-capable category, so under the default playback category a
    /// DigiRig that's already plugged in at launch is invisible. Bootstrapping
    /// with the no-speaker-pin options means the USB-present case then needs no
    /// second category change at all; the bare-device case applies
    /// `.defaultToSpeaker` right after.
    public static let bootstrapOptions: PlayAndRecordOption = [.allowBluetooth]

    /// Whether a USB audio interface (DigiRig etc.) is attached, judged from
    /// the session's selectable inputs and the active route's inputs/outputs —
    /// any of the three listing a USB port counts. Pure, so each path is
    /// host-testable; the app maps `AVAudioSession.Port` to `AudioPortKind`.
    public static func usbAudioPresent(
        availableInputs: [AudioPortKind],
        routeInputs: [AudioPortKind],
        routeOutputs: [AudioPortKind]
    ) -> Bool {
        availableInputs.contains(.usb)
            || routeInputs.contains(.usb)
            || routeOutputs.contains(.usb)
    }
}
