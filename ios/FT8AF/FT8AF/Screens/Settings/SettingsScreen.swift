import FT8Audio
import FT8Engine
import SwiftUI

struct SettingsScreen: View {
    @Environment(AppState.self) private var appState
    @State private var showOperatorEdit = false

    var body: some View {
        @Bindable var settings = appState.settings

        NavigationStack {
            List {
                // Operator identity card
                Section {
                    Button { showOperatorEdit = true } label: {
                        OperatorIdentityCard(settings: settings)
                    }
                    .buttonStyle(.plain)
                } header: {
                    Text("Operator")
                        .foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)
                .listRowInsets(EdgeInsets(top: 8, leading: 12, bottom: 8, trailing: 12))

                // Radio & Audio section
                Section {
                    NavigationLink {
                        RadioAudioSettings()
                    } label: {
                        HStack {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                                .font(.ft8afUI(size: 14))
                                .foregroundStyle(accent)
                                .frame(width: 24)
                            Text("Radio & Audio")
                                .foregroundStyle(textPrimary)
                            Spacer()
                            Text(settings.rigModel.rawValue)
                                .font(.ft8afMono(size: 14))
                                .foregroundStyle(textMuted)
                        }
                    }
                } header: {
                    Text("Radio")
                        .foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)

                // Transmission section
                Section {
                    NavigationLink {
                        TransmissionSettings()
                    } label: {
                        HStack {
                            Image(systemName: "bolt.horizontal")
                                .font(.ft8afUI(size: 14))
                                .foregroundStyle(accent)
                                .frame(width: 24)
                            Text("Transmission")
                                .foregroundStyle(textPrimary)
                            Spacer()
                            Text("\(settings.txPowerWatts)W / \(settings.pttMode.rawValue)")
                                .font(.ft8afMono(size: 14))
                                .foregroundStyle(textMuted)
                        }
                    }
                } header: {
                    Text("Transmit")
                        .foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)

                // Decode Filters section
                Section {
                    NavigationLink {
                        DecodeFilterSettings()
                    } label: {
                        HStack {
                            Image(systemName: "line.3.horizontal.decrease.circle")
                                .font(.ft8afUI(size: 14))
                                .foregroundStyle(accent)
                                .frame(width: 24)
                            Text("Decode Filters")
                                .foregroundStyle(textPrimary)
                        }
                    }

                    NavigationLink {
                        BlockedCallsignsSettings()
                    } label: {
                        HStack {
                            Image(systemName: "hand.raised")
                                .font(.ft8afUI(size: 14))
                                .foregroundStyle(accent)
                                .frame(width: 24)
                            Text("Blocked Callsigns")
                                .foregroundStyle(textPrimary)
                            Spacer()
                            Text("\(settings.blockedCallsigns.count)")
                                .font(.ft8afMono(size: 14))
                                .foregroundStyle(textMuted)
                        }
                    }
                } header: {
                    Text("Decode")
                        .foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)

                // Logging section
                Section {
                    NavigationLink {
                        LoggingSettings()
                    } label: {
                        HStack {
                            Image(systemName: "icloud.and.arrow.up")
                                .font(.ft8afUI(size: 14))
                                .foregroundStyle(accent)
                                .frame(width: 24)
                            Text("Online Logging")
                                .foregroundStyle(textPrimary)
                            Spacer()
                            Text(onlineLoggingSummary)
                                .font(.ft8afMono(size: 14))
                                .foregroundStyle(textMuted)
                        }
                    }

                    Toggle(isOn: $settings.autoLog) {
                        HStack {
                            Image(systemName: "book")
                                .font(.ft8afUI(size: 14))
                                .foregroundStyle(accent)
                                .frame(width: 24)
                            Text("Auto-log QSOs")
                                .foregroundStyle(textPrimary)
                        }
                    }
                    .tint(accent)
                } header: {
                    Text("Logging")
                        .foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)

                // Advanced section
                Section {
                    NavigationLink {
                        TimeSyncSettings()
                    } label: {
                        HStack {
                            Image(systemName: "clock.arrow.2.circlepath")
                                .font(.ft8afUI(size: 14))
                                .foregroundStyle(accent)
                                .frame(width: 24)
                            Text("Time Sync")
                                .foregroundStyle(textPrimary)
                            Spacer()
                            Text(ClockHealth.offsetLabel(offsetSec: appState.clock.dtOffsetSec))
                                .font(.ft8afMono(size: 14))
                                .foregroundStyle(textMuted)
                        }
                    }

                    NavigationLink {
                        AdvancedSettings()
                    } label: {
                        HStack {
                            Image(systemName: "gearshape.2")
                                .font(.ft8afUI(size: 14))
                                .foregroundStyle(accent)
                                .frame(width: 24)
                            Text("Advanced")
                                .foregroundStyle(textPrimary)
                        }
                    }
                } header: {
                    Text("Advanced")
                        .foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)

                // About section
                Section {
                    NavigationLink {
                        AboutScreen()
                    } label: {
                        HStack {
                            Image(systemName: "info.circle")
                                .font(.ft8afUI(size: 14))
                                .foregroundStyle(accent)
                                .frame(width: 24)
                            Text("About")
                                .foregroundStyle(textPrimary)
                            Spacer()
                            Text(appVersion)
                                .font(.ft8afMono(size: 14))
                                .foregroundStyle(textFaint)
                        }
                    }
                } header: {
                    Text("App")
                        .foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)
            }
            .scrollContentBackground(.hidden)
            .background(bgApp)
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.large)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .sheet(isPresented: $showOperatorEdit) {
                OperatorEditSheet()
                    .presentationDetents([.medium])
                    .presentationDragIndicator(.visible)
            }
            .onChange(of: appState.settings.myCall) { _, _ in SettingsPersistence.save(appState.settings) }
            .onChange(of: appState.settings.myGrid) { _, _ in SettingsPersistence.save(appState.settings) }
            .onChange(of: appState.settings.autoLog) { _, _ in SettingsPersistence.save(appState.settings) }
        }
    }

    /// Short summary of enabled online-logging services for the nav row.
    private var onlineLoggingSummary: String {
        let s = appState.settings
        let parts = [
            s.cloudlogEnabled ? "CL" : nil,
            s.qrzLogbookEnabled ? "QRZ" : nil,
            s.pskReporterEnabled ? "PSK" : nil,
        ].compactMap { $0 }
        return parts.isEmpty ? "Off" : parts.joined(separator: " ")
    }

    private var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }
}

