import SwiftUI

struct AppTabView: View {
    @State private var selectedTab: FT8AFTab = .decode
    @State private var showFrequencyPicker = false
    @State private var showCelebration = false
    @Environment(AppState.self) private var appState

    /// Whether the current tab shows the TX control strip.
    private var showsTxStrip: Bool {
        selectedTab == .decode || selectedTab == .waterfall
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            // Screen content
            Group {
                switch selectedTab {
                case .decode:    DecodeScreen()
                case .waterfall: WaterfallScreen()
                case .logbook:   LogbookScreen()
                case .map:       MapScreen()
                case .pota:      PotaScreen()
                case .settings:  SettingsScreen()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            // Bottom chrome: ActiveQsoPanel + TxStrip + SlotTimer + TabBar
            VStack(spacing: 0) {
                // Panel also shows while HUNT is armed (silent listening) so
                // the "Hunting…" header state has somewhere to live.
                if showsTxStrip && (appState.tx.isActivated || appState.tx.huntEnabled) {
                    ActiveQsoPanel()
                }

                if showsTxStrip {
                    TxStrip(
                        onHunt: { appState.engine?.toggleHunt() },
                        onCallCQ: { appState.engine?.callCQ() },
                        onStop: { appState.engine?.stopTx() },
                        onToggleSlot: { appState.engine?.toggleSlotParity() },
                        onToggleTune: { appState.engine?.toggleTune() },
                        onOpenFrequencyPicker: { showFrequencyPicker = true },
                        onVolumeChange: { newVol in
                            appState.settings.txVolume = newVol
                            SettingsPersistence.save(appState.settings)
                            appState.toast.show("Volume: \(newVol)%", icon: "speaker.wave.2")
                        }
                    )
                }
                SlotTimerBar()
                TabBarView(selectedTab: $selectedTab)
            }

            // TX glow overlay
            if appState.tx.isTransmitting {
                TransmitGlow()
            }

            // QSO celebration overlay
            if showCelebration {
                QsoCelebration {
                    showCelebration = false
                }
            }

            // Toast overlay
            if let msg = appState.toast.currentMessage {
                ToastOverlay(message: msg, icon: appState.toast.currentIcon) {
                    appState.toast.dismiss()
                }
            }
        }
        .background(bgApp)
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showFrequencyPicker) {
            FrequencyPickerSheet()
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .onChange(of: appState.tx.qsoCompletedAt) { _, newVal in
            if newVal != nil {
                showCelebration = true
                // Clear the trigger so it can fire again next QSO.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                    appState.tx.qsoCompletedAt = nil
                }
            }
        }
    }
}

// MARK: - Custom Tab Bar

private struct TabBarView: View {
    @Binding var selectedTab: FT8AFTab

    var body: some View {
        HStack(spacing: 0) {
            ForEach(FT8AFTab.allCases) { tab in
                TabBarButton(
                    tab: tab,
                    isSelected: selectedTab == tab
                ) {
                    selectedTab = tab
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 30)
        .background(
            LinearGradient(
                colors: [.clear, bgApp.opacity(0.95)],
                startPoint: .top,
                endPoint: UnitPoint(x: 0.5, y: 0.3)
            )
        )
    }
}

private struct TabBarButton: View {
    let tab: FT8AFTab
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                ZStack {
                    if isSelected {
                        RoundedRectangle(cornerRadius: 14)
                            .fill(accent.opacity(0.14))
                            .frame(width: 40, height: 28)
                    }
                    Image(systemName: tab.icon)
                        .font(.system(size: 16, weight: isSelected ? .semibold : .regular))
                        .foregroundStyle(isSelected ? accent : textFaint)
                }
                .frame(height: 28)

                Text(tab.label)
                    .font(.system(size: 10.5, weight: isSelected ? .semibold : .medium))
                    .foregroundStyle(isSelected ? accent : textFaint)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
