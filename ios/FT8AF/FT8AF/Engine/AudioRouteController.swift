import AVFoundation
import FT8Audio
import Foundation
import Observation

/// One selectable audio input, identified by the session port UID (stable
/// while the device stays attached; the persisted preference matches on
/// port *name* instead, which survives replug/relaunch).
struct AudioInputOption: Identifiable, Equatable {
    let id: String        // AVAudioSessionPortDescription.uid
    let name: String      // raw port name, used for the persisted preference
    let label: String     // display label ("Digirig (USB)", "Built-in Microphone")
}

/// Watches the shared AVAudioSession for the Radio & Audio settings screen:
/// enumerates selectable inputs, tracks the current output route, and applies
/// the operator's preferred input. Output routing is not directly settable on
/// iOS — the screen pairs the current-route readout with the system
/// `AVRoutePickerView`.
@Observable @MainActor
final class AudioRouteController {
    var inputs: [AudioInputOption] = []
    var currentInputUid: String = ""
    var currentOutputLabel: String = "—"

    @ObservationIgnored private var observer: NSObjectProtocol?

    func start(preferredPortName: String) {
        refresh(preferredPortName: preferredPortName)
        observer = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.refresh(preferredPortName: preferredPortName)
            }
        }
    }

    func stop() {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = nil
    }

    func refresh(preferredPortName: String) {
        let session = AVAudioSession.sharedInstance()
        inputs = (session.availableInputs ?? []).map { port in
            AudioInputOption(id: port.uid, name: port.portName, label: Self.label(for: port))
        }
        // Re-apply the persisted preference when its port is present but not
        // active (e.g. the USB interface was replugged).
        if !preferredPortName.isEmpty,
           let wanted = (session.availableInputs ?? []).first(where: { $0.portName == preferredPortName }),
           session.currentRoute.inputs.first?.uid != wanted.uid {
            try? session.setPreferredInput(wanted)
        }
        currentInputUid = session.currentRoute.inputs.first?.uid
            ?? inputs.first?.id ?? ""
        currentOutputLabel = session.currentRoute.outputs.first.map(Self.label(for:)) ?? "—"
    }

    /// Selects an input for the shared session. Returns the chosen option's
    /// port name for persistence, or nil if the selection failed.
    func selectInput(uid: String) -> String? {
        let session = AVAudioSession.sharedInstance()
        guard let port = (session.availableInputs ?? []).first(where: { $0.uid == uid }) else {
            return nil
        }
        do {
            try session.setPreferredInput(port)
            currentInputUid = port.uid
            return port.portName
        } catch {
            return nil
        }
    }

    private static func label(for port: AVAudioSessionPortDescription) -> String {
        AudioPortLabel.label(kind: kind(of: port.portType), portName: port.portName)
    }

    private static func kind(of type: AVAudioSession.Port) -> AudioPortKind {
        switch type {
        case .builtInMic: return .builtInMic
        case .builtInSpeaker: return .speaker
        case .builtInReceiver: return .receiver
        case .headsetMic, .headphones, .lineIn, .lineOut: return .wired
        case .usbAudio: return .usb
        case .bluetoothHFP, .bluetoothA2DP, .bluetoothLE: return .bluetooth
        case .airPlay: return .airPlay
        case .carAudio: return .carAudio
        default: return .other
        }
    }
}
