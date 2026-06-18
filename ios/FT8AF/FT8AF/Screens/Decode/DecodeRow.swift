import SwiftUI

struct DecodeRow: View {
    let message: DecodeMessage

    var body: some View {
        HStack(spacing: 0) {
            // UTC time
            Text(shortTime)
                .font(.system(size: 11, weight: .medium, design: .monospaced))
                .foregroundStyle(textFaint)
                .frame(width: 38, alignment: .leading)

            // SNR badge
            Text(snrText)
                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                .foregroundStyle(snrColor)
                .frame(width: 32, alignment: .trailing)
                .padding(.trailing, 8)

            // Frequency
            Text(freqText)
                .font(.system(size: 11, weight: .medium, design: .monospaced))
                .foregroundStyle(textFaint)
                .frame(width: 40, alignment: .trailing)
                .padding(.trailing, 10)

            // Callsigns + extra
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    // CQ / callTo pill
                    if message.callTo == "CQ" || message.callTo.hasPrefix("CQ ") {
                        Text("CQ")
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .foregroundStyle(bgApp)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(
                                RoundedRectangle(cornerRadius: 3)
                                    .fill(accent)
                            )
                    } else {
                        Text(message.callTo)
                            .font(.system(size: 12, weight: .medium, design: .monospaced))
                            .foregroundStyle(signal)
                    }

                    Image(systemName: "arrow.left")
                        .font(.system(size: 8))
                        .foregroundStyle(textDim)

                    Text(message.callFrom)
                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                        .foregroundStyle(textPrimary)
                }

                if !message.grid.isEmpty || !message.extra.isEmpty {
                    Text(message.grid.isEmpty ? message.extra : message.grid)
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(textFaint)
                }
            }

            Spacer()

            // Chevron
            Image(systemName: "chevron.right")
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(textDim)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(bgApp)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(borderSubtle)
                .frame(height: 1)
        }
    }

    private var shortTime: String {
        // "12:30:00" -> "12:30"
        String(message.utcTime.prefix(5))
    }

    private var snrText: String {
        message.snr >= 0 ? "+\(message.snr)" : "\(message.snr)"
    }

    private var snrColor: Color {
        if message.snr >= 0 { return statusConfirmed }
        if message.snr >= -10 { return signal }
        if message.snr >= -18 { return textMuted }
        return statusBad
    }

    private var freqText: String {
        String(format: "%.0f", message.freqHz)
    }
}
