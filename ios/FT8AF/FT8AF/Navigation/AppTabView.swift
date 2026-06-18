import SwiftUI

struct AppTabView: View {
    @State private var selectedTab: FT8AFTab = .decode
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

            // Bottom chrome: TxStrip (on decode/waterfall tabs) + SlotTimer + TabBar
            VStack(spacing: 0) {
                if showsTxStrip {
                    TxStrip(
                        onHunt: { appState.engine?.toggleHunt() },
                        onCallCQ: { appState.engine?.callCQ() },
                        onStop: { appState.engine?.stopTx() },
                        onToggleSlot: { appState.engine?.toggleSlotParity() }
                    )
                }
                SlotTimerBar()
                TabBarView(selectedTab: $selectedTab)
            }
        }
        .background(bgApp)
        .preferredColorScheme(.dark)
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
