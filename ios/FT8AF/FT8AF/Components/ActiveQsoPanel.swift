import FT8Engine
import SwiftUI

/// Floating panel showing active QSO progress above the TxStrip (Android
/// parity: header states + SNR, LOG / ✕ actions, conversation log, TX
/// message-selector chips, caller queue, stage dots).
struct ActiveQsoPanel: View {
    @Environment(AppState.self) private var appState
    @State private var isExpanded = true

    var body: some View {
        let tx = appState.tx

        if isExpanded {
            expandedView(tx: tx)
        } else {
            collapsedView(tx: tx)
        }
    }

    private var headerState: QsoPanelHeader.State {
        QsoPanelHeader.state(
            targetCall: appState.tx.targetCall,
            isActivated: appState.tx.isActivated,
            huntEnabled: appState.tx.huntEnabled,
            huntCallsCQ: appState.settings.huntCallsCQ,
            isTransmitting: appState.tx.isTransmitting
        )
    }

    private var headerLabel: String {
        switch headerState {
        case .hunting:          return "Hunting…"
        case .callingCq:        return "Calling CQ"
        case .qsoing(let dx):   return "QSOing with \(dx)"
        case .waiting(let dx):  return "Waiting for \(dx)"
        case .idle:             return "Idle"
        }
    }

    private var hasTarget: Bool { !appState.tx.targetCall.isEmpty }

