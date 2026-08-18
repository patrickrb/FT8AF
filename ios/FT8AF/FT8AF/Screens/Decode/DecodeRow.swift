import FT8Engine
import SwiftUI

/// A single decoded FT8 message row, mirroring the Android `DecodeRow` design:
///
///  - Left accent bar — pink for the current call target, amber for CQ
///  - Context label chips (CALLING / CQ / TO YOU) before the callsign
///  - Callsign (bold monospaced), grid locator, status pill on the right
///  - Full decoded message text line
///  - Meta row: segmented signal bar, SNR, audio frequency, distance,
///    relative age ("now" / "32s" / "5m" / "2h")
///  - DXCC entity location line with globe icon (when known)
///
/// Background tinting: cyan glow for messages directed at the operator, pink
/// soft for the call target, surface card for CQ, transparent otherwise.
/// All classification logic is pure and lives in FT8Engine
/// (`classifyDecode`, `relativeAge`, `gridDistanceText`, `DxccPrefix`).
struct DecodeRow: View {
    let message: DecodeMessage
    var myCall: String = ""
    var myGrid: String = ""
    var highlight: DecodeHighlight?
    var isTarget: Bool = false
    var compact: Bool = false
    var now: Date = Date()
    var distanceInMiles: Bool = false

    private var isCQ: Bool { isCQMessage(callTo: message.callTo) }
    private var isToMe: Bool { isDirectedToMe(callTo: message.callTo, myCall: myCall) }
    /// Mid-QSO with a third party — de-emphasized but readable (Android: 0.8).
    private var isInQsoWithOther: Bool { !isCQ && !isToMe && !isTarget }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            // Left accent bar — target (pink) wins over CQ (amber) because it
            // is the more actionable signal for the operator.
            if let accentColor = accentBarColor {
                RoundedRectangle(cornerRadius: 99)
                    .fill(accentColor)
                    .frame(width: 3, height: compact ? 32 : 52)
                Spacer().frame(width: 10)
            } else {
                Spacer().frame(width: 13)
            }

            VStack(alignment: .leading, spacing: 0) {
                topRow
                Spacer().frame(height: compact ? 2 : 4)
                if !compact && !fullMessageText.isEmpty {
                    Text(fullMessageText)
                        .font(.ft8afMono(size: 12))
                        .foregroundStyle(textMuted)
                        .lineLimit(1)
                    Spacer().frame(height: 4)
                }
                metaRow
                if !compact, let location = locationText {
                    Spacer().frame(height: 4)
                    HStack(spacing: 4) {
                        Image(systemName: "globe")
                            .font(.ft8afUI(size: 10))
                            .foregroundStyle(textFaint)
                        Text(location)
                            .font(.ft8afUI(size: 10.5, weight: .medium))
                            .foregroundStyle(textFaint)
                    }
                }
            }
            .padding(.trailing, 12)
        }
        .padding(.vertical, compact ? 6 : 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(rowBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .strokeBorder(rowBorder, lineWidth: 1)
        )
        .contentShape(Rectangle())
        .opacity(isInQsoWithOther ? 0.8 : 1.0)
        .padding(.horizontal, 12)
        .padding(.vertical, 3)
    }

    // MARK: - Sub-rows

    private var topRow: some View {
        HStack(spacing: 8) {
            // Context labels can stack — a target station calling CQ shows
            // both CALLING and CQ, like Android.
            if isTarget {
                MessageLabel(text: "CALLING", color: target, bgColor: targetSoft)
            }
            if isCQ {
                MessageLabel(text: "CQ", color: accent, bgColor: accentSoft)
            } else if isToMe {
                MessageLabel(text: "TO YOU", color: signal, bgColor: signalSoft)
            }

            Text(message.callFrom)
                .font(.ft8afMono(size: 15, weight: .bold))
                .foregroundStyle(callsignColor)

            if !message.grid.isEmpty {
                Text(message.grid)
                    .font(.ft8afMono(size: 11))
                    .foregroundStyle(textFaint)
            }

            Spacer(minLength: 4)

            if let highlight {
                StatusPillView(highlight: highlight, compact: true)
            }
        }
    }

    private var metaRow: some View {
        HStack(spacing: 10) {
            SignalBarView(
                snr: message.snr,
                width: compact ? 22 : 28,
                height: compact ? 10 : 12
            )

            MetaText("\(message.snr) dB")
            MetaText("\(Int(message.freqHz)) Hz")

            let distance = gridDistanceText(from: myGrid, to: message.grid, inMiles: distanceInMiles)
            if !distance.isEmpty {
                MetaText(distance)
            }

            Spacer(minLength: 4)

            MetaText(relativeAge(secondsAgo: Int(now.timeIntervalSince(message.arrival))))
        }
    }

    // MARK: - Derived styling

    private var accentBarColor: Color? {
        if isTarget { return target }
        if isCQ { return accent }
        return nil
    }

    private var rowBackground: Color {
        if isToMe { return signal.opacity(0.08) }   // cyan glow
        if isTarget { return targetSoft }
        if isCQ { return bgSurface }
        return .clear
    }

    private var rowBorder: Color {
        if isToMe { return signal.opacity(0.22) }
        if isTarget { return targetBorder }
        if isCQ { return borderSubtle }
        return .clear
    }

    private var callsignColor: Color {
        if isToMe { return signal }
        if isTarget { return target }
        return textPrimary
    }

    private var fullMessageText: String {
        var parts: [String] = []
        if !message.callTo.isEmpty { parts.append(message.callTo) }
        if !message.callFrom.isEmpty { parts.append(message.callFrom) }
        if !message.extra.isEmpty {
            parts.append(message.extra)
        } else if !message.grid.isEmpty {
            parts.append(message.grid)
        }
        return parts.joined(separator: " ")
    }

    /// Location line for the row: a resolved US state ("CT, USA") when the grid
    /// maps to one, else the DXCC entity name ("United States" → "USA"
    /// shorthand). Logic lives in `decodeLocationText` (FT8Engine) so it is
    /// testable; the view is a thin caller.
    private var locationText: String? {
        decodeLocationText(callFrom: message.callFrom, grid: message.grid)
    }
}

