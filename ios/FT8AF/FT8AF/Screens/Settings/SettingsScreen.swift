import FT8Engine
import SwiftUI

struct SettingsScreen: View {
    @Environment(AppState.self) private var appState
    @State private var showShareSheet = false
    @State private var adifString = ""

    var body: some View {
        @Bindable var settings = appState.settings

        NavigationStack {
            List {
                // Operator section
                Section {
                    HStack {
                        Text("Callsign")
                            .foregroundStyle(textPrimary)
                        Spacer()
                        TextField("e.g. KD2OGR", text: $settings.myCall)
                            .font(.system(.body, design: .monospaced))
                            .multilineTextAlignment(.trailing)
                            .foregroundStyle(textPrimary)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                    }
                    HStack {
                        Text("Grid Square")
                            .foregroundStyle(textPrimary)
                        Spacer()
                        TextField("e.g. FN20", text: $settings.myGrid)
                            .font(.system(.body, design: .monospaced))
                            .multilineTextAlignment(.trailing)
                            .foregroundStyle(textPrimary)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                    }
                } header: {
                    Text("Operator")
                        .foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)

                // Radio & Audio section
                Section {
                    NavigationLink {
                        RadioAudioSettings()
                    } label: {
                        HStack {
                            Text("Radio & Audio")
                                .foregroundStyle(textPrimary)
                            Spacer()
                            Text(settings.rigModel.rawValue)
                                .font(.system(size: 14, design: .monospaced))
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
                            Text("Transmission")
                                .foregroundStyle(textPrimary)
                            Spacer()
                            Text("\(settings.txPowerWatts)W / \(settings.pttMode.rawValue)")
                                .font(.system(size: 14, design: .monospaced))
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
                        Text("Decode Filters")
                            .foregroundStyle(textPrimary)
                    }
                } header: {
                    Text("Decode")
                        .foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)

                // Logging section
                Section {
                    Toggle(isOn: $settings.autoLog) {
                        Text("Auto-log QSOs")
                            .foregroundStyle(textPrimary)
                    }
                    .tint(accent)

                    Button {
                        adifString = Adif.export(appState.logbook.records)
                        showShareSheet = true
                    } label: {
                        HStack {
                            Text("Export ADIF")
                                .foregroundStyle(textPrimary)
                            Spacer()
                            Image(systemName: "square.and.arrow.up")
                                .foregroundStyle(textMuted)
                        }
                    }
                } header: {
                    Text("Logging")
                        .foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)

                // Advanced section
                Section {
                    Button { } label: {
                        HStack {
                            Text("Debug Log")
                                .foregroundStyle(textPrimary)
                            Spacer()
                            Image(systemName: "doc.text")
                                .foregroundStyle(textMuted)
                        }
                    }

                    HStack {
                        Text("Version")
                            .foregroundStyle(textPrimary)
                        Spacer()
                        Text("1.0.0")
                            .font(.system(size: 14, design: .monospaced))
                            .foregroundStyle(textFaint)
                    }
                } header: {
                    Text("Advanced")
                        .foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)
            }
            .scrollContentBackground(.hidden)
            .background(bgApp)
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.large)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .sheet(isPresented: $showShareSheet) {
                if let url = adifFileURL {
                    ShareSheet(items: [url])
                }
            }
            .onChange(of: appState.settings.myCall) { _, _ in SettingsPersistence.save(appState.settings) }
            .onChange(of: appState.settings.myGrid) { _, _ in SettingsPersistence.save(appState.settings) }
            .onChange(of: appState.settings.autoLog) { _, _ in SettingsPersistence.save(appState.settings) }
        }
    }

    private var adifFileURL: URL? {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("ft8af_log.adi")
        let content = Adif.export(appState.logbook.records)
        try? content.data(using: .utf8)?.write(to: tmp, options: .atomic)
        return tmp
    }
}

/// Minimal UIKit share sheet wrapper for ADIF export.
private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
