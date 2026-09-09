import FT8Audio
import SwiftUI

/// Time & clock-sync settings: an NTP "Sync now" (needs internet) plus a manual
/// ±5 s correction for operating offline. Both feed the unified whole-clock
/// offset the engine adds to every RX slot / TX key-up / logged-UTC read
/// (`ClockState` + `SettingsState.manualClockOffsetMs`). Mirrors Android's
/// TimeSyncSettings + TimeCorrection.
struct TimeSyncSettings: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        @Bindable var settings = appState.settings
        let clock = appState.clock

        List {
            // MARK: Health
            Section {
                HStack {
                    Text("Clock Sync")
                        .foregroundStyle(textPrimary)
                    Spacer()
                    ClockSyncIndicator(offsetSec: clock.dtOffsetSec)
                }
            } header: {
                Text("Status").foregroundStyle(textMuted)
            } footer: {
                Text("Derived from the mean DT of the stations you decode — a good proxy for your own clock offset. Green is in the FT8 sweet spot; amber or red means resync.")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            // MARK: NTP sync
            Section {
                Button {
                    Task { await syncNow() }
                } label: {
                    HStack {
                        if clock.isSyncing {
                            ProgressView().tint(accent)
                        } else {
                            Image(systemName: "clock.arrow.2.circlepath")
                                .foregroundStyle(accent)
                        }
                        Text(clock.isSyncing ? "Syncing…" : "Sync now")
                            .foregroundStyle(accent)
                            .fontWeight(.semibold)
                        Spacer()
                    }
                }
                .disabled(clock.isSyncing)

                if let date = clock.lastSyncDate {
                    HStack {
                        Text("NTP offset").foregroundStyle(textMuted)
                        Spacer()
                        Text(ClockOffset.formatMs(clock.ntpOffsetMs))
                            .font(.ft8afMono(size: 14))
                            .foregroundStyle(clock.ntpOffsetMs == 0 ? textPrimary : accent)
                    }
                    HStack {
                        Text("Last sync").foregroundStyle(textMuted)
                        Spacer()
                        Text(date.formatted(date: .omitted, time: .standard))
                            .font(.ft8afMono(size: 13))
                            .foregroundStyle(textFaint)
                    }
                }
                if let err = clock.lastSyncError {
                    Text(err)
                        .font(.ft8afUI(size: 13))
                        .foregroundStyle(statusBad)
                }
            } header: {
                Text("Network Time (\(SntpSyncService.defaultServer))").foregroundStyle(textMuted)
            } footer: {
                Text("Queries an SNTP time server and corrects the whole app clock — receive, transmit, and logged times all shift together. Needs internet.")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)

            // MARK: Manual correction
            Section {
                VStack(spacing: 14) {
                    Text(ClockOffset.formatMs(Int64(settings.manualClockOffsetMs)))
                        .font(.ft8afMono(size: 30, weight: .bold))
                        .foregroundStyle(settings.manualClockOffsetMs == 0 ? textPrimary : accent)
                        .frame(maxWidth: .infinity)

                    HStack(spacing: 8) {
                        stepButton("-0.5", deltaMs: -500)
                        stepButton("-0.1", deltaMs: -100)
                        stepButton("+0.1", deltaMs: 100)
                        stepButton("+0.5", deltaMs: 500)
                    }

                    Button {
                        setManual(0)
                    } label: {
                        Text("Reset to 0.0 s")
                            .font(.ft8afUI(size: 14, weight: .semibold))
                            .foregroundStyle(settings.manualClockOffsetMs == 0 ? textFaint : accent)
                            .frame(maxWidth: .infinity)
                    }
                    .disabled(settings.manualClockOffsetMs == 0)
                }
                .padding(.vertical, 4)
            } header: {
                Text("Manual Correction").foregroundStyle(textMuted)
            } footer: {
                Text("For operating offline where NTP can't reach a server. Range ±5 s. Positive shifts the clock forward. This moves transmit timing too, not just receive.")
                    .foregroundStyle(textFaint)
            }
            .listRowBackground(bgSurface)
        }
        .scrollContentBackground(.hidden)
        .background(bgApp)
        .navigationTitle("Time Sync")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }

    // MARK: - Actions

    private func stepButton(_ label: String, deltaMs: Int64) -> some View {
        Button {
            setManual(ClockOffset.stepManualMs(Int64(appState.settings.manualClockOffsetMs), byMs: deltaMs))
        } label: {
            Text(label)
                .font(.ft8afMono(size: 15, weight: .bold))
                .foregroundStyle(accent)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(borderStrong, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }

    private func setManual(_ ms: Int64) {
        appState.settings.manualClockOffsetMs = Int(ClockOffset.clampManualMs(ms))
        SettingsPersistence.save(appState.settings)
    }

    private func syncNow() async {
        appState.clock.isSyncing = true
        appState.clock.lastSyncError = nil
        let result = await SntpSyncService.fetchOffsetMs()
        appState.clock.isSyncing = false
        switch result {
        case .success(let offsetMs):
            appState.clock.ntpOffsetMs = offsetMs
            appState.clock.lastSyncDate = Date()
            appState.toast.show("Clock synced: \(ClockOffset.formatMs(offsetMs))", icon: "clock.badge.checkmark")
        case .failure(let err):
            // Leave the prior offset intact; just surface the error.
            appState.clock.lastSyncError = err.errorDescription ?? "Sync failed"
            appState.toast.show("Time sync failed", icon: "exclamationmark.triangle")
        }
    }
}