// MARK: - Status pill

/// Android-parity status chip: 4 pt corner, glowing dot, hue at 0.12 bg /
/// 0.28 border alpha. Category → hue mapping mirrors `StatusPill.kt` with the
/// pending/needed chips on the amber accent.
private struct StatusPillView: View {
    let highlight: DecodeHighlight
    var compact: Bool = false

    var body: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(hue)
                .frame(width: 6, height: 6)
            Text(label)
                .font(.ft8afUI(size: compact ? 9.5 : 11, weight: .semibold))
                .foregroundStyle(hue)
        }
        .padding(.horizontal, compact ? 6 : 8)
        .frame(height: compact ? 18 : 22)
        .background(
            RoundedRectangle(cornerRadius: 4)
                .fill(hue.opacity(0.12))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 4)
                .strokeBorder(hue.opacity(0.28), lineWidth: 1)
        )
    }

    private var label: String {
        switch highlight {
        case .pending: return "PENDING"
        case .pota: return "POTA"
        case .newDxcc: return "NEW DXCC"
        case .newState: return "NEW STATE"
        case .newGrid: return "NEW GRID"
        case .newBand: return "NEW BAND"
        case .worked: return "WORKED"
        case .cq: return "CQ"
        }
    }

    private var hue: Color {
        switch highlight {
        case .pending: return accent          // amber — needs the operator's action
        case .pota: return statusConfirmed    // green
        case .newDxcc: return statusNew       // purple
        case .newState: return statusState    // teal
        case .newGrid: return statusWarn      // yellow
        case .newBand: return statusWorked    // cyan
        case .worked: return statusWorked     // cyan
        case .cq: return statusCq             // amber
        }
    }
}

// MARK: - Signal bar

/// Small segmented signal-strength bar (5 ascending bars), port of the
/// Android `SignalBar`: fill fraction = (snr + 25) / 30, color by SNR band.
private struct SignalBarView: View {
    let snr: Int
    var width: CGFloat = 28
    var height: CGFloat = 12

    private static let bars = 5

    var body: some View {
        let filled = Self.filledBars(snr: snr)
        let barWidth = (width - CGFloat(Self.bars - 1) * 2) / CGFloat(Self.bars)
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<Self.bars, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1)
                    .fill(i < filled ? barColor : emptyColor)
                    .frame(
                        width: barWidth,
                        height: 4 + (height - 4) * CGFloat(i) / CGFloat(Self.bars - 1)
                    )
            }
        }
        .frame(height: height, alignment: .bottom)
    }

    static func filledBars(snr: Int) -> Int {
        let norm = min(1, max(0, Float(snr + 25) / 30))
        return min(bars, Int(norm * Float(bars)))
    }

    private var barColor: Color {
        if snr >= -5 { return statusConfirmed }  // green
        if snr >= -12 { return signal }          // cyan
        if snr >= -18 { return accent }          // amber
        return statusBad                          // red
    }

    private var emptyColor: Color {
        Color(red: 148/255, green: 163/255, blue: 184/255).opacity(0.15)
    }
}

// MARK: - Small pieces

/// Small colored label chip (e.g. "CQ", "TO YOU", "CALLING").
private struct MessageLabel: View {
    let text: String
    let color: Color
    let bgColor: Color

    var body: some View {
        Text(text)
            .font(.ft8afUI(size: 9.5, weight: .bold))
            .kerning(0.6)
            .foregroundStyle(color)
            .padding(.horizontal, 5)
            .padding(.vertical, 1)
            .background(RoundedRectangle(cornerRadius: 4).fill(bgColor))
    }
}

/// Small monospaced metadata text used in the bottom info row.
private struct MetaText: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.ft8afMono(size: 10.5))
            .foregroundStyle(textFaint)
    }
}
