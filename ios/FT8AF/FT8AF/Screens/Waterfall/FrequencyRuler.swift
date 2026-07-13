import SwiftUI
import FT8Audio

/// Horizontal ruler showing Hz tick marks across the displayed audio band.
/// Ticks span the operator's spectrum-width setting — the same range the
/// waterfall/spectrum data covers — so the labels line up with the trace.
/// Tick geometry comes from `WaterfallAxis.rulerTicks`, which places each
/// label at its true `hz / width` fraction (mirrors the Android fix for ruler
/// labels drifting when the width isn't a 500 Hz multiple, commit 647b12e8).
struct FrequencyRuler: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        let ticks = WaterfallAxis.rulerTicks(
            displayMaxHz: Float(appState.settings.spectrumWidthHz)
        )
        Canvas { context, size in
            for tick in ticks {
                let x = CGFloat(tick.fraction) * size.width
                // Tick mark
                let tickPath = Path { p in
                    p.move(to: CGPoint(x: x, y: 0))
                    p.addLine(to: CGPoint(x: x, y: 6))
                }
                context.stroke(tickPath, with: .color(textFaint), lineWidth: 0.5)
                // Label
                context.draw(
                    Text("\(tick.hz)")
                        .font(.system(size: 8, weight: .medium, design: .monospaced))
                        .foregroundStyle(textFaint),
                    at: CGPoint(x: x, y: 14),
                    anchor: .center
                )
            }
        }
        .background(bgSurface)
    }
}
