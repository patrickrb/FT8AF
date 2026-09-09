import FT8Engine
import SwiftUI

/// Online logging integrations: Cloudlog/Wavelog server upload, QRZ.com
/// logbook upload, and PSK Reporter reception reports. Mirrors the Android
/// LoggingSettings groups.
struct LoggingSettings: View {
    @Environment(AppState.self) private var appState

    @State private var testingCloudlog = false
    @State private var testingQrz = false
    @State private var cloudlogResult: Bool?
    @State private var qrzResult: Bool?

    var body: some View {
        @Bindable var settings = appState.settings

        List {
            // MARK: Cloudlog / Wavelog
            Section {
                Toggle(isOn: $settings.cloudlogEnabled) {
                    Text("Upload QSOs")
                        .foregroundStyle(textPrimary)
                }
                .tint(accent)

                credentialField("Server URL", text: $settings.cloudlogUrl,
                                placeholder: "https://log.example.com/",
                                keyboard: .URL)
                credentialField("API Key", text: $settings.cloudlogApiKey,
                                placeholder: "cl64ab…", secure: true)
                credentialField("Station ID", text: $settings.cloudlogStationId,
                                placeholder: "1", keyboard: .numberPad)

                testConnectionRow(
                    isTesting: testingCloudlog,
                    result: cloudlogResult,
                    disabled: settings.cloudlogUrl.isEmpty || settings.cloudlogApiKey.isEmpty
                ) {
                    testingCloudlog = true
                    cloudlogResult = nil
                    let addr = settings.cloudlogUrl
                    let key = settings.cloudlogApiKey
                    Task {
                        let ok = await OnlineLogService.testCloudlog(
                            serverAddress: addr, apiKey: key)
                        cloudlogResult = ok
                        testingCloudlog = false
                        appState.toast.show(ok ? "Cloudlog: Pass" : "Cloudlog: Fail",
                                            icon: ok ? "checkmark.circle" : "xmark.circle")
                    }
                }
            } header: {
                Text("Cloudlog").foregroundStyle(textMuted)
            } footer: {
                Text("Upload each logged QSO to a Cloudlog, Wavelog or Nextlog server. The API key needs read/write rights.")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            // MARK: QRZ logbook
            Section {
                Toggle(isOn: $settings.qrzLogbookEnabled) {
                    Text("Upload QSOs")
                        .foregroundStyle(textPrimary)
                }
                .tint(accent)

                credentialField("API Key", text: $settings.qrzLogbookApiKey,
                                placeholder: "ABCD-1234-…", secure: true)

                testConnectionRow(
                    isTesting: testingQrz,
                    result: qrzResult,
                    disabled: settings.qrzLogbookApiKey.isEmpty
                ) {
                    testingQrz = true
                    qrzResult = nil
                    let key = settings.qrzLogbookApiKey
                    Task {
                        let ok = await OnlineLogService.testQrz(apiKey: key)
                        qrzResult = ok
                        testingQrz = false
                        appState.toast.show(ok ? "QRZ: Pass" : "QRZ: Fail",
                                            icon: ok ? "checkmark.circle" : "xmark.circle")
                    }
                }
            } header: {
                Text("QRZ Logbook").foregroundStyle(textMuted)
            } footer: {
                Text("Upload each logged QSO to your QRZ.com logbook. Use the Logbook API key from QRZ (requires an XML subscription), not your account password.")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            // MARK: PSK Reporter
            Section {
                Toggle(isOn: $settings.pskReporterEnabled) {
                    Text("Send reception reports")
                        .foregroundStyle(textPrimary)
                }
                .tint(accent)
            } header: {
                Text("PSK Reporter").foregroundStyle(textMuted)
            } footer: {
                Text("Report decoded stations to pskreporter.info every ~5 minutes so others can see your receive coverage. Requires your callsign and grid to be set.")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)
        }
        .scrollContentBackground(.hidden)
        .background(bgApp)
        .navigationTitle("Online Logging")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .onChange(of: settings.cloudlogEnabled) { _, _ in persist() }
        .onChange(of: settings.cloudlogUrl) { _, _ in cloudlogResult = nil; persist() }
        .onChange(of: settings.cloudlogApiKey) { _, _ in cloudlogResult = nil; persist() }
        .onChange(of: settings.cloudlogStationId) { _, _ in persist() }
        .onChange(of: settings.qrzLogbookEnabled) { _, _ in persist() }
        .onChange(of: settings.qrzLogbookApiKey) { _, _ in qrzResult = nil; persist() }
        .onChange(of: settings.pskReporterEnabled) { _, newValue in
            persist()
            // Opting out drops any queued spots and cancels the pending
            // flush task right away — nothing may be sent after opt-out.
            if !newValue {
                OnlineLogService.shared.flushPsk(appState: appState)
            }
        }
    }

    private func persist() {
        SettingsPersistence.save(appState.settings)
    }

    // MARK: - Rows

    private func credentialField(
        _ label: String, text: Binding<String>, placeholder: String,
        secure: Bool = false, keyboard: UIKeyboardType = .asciiCapable
    ) -> some View {
        HStack {
            Text(label)
                .foregroundStyle(textPrimary)
            Spacer()
            Group {
                if secure {
                    SecureField(placeholder, text: text)
                } else {
                    TextField(placeholder, text: text)
                        .keyboardType(keyboard)
                }
            }
            .font(.ft8afMono(size: 17))
            .multilineTextAlignment(.trailing)
            .foregroundStyle(textPrimary)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
        }
    }

    private func testConnectionRow(
        isTesting: Bool, result: Bool?, disabled: Bool, action: @escaping () -> Void
    ) -> some View {
        HStack {
            Button(action: action) {
                Text("Test Connection")
                    .foregroundStyle(disabled || isTesting ? textFaint : signal)
                    .fontWeight(.semibold)
            }
            .buttonStyle(.plain)
            .disabled(disabled || isTesting)

            Spacer()

            if isTesting {
                ProgressView()
                    .controlSize(.small)
                    .tint(accent)
            } else if let result {
                Text(result ? "Pass" : "Fail")
                    .font(.ft8afMono(size: 14, weight: .semibold))
                    .foregroundStyle(result ? statusConfirmed : statusBad)
            }
        }
    }
}
