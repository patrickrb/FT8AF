import SwiftUI

struct AboutScreen: View {
    @State private var showDebugLog = false
    @State private var debugLogContent = ""

    var body: some View {
        List {
            // App info
            Section {
                VStack(spacing: 12) {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .font(.ft8afUI(size: 40))
                        .foregroundStyle(accent)

                    Text("FT8AF")
                        .font(.ft8afUI(size: 22, weight: .bold))
                        .foregroundStyle(textPrimary)

                    Text("FT8 for Amateur Radio")
                        .font(.ft8afUI(size: 14))
                        .foregroundStyle(textMuted)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
            }
            .listRowBackground(bgSurface)

            // Version info
            Section {
                infoRow("Version", value: appVersion)
                infoRow("Build", value: buildNumber)
                infoRow("Platform", value: "iOS \(UIDevice.current.systemVersion)")
                infoRow("Device", value: UIDevice.current.model)
            } header: {
                Text("App Info").foregroundStyle(textMuted)
            }
            .listRowBackground(bgSurface)

            // Debug
            Section {
                Button {
                    loadDebugLog()
                    showDebugLog = true
                } label: {
                    HStack {
                        Image(systemName: "doc.text")
                            .foregroundStyle(accent)
                        Text("View Debug Log")
                            .foregroundStyle(textPrimary)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.ft8afUI(size: 12))
                            .foregroundStyle(textFaint)
                    }
                }

                Button {
                    clearDebugLog()
                } label: {
                    HStack {
                        Image(systemName: "trash")
                            .foregroundStyle(statusBad)
                        Text("Clear Debug Log")
                            .foregroundStyle(statusBad)
                    }
                }
            } header: {
                Text("Diagnostics").foregroundStyle(textMuted)
            }
            .listRowBackground(bgSurface)

            // Credits
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Based on FT8CN by bg7yoz")
                        .font(.ft8afUI(size: 13))
                        .foregroundStyle(textMuted)
                    Text("FT8 protocol by K9AN & K1JT")
                        .font(.ft8afUI(size: 13))
                        .foregroundStyle(textMuted)
                    Text("Open source under GPL-3.0")
                        .font(.ft8afUI(size: 13))
                        .foregroundStyle(textFaint)
                }
                .padding(.vertical, 4)
            } header: {
                Text("Credits").foregroundStyle(textMuted)
            }
            .listRowBackground(bgSurface)
        }
        .scrollContentBackground(.hidden)
        .background(bgApp)
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .sheet(isPresented: $showDebugLog) {
            DebugLogView(content: debugLogContent)
        }
    }

    private func infoRow(_ label: String, value: String) -> some View {
        HStack {
            Text(label)
                .foregroundStyle(textPrimary)
            Spacer()
            Text(value)
                .font(.ft8afMono(size: 14))
                .foregroundStyle(textMuted)
        }
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }

    private var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
    }

    private var debugLogURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("debug.log")
    }

    private func loadDebugLog() {
        debugLogContent = (try? String(contentsOf: debugLogURL, encoding: .utf8)) ?? "No debug log found."
    }

    private func clearDebugLog() {
        try? FileManager.default.removeItem(at: debugLogURL)
    }
}

// MARK: - Debug Log Viewer

private struct DebugLogView: View {
    let content: String
    @Environment(\.dismiss) private var dismiss
    @State private var showShareSheet = false

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(content)
                    .font(.ft8afMono(size: 11))
                    .foregroundStyle(textPrimary)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(bgApp)
            .navigationTitle("Debug Log")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                        .foregroundStyle(accent)
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showShareSheet = true
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                            .foregroundStyle(accent)
                    }
                }
            }
            .sheet(isPresented: $showShareSheet) {
                let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("ft8af_debug.log")
                let _ = try? content.data(using: .utf8)?.write(to: tmp, options: .atomic)
                DebugShareSheet(items: [tmp])
            }
        }
    }
}

private struct DebugShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
