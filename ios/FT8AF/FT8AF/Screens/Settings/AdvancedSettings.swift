import SwiftUI

struct AdvancedSettings: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        @Bindable var settings = appState.settings

        List {
            // Timing section
            Section {
                // PTT delay
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("PTT Delay")
                            .foregroundStyle(textPrimary)
                        Text("Delay before audio starts after PTT key")
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textFaint)
                    }
                    Spacer()
                    Picker("", selection: $settings.pttDelayMs) {
                        ForEach(Array(stride(from: 0, through: 190, by: 10)), id: \.self) { ms in
                            Text("\(ms) ms").tag(ms)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(accent)
                    .frame(width: 100)
                }

                // TX delay
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("TX Delay")
                            .foregroundStyle(textPrimary)
                        Text("Extra delay before TX audio playback")
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textFaint)
                    }
                    Spacer()
                    HStack(spacing: 4) {
                        TextField("0", value: $settings.txDelayMs, format: .number)
                            .font(.ft8afMono(size: 14))
                            .foregroundStyle(textPrimary)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numberPad)
                            .frame(width: 60)
                        Text("ms")
                            .font(.ft8afUI(size: 12))
                            .foregroundStyle(textFaint)
                    }
                }

                // Late-start tolerance
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Late Start Tolerance")
                            .foregroundStyle(textPrimary)
                        Text("Max ms clipped from a late TX before skipping it")
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textFaint)
                    }
                    Spacer()
                    HStack(spacing: 4) {
                        TextField("2360", value: $settings.lateStartToleranceMs, format: .number)
                            .font(.ft8afMono(size: 14))
                            .foregroundStyle(textPrimary)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numberPad)
                            .frame(width: 60)
                        Text("ms")
                            .font(.ft8afUI(size: 12))
                            .foregroundStyle(textFaint)
                    }
                }
            } header: {
                Text("Timing").foregroundStyle(textMuted)
            } footer: {
                Text("PTT delay compensates for rig keying latency. A TX starting more than 2.36 s into the 15-second cycle has its leading audio clipped so it still ends on time; late start tolerance is the most clipped audio allowed before the transmission is skipped for that cycle (0 = skip any clipped start).")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            // Decode section
            Section {
                Toggle(isOn: $settings.deepDecode) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Deep Decode")
                            .foregroundStyle(textPrimary)
                        Text("More LDPC iterations to recover weaker signals")
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textFaint)
                    }
                }
                .tint(accent)
            } header: {
                Text("Decode").foregroundStyle(textMuted)
            } footer: {
                Text("Deep decode raises the error-correction effort each slot, pulling out a few more weak signals for a small extra CPU cost. On by default; turn off to save battery on slower devices.")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            // WSJT-X UDP section
            Section {
                Toggle(isOn: $settings.udpEnabled) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Enable UDP broadcast").foregroundStyle(textPrimary)
                        Text("Send decodes, status & QSOs to companion apps")
                            .font(.ft8afUI(size: 11)).foregroundStyle(textFaint)
                    }
                }
                .tint(accent)

                HStack {
                    Text("Server host").foregroundStyle(textPrimary)
                    Spacer()
                    TextField("127.0.0.1", text: $settings.udpHost)
                        .font(.ft8afMono(size: 14))
                        .foregroundStyle(textPrimary)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .frame(width: 140)
                        .disabled(!settings.udpEnabled)
                }

                HStack {
                    Text("Server port").foregroundStyle(textPrimary)
                    Spacer()
                    TextField("2237", value: $settings.udpPort, format: .number)
                        .font(.ft8afMono(size: 14))
                        .foregroundStyle(textPrimary)
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.numberPad)
                        .frame(width: 80)
                        .disabled(!settings.udpEnabled)
                }

                Toggle(isOn: $settings.udpAcceptRequests) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Accept UDP requests").foregroundStyle(textPrimary)
                        Text("Let companion apps reply / halt / free-text")
                            .font(.ft8afUI(size: 11)).foregroundStyle(textFaint)
                    }
                }
                .tint(accent)
                .disabled(!settings.udpEnabled)
            } header: {
                Text("WSJT-X UDP").foregroundStyle(textMuted)
            } footer: {
                Text("Interoperate with GridTracker, JTAlert, N1MM+, Log4OM. Default 127.0.0.1:2237. Takes effect on the next receive session.")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            // Reset section
            Section {
                Button {
                    settings.pttDelayMs = 0
                    settings.txDelayMs = 0
                    settings.lateStartToleranceMs = 2360
                    SettingsPersistence.save(settings)
                } label: {
                    HStack {
                        Image(systemName: "arrow.counterclockwise")
                            .foregroundStyle(statusWarn)
                        Text("Reset to Defaults")
                            .foregroundStyle(statusWarn)
                    }
                }
            }
            .listRowBackground(bgSurface)
        }
        .scrollContentBackground(.hidden)
        .background(bgApp)
        .navigationTitle("Advanced")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .onChange(of: settings.pttDelayMs) { _, _ in SettingsPersistence.save(settings) }
        .onChange(of: settings.txDelayMs) { _, _ in SettingsPersistence.save(settings) }
        .onChange(of: settings.lateStartToleranceMs) { _, _ in SettingsPersistence.save(settings) }
        .onChange(of: settings.deepDecode) { _, _ in SettingsPersistence.save(settings) }
        .onChange(of: settings.udpEnabled) { _, _ in SettingsPersistence.save(settings) }
        .onChange(of: settings.udpHost) { _, _ in SettingsPersistence.save(settings) }
        .onChange(of: settings.udpPort) { _, _ in SettingsPersistence.save(settings) }
        .onChange(of: settings.udpAcceptRequests) { _, _ in SettingsPersistence.save(settings) }
    }
}
