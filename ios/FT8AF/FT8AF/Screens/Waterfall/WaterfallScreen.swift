import FT8Audio
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

                // TX frequency display
                Text("TX \(Int(appState.waterfall.txFreqHz)) Hz")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(accent)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(
                        RoundedRectangle(cornerRadius: 4)
                            .fill(accentSoft)
                    )

                // RX input level meter (Android: InputLevelIndicator).
                InputLevelMeter(
                    peak: appState.waterfall.inputPeak,
                    rms: appState.waterfall.inputRms
                )
                .padding(.leading, 4)

                Spacer()

                HStack(spacing: 12) {
                    Button {
                        appState.waterfall.noiseReduction.toggle()
                    } label: {
                        InfoChip(label: "NR", isOn: appState.waterfall.noiseReduction)
                    }
                    .buttonStyle(.plain)

                    Button {
                        appState.waterfall.messageMarking.toggle()
                    } label: {
                        InfoChip(label: "MSG", isOn: appState.waterfall.messageMarking)
                    }
                    .buttonStyle(.plain)

                    Text("\(appState.waterfall.updateCount)")
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(textDim)

                    LiveIndicator(isLive: appState.waterfall.isLive)
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 34)
            .background(bgSurface)

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

/// Compact live RX input-level meter for the waterfall info bar: an
/// RMS-filled bar with a peak tick and a color-coded status word. Mirrors
/// Android's `InputLevelIndicator`; the level→status classification is the
/// pure, kit-tested `AudioInputLevel` (same thresholds as Android).
private struct InputLevelMeter: View {
    let peak: Float
    let rms: Float

    private static let barWidth: CGFloat = 44

    private var status: AudioInputLevel.Status {
        AudioInputLevel.fromPeakRms(peak: peak, rms: rms).status
    }

    /// Status color: faint = no/too-low input, green = good, yellow = hot,
    /// red = clipping.
    private var color: Color {
        switch status {
        case .silent, .low: return textFaint
        case .good: return statusConfirmed
        case .high: return statusWarn
        case .clipping: return statusBad
        }
    }

    private var statusText: String {
        switch status {
        case .silent, .low: return "LOW"
        case .good: return "GOOD"
        case .high: return "HIGH"
        case .clipping: return "CLIP"
        }
    }

    var body: some View {
        HStack(spacing: 4) {
            Text("RX")
                .font(.system(size: 9, weight: .bold, design: .monospaced))
                .foregroundStyle(textMuted)

            // Meter bar: RMS fill + peak tick.
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3)
                    .fill(bgSurface3)
                RoundedRectangle(cornerRadius: 3)
                    .fill(color)
                    .frame(width: Self.barWidth * CGFloat(AudioInputLevel.meterFraction(rms)))
                Rectangle()
                    .fill(color)
                    .frame(width: 1)
                    .offset(x: max(0, Self.barWidth * CGFloat(AudioInputLevel.meterFraction(peak)) - 1))
            }
            .frame(width: Self.barWidth, height: 6)
            .clipShape(RoundedRectangle(cornerRadius: 3))

            Text(statusText)
                .font(.system(size: 9, weight: .bold, design: .monospaced))
                .foregroundStyle(color)
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
