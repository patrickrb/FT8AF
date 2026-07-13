import SwiftUI
import FT8Audio

/// Horizontal ruler showing Hz tick marks across the displayed audio band.
/// Ticks span the same range the waterfall/spectrum data covers
/// (`WaterfallAxis.displayMaxHz`) so the labels line up with the trace.
struct FrequencyRuler: View {
    private let ticks = stride(from: 0, through: Int(WaterfallAxis.displayMaxHz), by: 500).map { $0 }

    var body: some View {
        Canvas { context, size in
            for hz in ticks {
                let x = CGFloat(WaterfallAxis.fraction(forHz: Float(hz))) * size.width
                // Tick mark
                let tickPath = Path { p in
                    p.move(to: CGPoint(x: x, y: 0))
                    p.addLine(to: CGPoint(x: x, y: 6))
                }
                context.stroke(tickPath, with: .color(textFaint), lineWidth: 0.5)
                // Label
                let label = "\(hz)"
                context.draw(
                    Text(label)
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
