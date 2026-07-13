import FT8Engine
import SwiftUI
import UIKit

/// TX control bar shown at the bottom of Decode/Waterfall screens.
struct TxStrip: View {
    @Environment(AppState.self) private var appState

    var onHunt: () -> Void = {}
    var onCallCQ: () -> Void = {}
    var onStop: () -> Void = {}
    var onToggleSlot: () -> Void = {}
    var onToggleTune: () -> Void = {}
    var onOpenFrequencyPicker: () -> Void = {}
    var onVolumeChange: (Int) -> Void = { _ in }

    var body: some View {
        let tx = appState.tx
        let settings = appState.settings

        VStack(spacing: 10) {
            // Info row: status + frequency/mode chips + expand chevron
            HStack {
                // Left: pulse dot + status label
                HStack(spacing: 6) {
                    PulseDot(color: tx.isTransmitting ? accent : signal)
                    Text(tx.isTransmitting ? "TX" : "RX")
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        .foregroundStyle(textPrimary)
                }

                Spacer()

                // CAT status chip
                CatChip(status: appState.rig.connectionStatus)

                // Right: mode + frequency + tune chips
                HStack(spacing: 6) {
                    // Mode pill — static "FT8" (FT4/FT2 deferred; plain,
                    // non-interactive).
                    TxChip(label: "FT8", color: accent)

                    // Tappable frequency/band chip
                    Button { onOpenFrequencyPicker() } label: {
                        TxChip(label: settings.band, color: textPrimary)
                    }
                    .buttonStyle(.plain)

                    // TUNE pill: latches a steady carrier at the TX audio
                    // frequency; red with a countdown while active. Locked off
                    // while the sequencer is armed/transmitting — tune and FT8
                    // TX are mutually exclusive.
                    TunePill(
                        isTuning: tx.isTuning,
                        remainingSec: tx.tuneRemainingSec,
                        locked: tx.isActivated || tx.isTransmitting,
                        action: onToggleTune
                    )
                }

                // Expand/collapse chevron
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        appState.tx.expanded.toggle()
                    }
                } label: {
                    Image(systemName: tx.expanded ? "chevron.down" : "chevron.up")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(textMuted)
                        .frame(width: 28, height: 28)
                        .background(
                            RoundedRectangle(cornerRadius: 6)
                                .fill(bgSurface3)
                        )
                }
                .buttonStyle(.plain)
            }

            // Expanded section: volume slider
            if tx.expanded {
                VStack(spacing: 8) {
                    HStack(spacing: 8) {
                        Text("VOL")
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .foregroundStyle(textFaint)

                        // Step down
                        Button {
                            let newVol = max(0, settings.txVolume - 5)
                            onVolumeChange(newVol)
                        } label: {
                            Image(systemName: "minus")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(textMuted)
                                .frame(width: 24, height: 24)
                                .background(Circle().fill(bgSurface3))
                        }
                        .buttonStyle(.plain)

                        Slider(
                            value: Binding(
                                get: { Double(settings.txVolume) },
                                set: { onVolumeChange(Int($0)) }
                            ),
                            in: 0...100,
                            step: 1
                        )
                        .tint(accent)

                        // Step up
                        Button {
                            let newVol = min(100, settings.txVolume + 5)
                            onVolumeChange(newVol)
                        } label: {
                            Image(systemName: "plus")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(textMuted)
                                .frame(width: 24, height: 24)
                                .background(Circle().fill(bgSurface3))
                        }
                        .buttonStyle(.plain)

                        Text("\(settings.txVolume)%")
                            .font(.system(size: 11, weight: .semibold, design: .monospaced))
                            .foregroundStyle(textMuted)
                            .frame(width: 36, alignment: .trailing)
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
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

/// TUNE toggle pill: idle = plain chip; active = statusBad red with the
/// remaining-seconds countdown (label via the kit's `TuneTone.chipLabel`);
/// dimmed/disabled while the sequencer owns the transmitter.
private struct TunePill: View {
    let isTuning: Bool
    let remainingSec: Int
    let locked: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(TuneTone.chipLabel(isTuning: isTuning, remainingSec: remainingSec))
                .font(.system(size: 11, weight: isTuning ? .bold : .semibold, design: .monospaced))
                .foregroundStyle(isTuning ? .white : (locked ? textMuted.opacity(0.4) : textMuted))
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(
                    RoundedRectangle(cornerRadius: 6)
                        .fill(isTuning ? statusBad : bgSurface3.opacity(locked ? 0.4 : 1.0))
                )
        }
        .buttonStyle(.plain)
        .disabled(locked && !isTuning)
    }
}

private struct CatChip: View {
    let status: ConnectionStatus

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(dotColor)
                .frame(width: 6, height: 6)
            Text("CAT")
                .font(.system(size: 9, weight: .bold, design: .monospaced))
                .foregroundStyle(textMuted)
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 3)
        .background(
            RoundedRectangle(cornerRadius: 4)
                .fill(bgSurface3)
        )
        .padding(.trailing, 6)
    }

    private var dotColor: Color {
        switch status {
        case .disconnected: return statusBad
        case .connecting:   return statusWarn
        case .connected:    return statusConfirmed
        }
    }
}

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
        Button {
            action()
            haptic()
        } label: {
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

    private func haptic() {
        let gen = UIImpactFeedbackGenerator(style: style == .primary ? .medium : .light)
        gen.impactOccurred()
    }
}
