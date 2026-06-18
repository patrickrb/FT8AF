import SwiftUI

/// Bottom sheet showing station detail when a decode row is tapped.
struct QsoSheet: View {
    let message: DecodeMessage
    var onCall: (DecodeMessage) -> Void = { _ in }
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            // Callsign header
            VStack(spacing: 4) {
                Text(message.callFrom)
                    .font(.system(size: 28, weight: .bold, design: .monospaced))
                    .foregroundStyle(textPrimary)

                if !message.grid.isEmpty {
                    Text(message.grid)
                        .font(.system(size: 14, weight: .medium, design: .monospaced))
                        .foregroundStyle(textMuted)
                }
            }
            .padding(.top, 20)
            .padding(.bottom, 16)

            // Stats row
            HStack(spacing: 24) {
                StatBadge(label: "SNR", value: message.snr >= 0 ? "+\(message.snr)" : "\(message.snr)")
                StatBadge(label: "Freq", value: String(format: "%.0f Hz", message.freqHz))
                StatBadge(label: "UTC", value: message.utcTime)
            }
            .padding(.bottom, 20)

            // Extra info
            if !message.extra.isEmpty && message.extra != message.grid {
                HStack {
                    Text("Report")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(textFaint)
                    Spacer()
                    Text(message.extra)
                        .font(.system(size: 14, weight: .semibold, design: .monospaced))
                        .foregroundStyle(textPrimary)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
            }

            Spacer()

            // Call button — starts answering this station's CQ
            Button {
                onCall(message)
                dismiss()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .font(.system(size: 14, weight: .semibold))
                    Text("Call \(message.callFrom)")
                        .font(.system(size: 16, weight: .bold, design: .monospaced))
                }
                .foregroundStyle(bgApp)
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(accent)
                )
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24)
            .padding(.bottom, 30)
        }
        .frame(maxWidth: .infinity)
        .background(bgSurface2)
    }
}

private struct StatBadge: View {
    let label: String
    let value: String

    var body: some View {
        VStack(spacing: 4) {
            Text(label)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(textFaint)
                .textCase(.uppercase)
            Text(value)
                .font(.system(size: 14, weight: .semibold, design: .monospaced))
                .foregroundStyle(textPrimary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(bgSurface3)
        )
    }
}
