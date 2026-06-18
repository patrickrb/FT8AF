import SwiftUI

struct TransmissionSettings: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        @Bindable var settings = appState.settings

        List {
            // TX Power
            Section {
                HStack {
                    Text("TX Power")
                        .foregroundStyle(textPrimary)
                    Spacer()
                    TextField("Watts", value: $settings.txPowerWatts, format: .number)
                        .font(.system(.body, design: .monospaced))
                        .multilineTextAlignment(.trailing)
                        .foregroundStyle(textPrimary)
                        .keyboardType(.numberPad)
                        .frame(width: 60)
                    Text("W")
                        .foregroundStyle(textMuted)
                }
            } header: {
                Text("Power")
                    .foregroundStyle(textMuted)
            }
            .listRowBackground(bgSurface)

            // PTT Mode
            Section {
                Picker("PTT Mode", selection: $settings.pttMode) {
                    ForEach(PttMode.allCases) { mode in
                        Text(mode.rawValue).tag(mode)
                    }
                }
                .foregroundStyle(textPrimary)
            } header: {
                Text("PTT Control")
                    .foregroundStyle(textMuted)
            } footer: {
                Text("VOX: use audio-triggered PTT. CAT/RTS/DTR: hardware control via rig connection")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            // Volume
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("TX Volume")
                            .foregroundStyle(textPrimary)
                        Spacer()
                        Text("\(settings.txVolume)%")
                            .font(.system(size: 14, design: .monospaced))
                            .foregroundStyle(textMuted)
                    }
                    Slider(
                        value: Binding(
                            get: { Double(settings.txVolume) },
                            set: { settings.txVolume = Int($0) }
                        ),
                        in: 0...100,
                        step: 5
                    )
                    .tint(accent)
                }
            } header: {
                Text("Audio")
                    .foregroundStyle(textMuted)
            }
            .listRowBackground(bgSurface)

            // Band selection
            Section {
                Picker("Band", selection: $settings.band) {
                    ForEach(["160M","80M","40M","30M","20M","17M","15M","12M","10M","6M"], id: \.self) { band in
                        Text(band).tag(band)
                    }
                }
                .foregroundStyle(textPrimary)
            } header: {
                Text("Frequency")
                    .foregroundStyle(textMuted)
            }
            .listRowBackground(bgSurface)
        }
        .scrollContentBackground(.hidden)
        .background(bgApp)
        .navigationTitle("Transmission")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .onChange(of: settings.txPowerWatts) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.pttMode) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.txVolume) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.band) { _, _ in SettingsPersistence.save(appState.settings) }
    }
}
