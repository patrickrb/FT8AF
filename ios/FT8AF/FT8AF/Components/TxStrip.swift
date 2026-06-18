import SwiftUI

/// TX control bar shown at the bottom of Decode/Waterfall screens.
struct TxStrip: View {
    @Environment(AppState.self) private var appState

    var onHunt: () -> Void = {}
    var onCallCQ: () -> Void = {}
    var onStop: () -> Void = {}
    var onToggleSlot: () -> Void = {}

    var body: some View {
        let tx = appState.tx
        let settings = appState.settings

        VStack(spacing: 10) {
            // Info row: status + frequency/mode chips
            HStack {
                // Left: pulse dot + status label
                HStack(spacing: 6) {
                    PulseDot(color: tx.isTransmitting ? accent : signal)
                    Text(tx.isTransmitting ? "TX" : "RX")
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        .foregroundStyle(textPrimary)
                }

                Spacer()

                // Right: mode + frequency chips
                HStack(spacing: 6) {
                    TxChip(label: "FT8", color: accent)
                    TxChip(label: settings.band, color: textPrimary)
                }
            }

            // Action row: HUNT - CQ/STOP - TX slot
            HStack(spacing: 8) {
                // HUNT button
                ActionButton(
                    label: "HUNT",
                    icon: "target",
                    isActive: tx.huntEnabled,
                    activeColor: signal,
                    style: .secondary
                ) { onHunt() }

                // CQ / STOP button
                ActionButton(
                    label: tx.isActivated ? "STOP" : "CALL CQ",
                    icon: tx.isActivated ? "xmark" : "antenna.radiowaves.left.and.right",
                    isActive: true,
                    activeColor: tx.isActivated ? statusBad : accent,
                    style: .primary
                ) {
                    if tx.isActivated {
                        onStop()
                    } else {
                        onCallCQ()
                    }
                }

                // TX slot toggle
                ActionButton(
                    label: tx.slotParity == 0 ? "TX1" : "TX2",
                    icon: "arrow.up",
                    isActive: false,
                    activeColor: textMuted,
                    style: .secondary
                ) { onToggleSlot() }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(bgSurface)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(borderSubtle)
                .frame(height: 1)
        }
    }
}

// MARK: - Subviews

private struct PulseDot: View {
    let color: Color

    @State private var isPulsing = false

    var body: some View {
        ZStack {
            Circle()
                .fill(color.opacity(isPulsing ? 0 : 0.3))
                .frame(width: isPulsing ? 16 : 6, height: isPulsing ? 16 : 6)
            Circle()
                .fill(color)
                .frame(width: 6, height: 6)
        }
        .frame(width: 22, height: 22)
        .onAppear {
            withAnimation(.easeOut(duration: 1.5).repeatForever(autoreverses: false)) {
                isPulsing = true
            }
        }
    }
}

private struct TxChip: View {
    let label: String
    let color: Color

    var body: some View {
        Text(label)
            .font(.system(size: 11, weight: .semibold, design: .monospaced))
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(
                RoundedRectangle(cornerRadius: 6)
                    .fill(bgSurface3)
            )
    }
}

private enum ActionButtonStyle {
    case primary, secondary
}

private struct ActionButton: View {
    let label: String
    let icon: String
    let isActive: Bool
    let activeColor: Color
    let style: ActionButtonStyle
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                switch style {
                case .primary:
                    HStack(spacing: 8) {
                        Image(systemName: icon)
                            .font(.system(size: 14, weight: .semibold))
                        Text(label)
                            .font(.system(size: 15, weight: .bold, design: .monospaced))
                    }
                case .secondary:
                    VStack(spacing: 3) {
                        Image(systemName: icon)
                            .font(.system(size: 14, weight: .semibold))
                        Text(label)
                            .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    }
                }
            }
            .foregroundStyle(isActive ? (style == .primary ? bgApp : activeColor) : textMuted)
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(isActive ? activeColor.opacity(style == .primary ? 1.0 : 0.18) : bgSurface3)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(isActive && style == .secondary ? activeColor.opacity(0.5) : borderSubtle, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}
