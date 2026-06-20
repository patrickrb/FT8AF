import SwiftUI

private let allBands = ["160M","80M","40M","30M","20M","17M","15M","12M","10M","6M"]

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

                // Spectrum width
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Spectrum Width")
                            .foregroundStyle(textPrimary)
                        Text("Decode bandwidth")
                            .font(.system(size: 11))
                            .foregroundStyle(textFaint)
                    }
                    Spacer()
                    Picker("", selection: $settings.spectrumWidthHz) {
                        ForEach([2500, 3000, 4000, 5000], id: \.self) { hz in
                            Text("\(hz) Hz").tag(hz)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(accent)
                    .frame(width: 110)
                }
            } header: {
                Text("Audio")
                    .foregroundStyle(textMuted)
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

            // Enabled Bands
            Section {
                ForEach(allBands, id: \.self) { band in
                    Toggle(isOn: Binding(
                        get: { settings.enabledBands.contains(band) },
                        set: { enabled in
                            if enabled {
                                if !settings.enabledBands.contains(band) {
                                    settings.enabledBands.append(band)
                                }
                            } else {
                                settings.enabledBands.removeAll { $0 == band }
                            }
                            SettingsPersistence.save(settings)
                        }
                    )) {
                        HStack(spacing: 8) {
                            RoundedRectangle(cornerRadius: 2)
                                .fill(bandColor(for: band))
                                .frame(width: 3, height: 18)
                            Text(band)
                                .font(.system(size: 14, weight: .medium, design: .monospaced))
                                .foregroundStyle(textPrimary)
                            Spacer()
                            Text(bandFrequency(band))
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(textFaint)
                        }
                    }
                    .tint(accent)
                }
            } header: {
                Text("Enabled Bands (\(settings.enabledBands.count) of \(allBands.count))")
                    .foregroundStyle(textMuted)
            } footer: {
                Text("Disabled bands are excluded from band cycling and hunt mode.")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)
        }
        .scrollContentBackground(.hidden)
        .background(bgApp)
        .navigationTitle("Radio & Audio")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .onChange(of: settings.rigModel) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.spectrumWidthHz) { _, _ in SettingsPersistence.save(appState.settings) }
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

    private func bandFrequency(_ band: String) -> String {
        switch band {
        case "160M": return "1.840"
        case "80M":  return "3.573"
        case "40M":  return "7.074"
        case "30M":  return "10.136"
        case "20M":  return "14.074"
        case "17M":  return "18.100"
        case "15M":  return "21.074"
        case "12M":  return "24.915"
        case "10M":  return "28.074"
        case "6M":   return "50.313"
        default:     return ""
        }
    }
}