    private func expandedView(tx: TxState) -> some View {
        VStack(spacing: 8) {
            // Header row: QSO label + state + SNR · LOG · ✕ · collapse
            HStack(spacing: 8) {
                Text("QSO")
                    .font(.ft8afMono(size: 10, weight: .semibold))
                    .foregroundStyle(textFaint)

                Text(headerLabel)
                    .font(.ft8afMono(size: 13, weight: .bold))
                    .foregroundStyle(accent)
                    .lineLimit(1)

                if hasTarget, let snr = tx.targetSnr {
                    Text(snr >= 0 ? "+\(snr)dB" : "\(snr)dB")
                        .font(.ft8afMono(size: 11, weight: .medium))
                        .foregroundStyle(signal)
                }

                Spacer(minLength: 4)

                if hasTarget {
                    // LOG: force-log the current QSO and move on.
                    Button {
                        appState.engine?.forceLogAndMoveOn()
                    } label: {
                        Text("LOG")
                            .font(.ft8afMono(size: 10, weight: .bold))
                            .foregroundStyle(signal)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(RoundedRectangle(cornerRadius: 6).fill(signalSoft))
                    }
                    .buttonStyle(.plain)

                    // ✕: abandon the target (back to CQ / idle, nothing logged).
                    Button {
                        appState.engine?.clearActiveQso()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.ft8afUI(size: 11, weight: .semibold))
                            .foregroundStyle(textMuted)
                            .frame(width: 22, height: 22)
                    }
                    .buttonStyle(.plain)
                }

                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        isExpanded = false
                    }
                } label: {
                    Image(systemName: "chevron.down")
                        .font(.ft8afUI(size: 10, weight: .semibold))
                        .foregroundStyle(textMuted)
                        .frame(width: 22, height: 22)
                }
                .buttonStyle(.plain)
            }

            // Conversation log
            if !tx.conversationLog.isEmpty {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(spacing: 2) {
                            ForEach(tx.conversationLog) { entry in
                                ConversationRow(entry: entry)
                                    .id(entry.id)
                            }
                        }
                    }
                    .frame(maxHeight: 100)
                    .onChange(of: tx.conversationLog.count) { _, _ in
                        if let last = tx.conversationLog.last {
                            withAnimation(.easeOut(duration: 0.2)) {
                                proxy.scrollTo(last.id, anchor: .bottom)
                            }
                        }
                    }
                }
            } else if !tx.txMessage.isEmpty {
                // Fallback: show current TX message if no log entries yet
                Text(tx.txMessage)
                    .font(.ft8afMono(size: 12, weight: .medium))
                    .foregroundStyle(textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
                    .background(
                        RoundedRectangle(cornerRadius: 6)
                            .fill(bgSurface3)
                    )
            }

            // TX message selector chips (CQ / GRID / RPT / R-RPT / RR73 / 73)
            TxSelectorRow(currentStage: tx.qsoStage) { stage in
                appState.engine?.selectTxStage(stage)
            }

            // Caller queue — tap a callsign to log the current QSO and work them
            if !tx.queuedCallers.isEmpty {
                CallerQueueBar(callers: tx.queuedCallers) { callsign in
                    appState.engine?.forceLogAndMoveOn(nextCallsign: callsign)
                }
            }

            // Stage progress dots
            QsoStageDots(currentStage: tx.stage)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(bgSurface2)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(accent.opacity(0.3), lineWidth: 1)
                )
        )
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    private func collapsedView(tx: TxState) -> some View {
        Button {
            withAnimation(.easeInOut(duration: 0.2)) {
                isExpanded = true
            }
        } label: {
            HStack(spacing: 8) {
                Circle()
                    .fill(accent)
                    .frame(width: 6, height: 6)

                Text(headerLabel)
                    .font(.ft8afMono(size: 12, weight: .semibold))
                    .foregroundStyle(textPrimary)

                if !tx.queuedCallers.isEmpty {
                    Text("+\(tx.queuedCallers.count)")
                        .font(.ft8afMono(size: 10, weight: .bold))
                        .foregroundStyle(signal)
                }

                Spacer()

                Image(systemName: "chevron.up")
                    .font(.ft8afUI(size: 10, weight: .semibold))
                    .foregroundStyle(textMuted)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(bgSurface2)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .strokeBorder(accent.opacity(0.2), lineWidth: 1)
                    )
            )
            .padding(.horizontal, 16)
            .padding(.bottom, 4)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - TX message selector

/// Chip row wired to `QsoEngine.setStage` via the engine; the active chip is
/// amber, chips for stages already passed are cyan (Android `TxSelector`).
private struct TxSelectorRow: View {
    let currentStage: TxStage
    let onSelect: (TxStage) -> Void

    var body: some View {
        HStack(spacing: 4) {
            Text("TX:")
                .font(.ft8afMono(size: 10, weight: .semibold))
                .foregroundStyle(textFaint)

            ForEach(TxStageSelector.chips, id: \.rawValue) { chip in
                let isActive = TxStageSelector.isActive(chip, current: currentStage)
                let isCompleted = TxStageSelector.isCompleted(chip, current: currentStage)

                Button {
                    onSelect(chip)
                } label: {
                    Text(TxStageSelector.label(for: chip))
                        .font(.ft8afMono(size: 10, weight: isActive ? .bold : .medium))
                        .foregroundStyle(isActive ? accent : (isCompleted ? signal : textMuted))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(
                            RoundedRectangle(cornerRadius: 6)
                                .fill(isActive ? accentSoft : (isCompleted ? signalSoft : bgSurface3))
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 6)
                                .strokeBorder(isActive ? accent.opacity(0.5) : .clear, lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
            }

            Spacer(minLength: 0)
        }
    }
}

// MARK: - Caller queue

/// Wrapping row of stations waiting their turn; tapping one logs the current
/// QSO (when it progressed far enough) and works that caller next.
private struct CallerQueueBar: View {
    let callers: [String]
    let onTap: (String) -> Void

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text("QUEUE")
                .font(.ft8afMono(size: 9, weight: .semibold))
                .foregroundStyle(textFaint)

            WrappingHStack(spacing: 4, lineSpacing: 3) {
                ForEach(callers, id: \.self) { callsign in
                    Button {
                        onTap(callsign)
                    } label: {
                        Text(callsign)
                            .font(.ft8afMono(size: 10, weight: .medium))
                            .foregroundStyle(signal)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(RoundedRectangle(cornerRadius: 4).fill(signalSoft))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Minimal flow layout so queued callsigns wrap to new lines (Android FlowRow).
private struct WrappingHStack: Layout {
    var spacing: CGFloat = 4
    var lineSpacing: CGFloat = 3

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0, x + size.width > maxWidth {
                x = 0
                y += lineHeight + lineSpacing
                lineHeight = 0
            }
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
        return CGSize(width: maxWidth == .infinity ? x : maxWidth, height: y + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, lineHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += lineHeight + lineSpacing
                lineHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: .unspecified)
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}

// MARK: - Conversation Row

private struct ConversationRow: View {
    let entry: QsoLogEntry

    var body: some View {
        HStack(spacing: 6) {
            // Direction badge
            Text(entry.direction.rawValue)
                .font(.ft8afMono(size: 8, weight: .bold))
                .foregroundStyle(directionColor)
                .frame(width: 28)
                .padding(.vertical, 2)
                .background(
                    RoundedRectangle(cornerRadius: 3)
                        .fill(directionColor.opacity(0.14))
                )

            // Message
            Text(entry.message)
                .font(.ft8afMono(size: 11, weight: .medium))
                .foregroundStyle(textPrimary)
                .lineLimit(1)

            Spacer()

            // SNR (for RX)
            if let snr = entry.snr {
                Text(snr >= 0 ? "+\(snr)" : "\(snr)")
                    .font(.ft8afMono(size: 9, weight: .semibold))
                    .foregroundStyle(textFaint)
            }

            // Time
            Text(String(entry.utcTime.prefix(5)))
                .font(.ft8afMono(size: 9, weight: .medium))
                .foregroundStyle(textDim)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(
            RoundedRectangle(cornerRadius: 4)
                .fill(bgSurface3.opacity(0.5))
        )
    }

    private var directionColor: Color {
        switch entry.direction {
        case .tx:   return accent
        case .rx:   return signal
        case .busy: return textFaint
        }
    }
}

/// Horizontal 5-step progress indicator for QSO stages.
struct QsoStageDots: View {
    let currentStage: TxUIStage

    private let stages: [(label: String, stage: TxUIStage)] = [
        ("CQ", .cqSent),
        ("GRID", .reportSent),
        ("RPT", .reportSent),
        ("RR73", .rrSent),
        ("73", .complete),
    ]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(stages.enumerated()), id: \.offset) { idx, item in
                if idx > 0 {
                    Rectangle()
                        .fill(isReached(idx) ? accent : textDim.opacity(0.3))
                        .frame(height: 2)
                }
                VStack(spacing: 3) {
                    Circle()
                        .fill(isReached(idx) ? accent : textDim.opacity(0.3))
                        .frame(width: isCurrent(idx) ? 10 : 7, height: isCurrent(idx) ? 10 : 7)
                    Text(item.label)
                        .font(.ft8afMono(size: 8, weight: .semibold))
                        .foregroundStyle(isReached(idx) ? accent : textFaint)
                }
            }
        }
    }

    private var stageIndex: Int {
        switch currentStage {
        case .idle: return -1
        case .cqSent: return 0
        case .reportSent: return 2
        case .rrSent: return 3
        case .complete: return 4
        }
    }

    private func isReached(_ idx: Int) -> Bool { idx <= stageIndex }
    private func isCurrent(_ idx: Int) -> Bool { idx == stageIndex }
}
