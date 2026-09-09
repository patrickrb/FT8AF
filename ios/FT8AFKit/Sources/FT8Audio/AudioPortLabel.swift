import Foundation

/// Category of an audio port, mapped from `AVAudioSession.Port` by the app
/// layer. Kept as a plain enum here so label composition stays
/// platform-neutral and host-testable (FT8Audio has no AVFoundation).
public enum AudioPortKind: String, Sendable {
    case builtInMic
    case speaker
    case receiver
    case wired
    case usb
    case bluetooth
    case airPlay
    case carAudio
    case other
}

/// Display labels for audio input/output ports, matching the Android
/// device-picker convention of "<name> (<transport>)" with the transport
/// suffix dropped when the port name already says it.
public enum AudioPortLabel {
    public static func label(kind: AudioPortKind, portName: String) -> String {
        let name = portName.trimmingCharacters(in: .whitespaces)
        switch kind {
        case .builtInMic:
            return "Built-in Microphone"
        case .speaker:
            return "Speaker"
        case .receiver:
            return "Receiver"
        case .wired:
            return name.isEmpty ? "Wired" : name
        case .usb:
            return suffixed(name, transport: "USB", fallback: "USB Audio")
        case .bluetooth:
            return suffixed(name, transport: "Bluetooth", fallback: "Bluetooth Audio")
        case .airPlay:
            return suffixed(name, transport: "AirPlay", fallback: "AirPlay")
        case .carAudio:
            return suffixed(name, transport: "CarPlay", fallback: "CarPlay")
        case .other:
            return name.isEmpty ? "Unknown Device" : name
        }
    }

    private static func suffixed(_ name: String, transport: String, fallback: String) -> String {
        if name.isEmpty { return fallback }
        if name.range(of: transport, options: .caseInsensitive) != nil { return name }
        return "\(name) (\(transport))"
    }
}
