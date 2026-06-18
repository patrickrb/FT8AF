import SwiftUI

struct RadioAudioSettings: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        @Bindable var settings = appState.settings

        List {
            // Rig model
            Section {
                Picker("Rig Model", selection: $settings.rigModel) {
                    ForEach(RigModel.allCases) { model in
                        Text(model.rawValue).tag(model)
                    }
                }
                .foregroundStyle(textPrimary)
            } header: {
                Text("Rig")
                    .foregroundStyle(textMuted)
            }
            .listRowBackground(bgSurface)

            // Audio input
            Section {
                HStack {
                    Text("Input Device")
                        .foregroundStyle(textPrimary)
                    Spacer()
                    Text("Built-in Microphone")
                        .font(.system(size: 14))
                        .foregroundStyle(textMuted)
                }
            } header: {
                Text("Audio")
                    .foregroundStyle(textMuted)
            } footer: {
                Text("Phase 2 will add audio input device selection with AVAudioEngine")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            // BLE device
            Section {
                HStack {
                    Text("BLE Device")
                        .foregroundStyle(textPrimary)
                    Spacer()
                    Text("Not connected")
                        .font(.system(size: 14))
                        .foregroundStyle(textFaint)
                }
                Button { } label: {
                    HStack {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .foregroundStyle(signal)
                        Text("Scan for BLE Devices")
                            .foregroundStyle(signal)
                    }
                }
            } header: {
                Text("Bluetooth")
                    .foregroundStyle(textMuted)
            } footer: {
                Text("Phase 3 will add CoreBluetooth BLE SPP rig control")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            // Connection status
            Section {
                HStack {
                    Text("Status")
                        .foregroundStyle(textPrimary)
                    Spacer()
                    HStack(spacing: 6) {
                        Circle()
                            .fill(statusColor)
                            .frame(width: 8, height: 8)
                        Text(appState.rig.connectionStatus.rawValue)
                            .font(.system(size: 14, design: .monospaced))
                            .foregroundStyle(textMuted)
                    }
                }
                HStack {
                    Text("Frequency")
                        .foregroundStyle(textPrimary)
                    Spacer()
                    Text(formatFreq(appState.rig.currentFreqHz))
                        .font(.system(size: 14, design: .monospaced))
                        .foregroundStyle(textMuted)
                }
            } header: {
                Text("Connection")
                    .foregroundStyle(textMuted)
            }
            .listRowBackground(bgSurface)
        }
        .scrollContentBackground(.hidden)
        .background(bgApp)
        .navigationTitle("Radio & Audio")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }

    private var statusColor: Color {
        switch appState.rig.connectionStatus {
        case .disconnected: return statusBad
        case .connecting:   return statusWarn
        case .connected:    return statusConfirmed
        }
    }

    private func formatFreq(_ hz: UInt64) -> String {
        let mhz = Double(hz) / 1_000_000
        return String(format: "%.3f MHz", mhz)
    }
}
