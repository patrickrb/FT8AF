import SwiftUI
import FT8Audio

/// Horizontal ruler showing Hz tick marks across the displayed audio band.
/// Ticks span `waterfall.displayMaxHz` — the range the drawn waterfall and
/// spectrum data actually cover (the loop keeps it in step with the
/// spectrum-width setting while live) — so the labels line up with the trace
/// even when RX is stopped and the setting changes.
/// Tick geometry comes from `WaterfallAxis.rulerTicks`, which places each
/// label at its true `hz / width` fraction (mirrors the Android fix for ruler
/// labels drifting when the width isn't a 500 Hz multiple, commit 647b12e8).
struct FrequencyRuler: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        let ticks = WaterfallAxis.rulerTicks(
            displayMaxHz: appState.waterfall.displayMaxHz
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
                        .font(.ft8afMono(size: 8, weight: .medium))
                        .foregroundStyle(textFaint),
                    at: CGPoint(x: x, y: 14),
                    anchor: .center
                )
            }
        }
        .background(bgSurface)
    }
}
