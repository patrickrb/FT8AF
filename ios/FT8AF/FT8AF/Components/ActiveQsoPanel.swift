import SwiftUI

/// Floating panel showing active QSO progress above the TxStrip.
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

    private func expandedView(tx: TxState) -> some View {
        VStack(spacing: 8) {
            // Header row with target call and collapse button
            HStack {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(accent)

                Text(tx.targetCall.isEmpty ? "CQ" : tx.targetCall)
                    .font(.system(size: 16, weight: .bold, design: .monospaced))
                    .foregroundStyle(textPrimary)

                Spacer()

                Text(stageLabel(tx.stage))
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundStyle(accent)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(
                        RoundedRectangle(cornerRadius: 4)
                            .fill(accent.opacity(0.14))
                    )

                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        isExpanded = false
                    }
                } label: {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(textMuted)
                }
                .buttonStyle(.plain)
            }

            // TX message
            if !tx.txMessage.isEmpty {
                Text(tx.txMessage)
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
                    .background(
                        RoundedRectangle(cornerRadius: 6)
                            .fill(bgSurface3)
                    )
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

                Text("QSO: \(tx.targetCall.isEmpty ? "CQ" : tx.targetCall)")
                    .font(.system(size: 12, weight: .semibold, design: .monospaced))
                    .foregroundStyle(textPrimary)

                Text("— \(stageLabel(tx.stage))")
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(textMuted)

                Spacer()

                Image(systemName: "chevron.up")
                    .font(.system(size: 10, weight: .semibold))
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

    private func stageLabel(_ stage: TxUIStage) -> String {
        switch stage {
        case .idle:       return "IDLE"
        case .cqSent:     return "CQ"
        case .reportSent: return "REPORT"
        case .rrSent:     return "RR73"
        case .complete:   return "DONE"
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
                        .font(.system(size: 8, weight: .semibold, design: .monospaced))
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
