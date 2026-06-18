import SwiftUI

struct DecodeRow: View {
    let message: DecodeMessage
    var myCall: String = ""
    var workedCalls: Set<String> = []
    var compact: Bool = false

    var body: some View {
        HStack(spacing: 0) {
            // UTC time
            Text(shortTime)
                .font(.system(size: compact ? 10 : 11, weight: .medium, design: .monospaced))
                .foregroundStyle(textFaint)
                .frame(width: compact ? 34 : 38, alignment: .leading)

            // SNR badge
            Text(snrText)
                .font(.system(size: compact ? 10 : 11, weight: .semibold, design: .monospaced))
                .foregroundStyle(snrColor)
                .frame(width: compact ? 28 : 32, alignment: .trailing)
                .padding(.trailing, compact ? 6 : 8)

            // Frequency
            Text(freqText)
                .font(.system(size: compact ? 10 : 11, weight: .medium, design: .monospaced))
                .foregroundStyle(textFaint)
                .frame(width: compact ? 36 : 40, alignment: .trailing)
                .padding(.trailing, compact ? 8 : 10)

            // Callsigns + extra
            VStack(alignment: .leading, spacing: compact ? 1 : 2) {
                HStack(spacing: 4) {
                    // CQ / callTo pill
                    if message.callTo == "CQ" || message.callTo.hasPrefix("CQ ") {
                        Text("CQ")
                            .font(.system(size: compact ? 9 : 10, weight: .bold, design: .monospaced))
                            .foregroundStyle(bgApp)
                            .padding(.horizontal, compact ? 4 : 5)
                            .padding(.vertical, 1)
                            .background(
                                RoundedRectangle(cornerRadius: 3)
                                    .fill(accent)
                            )
                    } else {
                        Text(message.callTo)
                            .font(.system(size: compact ? 11 : 12, weight: .medium, design: .monospaced))
                            .foregroundStyle(signal)
                    }

                    Image(systemName: "arrow.left")
                        .font(.system(size: compact ? 7 : 8))
                        .foregroundStyle(textDim)

                    Text(message.callFrom)
                        .font(.system(size: compact ? 12 : 13, weight: .bold, design: .monospaced))
                        .foregroundStyle(textPrimary)
                }

                if !compact, !message.grid.isEmpty || !message.extra.isEmpty {
                    Text(message.grid.isEmpty ? message.extra : message.grid)
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(textFaint)
                }
            }

            Spacer()

            // Chevron
            Image(systemName: "chevron.right")
                .font(.system(size: compact ? 9 : 10, weight: .semibold))
                .foregroundStyle(textDim)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, compact ? 5 : 8)
        .background(rowBackground)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(borderSubtle)
                .frame(height: 1)
        }
    }

    private var rowBackground: Color {
        if isForMe { return target.opacity(0.10) }
        if isCQ { return accent.opacity(0.06) }
        if isWorked { return signal.opacity(0.06) }
        return bgApp
    }

    private var isCQ: Bool {
        message.callTo == "CQ" || message.callTo.hasPrefix("CQ ")
    }

    private var isForMe: Bool {
        !myCall.isEmpty && message.callTo.uppercased() == myCall.uppercased()
    }

    private var isWorked: Bool {
        workedCalls.contains(message.callFrom.uppercased())
    }

    private var shortTime: String {
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
