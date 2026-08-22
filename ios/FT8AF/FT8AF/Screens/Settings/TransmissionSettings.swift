import FT8DSP
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
                        .font(.ft8afMono(size: 17))
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

            // PTT Mode — only VOX works on iOS (see footer). A non-VOX value
            // from an older build is migrated to VOX when settings load
            // (`SettingsPersistence.load`); the coercion here is only a belt
            // and braces so the picker never shows a tag it doesn't offer.
            Section {
                Picker("PTT Mode", selection: Binding(
                    get: { settings.pttMode.coercedForIOS },
                    set: { settings.pttMode = $0 }
                )) {
                    ForEach(PttMode.selectableOnIOS) { mode in
                        Text(mode.rawValue).tag(mode)
                    }
                }
                .foregroundStyle(textPrimary)
            } header: {
                Text("PTT Control")
                    .foregroundStyle(textMuted)
            } footer: {
                Text("VOX keys the radio from the transmit audio. CAT/RTS/DTR PTT need a wired CAT connection, which iOS/iPadOS doesn't allow over USB. (A future Wi-Fi/rigctld bridge could add CAT PTT.)")
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
                            .font(.ft8afMono(size: 14))
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

            // Mode selection
            Section {
                Picker("Mode", selection: $settings.mode) {
                    ForEach(Mode.allCases) { m in
                        Text(m.rawValue).tag(m)
                    }
                }
                .foregroundStyle(textPrimary)
            } header: {
                Text("Mode")
                    .foregroundStyle(textMuted)
            } footer: {
                Text("FT4 receives and shows timing; FT4 transmit is not yet enabled. FT8 transmits normally.")
                    .font(.ft8afUI(size: 11))
                    .foregroundStyle(textFaint)
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

            // Auto-Sequence
            Section {
                Toggle(isOn: $settings.huntCallsCQ) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Hunt: Call CQ")
                            .foregroundStyle(textPrimary)
                        Text("Automatically call CQ when hunting")
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textFaint)
                    }
                }
                .tint(accent)

                Toggle(isOn: $settings.autoCallFollow) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Auto-Call Follow")
                            .foregroundStyle(textPrimary)
                        Text("Automatically respond to stations calling you")
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textFaint)
                    }
                }
                .tint(accent)

                Toggle(isOn: $settings.earlyDecode) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Early Decode")
                            .foregroundStyle(textPrimary)
                        Text("Start decoding before end of RX cycle")
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textFaint)
                    }
                }
                .tint(accent)

                Toggle(isOn: $settings.autoCQAfterQSO) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Auto-CQ After QSO")
                            .foregroundStyle(textPrimary)
                        Text("Resume calling CQ after completing a QSO")
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textFaint)
                    }
                }
                .tint(accent)
            } header: {
                Text("Auto-Sequence")
                    .foregroundStyle(textMuted)
            } footer: {
                Text("These settings control automatic QSO sequencing behavior during hunt mode and CQ operations.")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            // TX Safety
            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("TX Watchdog")
                            .foregroundStyle(textPrimary)
                        Text("Auto-stop TX after timeout")
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textFaint)
                    }
                    Spacer()
                    Picker("", selection: $settings.txWatchdogMin) {
                        Text("Off").tag(0)
                        ForEach(Array(stride(from: 5, through: 95, by: 10)), id: \.self) { min in
                            Text("\(min) min").tag(min)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(accent)
                    .frame(width: 100)
                }

                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Stop After")
                            .foregroundStyle(textPrimary)
                        Text("Max CQ attempts before stopping")
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textFaint)
                    }
                    Spacer()
                    Picker("", selection: $settings.stopAfterAttempts) {
                        Text("Off").tag(0)
                        ForEach(1...30, id: \.self) { n in
                            Text("\(n)").tag(n)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(accent)
                    .frame(width: 100)
                }

                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Tune Timeout")
                            .foregroundStyle(textPrimary)
                        Text("Max carrier time for the TUNE button")
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textFaint)
                    }
                    Spacer()
                    Picker("", selection: $settings.tuneTimeoutSec) {
                        ForEach([10, 15, 30, 60, 120], id: \.self) { sec in
                            Text("\(sec) s").tag(sec)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(accent)
                    .frame(width: 100)
                }
            } header: {
                Text("TX Safety")
                    .foregroundStyle(textMuted)
            } footer: {
                Text("Watchdog prevents runaway TX. Stop-after limits consecutive unanswered CQ calls. TUNE always stops itself at the timeout.")
                    .foregroundStyle(textFaint)
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
        .onChange(of: settings.mode) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.huntCallsCQ) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.autoCallFollow) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.earlyDecode) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.autoCQAfterQSO) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.txWatchdogMin) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.stopAfterAttempts) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.tuneTimeoutSec) { _, _ in SettingsPersistence.save(appState.settings) }
    }
}
