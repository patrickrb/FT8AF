import SwiftUI

/// Horizontal ruler showing Hz tick marks from 0 to 3000.
struct FrequencyRuler: View {
    private let ticks = stride(from: 0, through: 3000, by: 500).map { $0 }

    var body: some View {
        Canvas { context, size in
            let hzRange: CGFloat = 3000
            for hz in ticks {
                let x = CGFloat(hz) / hzRange * size.width
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
