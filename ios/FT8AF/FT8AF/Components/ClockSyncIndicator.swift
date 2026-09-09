import FT8Audio
import SwiftUI

/// Compact clock-sync health pill: a color-coded dot, a "DT" label operators
/// know from WSJT-X, and the signed offset. Port of Android's
/// `ClockSyncIndicator`. All classification/format logic lives in the pure,
/// tested `ClockHealth` type in FT8Audio; this view only draws and wires
/// accessibility.
struct ClockSyncIndicator: View {
    /// Mean decode DT (seconds); nil until the first decode this session.
    let offsetSec: Float?

    var body: some View {
        let level = ClockHealth.level(offsetSec: offsetSec)
        let label = ClockHealth.offsetLabel(offsetSec: offsetSec)

        HStack(spacing: 4) {
            Circle()
                .fill(color(for: level))
                .frame(width: 6, height: 6)
            Text("DT")
                .font(.ft8afMono(size: 9, weight: .bold))
                .foregroundStyle(textMuted)
            Text(label)
                .font(.ft8afMono(size: 11, weight: .semibold))
                .foregroundStyle(color(for: level))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Clock sync \(label), \(ClockHealth.statusText(level))")
    }

    /// Green in-sync, amber worth-a-resync, red clock-off, grey unknown.
    private func color(for level: ClockHealthLevel) -> Color {
        switch level {
        case .good: return statusConfirmed
        case .fair: return statusWarn
        case .poor: return statusBad
        case .unknown: return textMuted
        }
    }
}
