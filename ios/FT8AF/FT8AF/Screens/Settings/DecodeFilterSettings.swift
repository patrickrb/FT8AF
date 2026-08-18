import SwiftUI

struct DecodeFilterSettings: View {
    @Environment(AppState.self) private var appState

    /// Continent picker options: "All" plus the seven continent codes used by the
    /// decode filter (matches Android's continent codes).
    private static let continentOptions = ["All", "NA", "SA", "EU", "AF", "AS", "OC", "AN"]

    private func continentName(_ code: String) -> String {
        switch code {
        case "NA": return "North America"
        case "SA": return "South America"
        case "EU": return "Europe"
        case "AF": return "Africa"
        case "AS": return "Asia"
        case "OC": return "Oceania"
        case "AN": return "Antarctica"
        default: return "All"
        }
    }

    var body: some View {
        @Bindable var settings = appState.settings

        List {
            Section {
                Toggle(isOn: $settings.showOnlyCQ) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Show Only CQ")
                            .foregroundStyle(textPrimary)
                        Text("Hide QSO traffic, show only CQ calls")
                            .font(.ft8afUI(size: 12))
                            .foregroundStyle(textFaint)
                    }
                }
                .tint(accent)

                Toggle(isOn: $settings.dxOnly) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("DX Only")
                            .foregroundStyle(textPrimary)
                        Text("Hide stations on your own continent")
                            .font(.ft8afUI(size: 12))
                            .foregroundStyle(textFaint)
                    }
                }
                .tint(accent)

                Picker(selection: $settings.continentFilter) {
                    ForEach(Self.continentOptions, id: \.self) { code in
                        Text(continentName(code)).tag(code)
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Continent Filter")
                            .foregroundStyle(textPrimary)
                        Text("Show only stations from one continent")
                            .font(.ft8afUI(size: 12))
                            .foregroundStyle(textFaint)
                    }
                }
                .tint(textMuted)
            } header: {
                Text("Filters")
                    .foregroundStyle(textMuted)
            }
            .listRowBackground(bgSurface)

            Section {
                highlightToggle(
                    "New DXCC", description: "Purple pill for unworked DXCC entities",
                    color: statusNew, isOn: $settings.highlightNewDxcc
                )
                highlightToggle(
                    "New State", description: "Teal pill for unworked US states (WAS)",
                    color: statusState, isOn: $settings.highlightNewState
                )
                highlightToggle(
                    "New Grid", description: "Yellow pill for unworked grid squares",
                    color: statusWarn, isOn: $settings.highlightNewGrid
                )
                highlightToggle(
                    "New Band", description: "Cyan pill for stations never worked on this band",
                    color: signal, isOn: $settings.highlightNewBand
                )
                highlightToggle(
                    "Worked Stations", description: "Cyan pill for previously worked calls",
                    color: statusWorked, isOn: $settings.highlightWorked
                )
            } header: {
                Text("Highlights")
                    .foregroundStyle(textMuted)
            } footer: {
                Text("Highlights compare each decode against your logbook. Disabled categories fall through to the next in priority.")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            Section {
                Picker(selection: $settings.distanceInMiles) {
                    Text("Kilometers").tag(false)
                    Text("Miles").tag(true)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Distance Unit")
                            .foregroundStyle(textPrimary)
                        Text("Unit for QSO-path distances in the decode list")
                            .font(.ft8afUI(size: 12))
                            .foregroundStyle(textFaint)
                    }
                }
                .tint(textMuted)

                NavigationLink {
                    BlockedCallsignsSettings()
                } label: {
                    HStack {
                        Text("Blocked Callsigns")
                            .foregroundStyle(textPrimary)
                        Spacer()
                        Text("\(appState.settings.blockedCallsigns.count)")
                            .font(.ft8afMono(size: 14, weight: .medium))
                            .foregroundStyle(textMuted)
                    }
                }
            } header: {
                Text("Advanced")
                    .foregroundStyle(textMuted)
            }
            .listRowBackground(bgSurface)
        }
        .scrollContentBackground(.hidden)
        .background(bgApp)
        .navigationTitle("Decode Filters")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .onChange(of: settings.showOnlyCQ) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.dxOnly) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.continentFilter) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.highlightNewDxcc) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.highlightNewState) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.highlightNewGrid) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.highlightNewBand) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.highlightWorked) { _, _ in SettingsPersistence.save(appState.settings) }
        .onChange(of: settings.distanceInMiles) { _, _ in SettingsPersistence.save(appState.settings) }
    }

    private func highlightToggle(
        _ title: String, description: String, color: Color, isOn: Binding<Bool>
    ) -> some View {
        Toggle(isOn: isOn) {
            HStack(spacing: 10) {
                Circle()
                    .fill(color)
                    .frame(width: 10, height: 10)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .foregroundStyle(textPrimary)
                    Text(description)
                        .font(.ft8afUI(size: 12))
                        .foregroundStyle(textFaint)
                }
            }
        }
        .tint(accent)
    }
}
