import SwiftUI

/// Live scrolling waterfall heatmap, driven by `appState.waterfall.rows`.
/// Each row is a `[UInt8]` of brightness values (0...255) produced by
/// `WaterfallRowBuilder`. Renders newest rows at the bottom.
struct WaterfallCanvas: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        Canvas { context, size in
            let rows = appState.waterfall.rows
            guard !rows.isEmpty else { return }
            let numRows = rows.count
            let numCols = rows[0].count
            guard numCols > 0 else { return }

            let cellW = size.width / CGFloat(numCols)
            let cellH = size.height / CGFloat(numRows)

            for r in 0..<numRows {
                let row = rows[r]
                for c in 0..<min(numCols, row.count) {
                    let value = row[c]
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
        .background(bgApp)
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