// MARK: - Operator Identity Card

private struct OperatorIdentityCard: View {
    let settings: SettingsState

    var body: some View {
        HStack(spacing: 12) {
            // Callsign avatar
            ZStack {
                RoundedRectangle(cornerRadius: 10)
                    .fill(accent.opacity(0.14))
                Text(settings.myCall.isEmpty ? "?" : String(settings.myCall.prefix(2)))
                    .font(.ft8afMono(size: 16, weight: .bold))
                    .foregroundStyle(accent)
            }
            .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 3) {
                Text(settings.myCall.isEmpty ? "No Callsign" : settings.myCall)
                    .font(.ft8afMono(size: 16, weight: .bold))
                    .foregroundStyle(settings.myCall.isEmpty ? textFaint : textPrimary)

                HStack(spacing: 8) {
                    if !settings.myGrid.isEmpty {
                        Label(settings.myGrid, systemImage: "mappin")
                            .font(.ft8afMono(size: 11, weight: .medium))
                            .foregroundStyle(textMuted)
                    }
                    Label(settings.rigModel.rawValue, systemImage: "antenna.radiowaves.left.and.right")
                        .font(.ft8afUI(size: 11, weight: .medium))
                        .foregroundStyle(textFaint)
                }
            }

            Spacer()

            Image(systemName: "pencil.circle")
                .font(.ft8afUI(size: 18))
                .foregroundStyle(textFaint)
        }
    }
}

// MARK: - Operator Edit Sheet

private struct OperatorEditSheet: View {
    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss
    @State private var callsign = ""
    @State private var grid = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Text("Callsign")
                            .foregroundStyle(textPrimary)
                        Spacer()
                        TextField("e.g. KD2OGR", text: $callsign)
                            .font(.ft8afMono(size: 17))
                            .multilineTextAlignment(.trailing)
                            .foregroundStyle(textPrimary)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                    }
                    HStack {
                        Text("Grid Square")
                            .foregroundStyle(textPrimary)
                        Spacer()
                        TextField("e.g. FN20", text: $grid)
                            .font(.ft8afMono(size: 17))
                            .multilineTextAlignment(.trailing)
                            .foregroundStyle(textPrimary)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                    }
                } header: {
                    Text("Operator Identity").foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)
            }
            .scrollContentBackground(.hidden)
            .background(bgApp)
            .navigationTitle("Edit Identity")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .foregroundStyle(accent)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        appState.settings.myCall = callsign.uppercased()
                        appState.settings.myGrid = grid.uppercased()
                        SettingsPersistence.save(appState.settings)
                        dismiss()
                    }
                    .foregroundStyle(accent)
                    .fontWeight(.bold)
                }
            }
            .onAppear {
                callsign = appState.settings.myCall
                grid = appState.settings.myGrid
            }
        }
    }
}
