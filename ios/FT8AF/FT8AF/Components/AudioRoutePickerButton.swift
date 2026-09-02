import AVKit
import SwiftUI

/// The system output-route picker (AirPlay/USB/Bluetooth/speaker). iOS does
/// not allow apps to set the output route directly — this button opens the
/// OS sheet, mirroring the Android "Audio output device" picker's role.
struct AudioRoutePickerButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.activeTintColor = UIColor(accent)
        view.tintColor = UIColor(textMuted)
        view.prioritizesVideoDevices = false
        return view
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
