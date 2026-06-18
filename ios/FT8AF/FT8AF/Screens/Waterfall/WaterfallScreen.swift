import SwiftUI

struct WaterfallScreen: View {
    @Environment(AppState.self) private var appState
    @State private var utcString = ""

    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 0) {
            // Spectrum strip (top)
            SpectrumStrip()
                .frame(height: 96)

            // Frequency ruler
            FrequencyRuler()
                .frame(height: 20)

            // Waterfall canvas (fills remaining space)
            WaterfallCanvas()
                .padding(.horizontal, 2)

            // Info bar (bottom)
            HStack {
                Text(utcString)
                    .font(.system(size: 12, weight: .semibold, design: .monospaced))
                    .foregroundStyle(textPrimary)

                Spacer()

                HStack(spacing: 12) {
                    InfoChip(label: "NR", isOn: false)
                    InfoChip(label: "MSG", isOn: true)
                    LiveIndicator(isLive: appState.waterfall.isLive)
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 34)
            .background(bgSurface)

            TxStrip()
        }
        .background(bgApp)
        .onReceive(timer) { _ in
            let fmt = DateFormatter()
            fmt.dateFormat = "HH:mm:ss"
            fmt.timeZone = TimeZone(identifier: "UTC")
            utcString = fmt.string(from: Date()) + " UTC"
        }
    }
}

private struct InfoChip: View {
    let label: String
    let isOn: Bool

    var body: some View {
        Text(label)
            .font(.system(size: 10, weight: .semibold, design: .monospaced))
            .foregroundStyle(isOn ? accent : textFaint)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(
                RoundedRectangle(cornerRadius: 4)
                    .fill(isOn ? accentSoft : bgSurface3)
            )
    }
}

private struct LiveIndicator: View {
    let isLive: Bool

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(isLive ? statusConfirmed : statusBad)
                .frame(width: 6, height: 6)
            Text(isLive ? "LIVE" : "OFF")
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundStyle(isLive ? statusConfirmed : textFaint)
        }
    }
}
