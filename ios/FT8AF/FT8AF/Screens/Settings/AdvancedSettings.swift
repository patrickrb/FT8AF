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
                            .font(.system(size: 11))
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
                            .font(.system(size: 11))
                            .foregroundStyle(textFaint)
                    }
                    Spacer()
                    HStack(spacing: 4) {
                        TextField("0", value: $settings.txDelayMs, format: .number)
                            .font(.system(size: 14, design: .monospaced))
                            .foregroundStyle(textPrimary)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numberPad)
                            .frame(width: 60)
                        Text("ms")
                            .font(.system(size: 12))
                            .foregroundStyle(textFaint)
                    }
                }

                // Late-start tolerance
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Late Start Tolerance")
                            .foregroundStyle(textPrimary)
                        Text("Max ms into cycle before skipping TX")
                            .font(.system(size: 11))
                            .foregroundStyle(textFaint)
                    }
                    Spacer()
                    HStack(spacing: 4) {
                        TextField("2360", value: $settings.lateStartToleranceMs, format: .number)
                            .font(.system(size: 14, design: .monospaced))
                            .foregroundStyle(textPrimary)
                            .multilineTextAlignment(.trailing)
                            .keyboardType(.numberPad)
                            .frame(width: 60)
                        Text("ms")
                            .font(.system(size: 12))
                            .foregroundStyle(textFaint)
                    }
                }
            } header: {
                Text("Timing").foregroundStyle(textMuted)
            } footer: {
                Text("PTT delay compensates for rig keying latency. Late start tolerance sets how far into a 15-second cycle TX can still start (default 2360 ms = FT8 message duration).")
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
    }
}
