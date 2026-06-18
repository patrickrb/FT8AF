import SwiftUI

/// Static mock waterfall heatmap. Phase 2 replaces this with a live MTKView.
struct WaterfallCanvas: View {
    // Generate static mock data once
    private let mockData: (bins: [UInt8], rows: Int, cols: Int) = {
        let rows = 80
        let cols = 120
        var bins = [UInt8](repeating: 0, count: rows * cols)
        // Simulate a few FT8 signals as bright horizontal bands
        let signals: [(col: Int, width: Int, intensity: UInt8)] = [
            (15, 3, 200), (35, 2, 150), (52, 3, 220),
            (70, 2, 130), (88, 4, 180), (105, 2, 160),
        ]
        for r in 0..<rows {
            for c in 0..<cols {
                // Background noise
                let noise = UInt8.random(in: 5...35)
                var value = noise
                // Add signal traces
                for sig in signals {
                    if c >= sig.col && c < sig.col + sig.width {
                        let variation = Int.random(in: -20...20)
                        let signalVal = Int(sig.intensity) + variation
                        value = UInt8(max(Int(value), min(255, signalVal)))
                    }
                }
                bins[r * cols + c] = value
            }
        }
        return (bins, rows, cols)
    }()

    var body: some View {
        Canvas { context, size in
            let rows = mockData.rows
            let cols = mockData.cols
            let bins = mockData.bins
            guard rows > 0, cols > 0 else { return }

            let cellW = size.width / CGFloat(cols)
            let cellH = size.height / CGFloat(rows)

            for r in 0..<rows {
                for c in 0..<cols {
                    let value = bins[r * cols + c]
                    let color = waterfallColor(value)
                    let rect = CGRect(
                        x: CGFloat(c) * cellW,
                        y: CGFloat(r) * cellH,
                        width: cellW + 1,
                        height: cellH + 1
                    )
                    context.fill(Path(rect), with: .color(color))
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 4))
    }

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
