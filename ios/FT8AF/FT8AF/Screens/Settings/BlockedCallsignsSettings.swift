import SwiftUI

struct BlockedCallsignsSettings: View {
    @Environment(AppState.self) private var appState
    @State private var newCallsign = ""

    var body: some View {
        List {
            // Add new
            Section {
                HStack(spacing: 8) {
                    TextField("Enter callsign", text: $newCallsign)
                        .font(.system(size: 14, weight: .medium, design: .monospaced))
                        .foregroundStyle(textPrimary)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()

                    Button {
                        addCallsign()
                    } label: {
                        Image(systemName: "plus.circle.fill")
                            .font(.system(size: 20))
                            .foregroundStyle(newCallsign.isEmpty ? textFaint : accent)
                    }
                    .buttonStyle(.plain)
                    .disabled(newCallsign.isEmpty)
                }
            } header: {
                Text("Add Callsign").foregroundStyle(textMuted)
            } footer: {
                Text("Blocked callsigns will be hidden from the decode list")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            // List
            if !appState.settings.blockedCallsigns.isEmpty {
                Section {
                    ForEach(appState.settings.blockedCallsigns, id: \.self) { call in
                        HStack {
                            Text(call)
                                .font(.system(size: 14, weight: .bold, design: .monospaced))
                                .foregroundStyle(textPrimary)
                            Spacer()
                            Button {
                                removeCallsign(call)
                            } label: {
                                Image(systemName: "xmark.circle")
                                    .font(.system(size: 16))
                                    .foregroundStyle(statusBad)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                } header: {
                    Text("Blocked (\(appState.settings.blockedCallsigns.count))").foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)
            }
        }
        .scrollContentBackground(.hidden)
        .background(bgApp)
        .navigationTitle("Blocked Callsigns")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }

    private func addCallsign() {
        let call = newCallsign.uppercased().trimmingCharacters(in: .whitespaces)
        guard !call.isEmpty, !appState.settings.blockedCallsigns.contains(call) else { return }
        appState.settings.blockedCallsigns.append(call)
        SettingsPersistence.save(appState.settings)
        newCallsign = ""
    }

    private func removeCallsign(_ call: String) {
        appState.settings.blockedCallsigns.removeAll { $0 == call }
        SettingsPersistence.save(appState.settings)
    }
}
