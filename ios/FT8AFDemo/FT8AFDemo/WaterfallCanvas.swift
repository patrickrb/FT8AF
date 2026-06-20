import SwiftUI

struct WaterfallCanvas: View {
    let bins: [UInt8]
    let rows: Int
    let cols: Int

    var body: some View {
        Canvas { context, size in
            guard rows > 0, cols > 0, bins.count == rows * cols else { return }

            let cellW = size.width / CGFloat(cols)
            let cellH = size.height / CGFloat(rows)

            for r in 0..<rows {
                for c in 0..<cols {
                    let value = bins[r * cols + c]
                    let color = waterfallColor(value)
                    let rect = CGRect(
                        x: CGFloat(c) * cellW,
                        y: CGFloat(r) * cellH,
                        width: cellW + 1,      // +1 to avoid sub-pixel gaps
                        height: cellH + 1
                    )
                    context.fill(Path(rect), with: .color(color))
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    /// Map 0-255 intensity to a blue-green-yellow-red gradient.
    private func waterfallColor(_ value: UInt8) -> Color {
        let v = Double(value) / 255.0
        if v < 0.25 {
            let t = v / 0.25
            return Color(red: 0, green: 0, blue: 0.2 + 0.8 * t)
        } else if v < 0.5 {
            let t = (v - 0.25) / 0.25
            return Color(red: 0, green: t, blue: 1.0 - t)
        } else if v < 0.75 {
            let t = (v - 0.5) / 0.25
            return Color(red: t, green: 1.0, blue: 0)
        } else {
            let t = (v - 0.75) / 0.25
            return Color(red: 1.0, green: 1.0 - t, blue: 0)
        }
    }
}
