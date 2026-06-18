import SwiftUI

struct DecodeFilterSettings: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        @Bindable var settings = appState.settings

        List {
            Section {
                Toggle(isOn: $settings.showOnlyCQ) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Show Only CQ")
                            .foregroundStyle(textPrimary)
                        Text("Hide QSO traffic, show only CQ calls")
                            .font(.system(size: 12))
                            .foregroundStyle(textFaint)
                    }
                }
                .tint(accent)

                Toggle(isOn: $settings.dxOnly) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("DX Only")
                            .foregroundStyle(textPrimary)
                        Text("Hide domestic stations, show only DX")
                            .font(.system(size: 12))
                            .foregroundStyle(textFaint)
                    }
                }
                .tint(accent)
            } header: {
                Text("Filters")
                    .foregroundStyle(textMuted)
            }
            .listRowBackground(bgSurface)

            Section {
                highlightRow("New DXCC", description: "Purple highlight for unworked DXCC entities", color: statusNew)
                highlightRow("New Grid", description: "Highlight for unworked grid squares", color: signal)
                highlightRow("POTA Activators", description: "Green pill for spotted park activators", color: statusConfirmed)
                highlightRow("Worked Stations", description: "Cyan indicator for previously worked calls", color: statusWorked)
            } header: {
                Text("Highlights")
                    .foregroundStyle(textMuted)
            } footer: {
                Text("Highlight rules take effect in Phase 4 when the logbook database is integrated")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            Section {
                HStack {
                    Text("Continent Filter")
                        .foregroundStyle(textPrimary)
                    Spacer()
                    Text("All")
                        .foregroundStyle(textMuted)
                }
                HStack {
                    Text("Blocked Callsigns")
                        .foregroundStyle(textPrimary)
                    Spacer()
                    Text("0")
                        .foregroundStyle(textMuted)
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
    }

    private func highlightRow(_ title: String, description: String, color: Color) -> some View {
        HStack(spacing: 10) {
            Circle()
                .fill(color)
                .frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .foregroundStyle(textPrimary)
                Text(description)
                    .font(.system(size: 12))
                    .foregroundStyle(textFaint)
            }
        }
    }
}
