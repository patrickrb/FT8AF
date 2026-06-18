import SwiftUI

/// Vertical bar chart of FFT magnitudes with a TX frequency marker.
struct SpectrumStrip: View {
    @Environment(AppState.self) private var appState

    // Mock spectrum data: 120 bins across 0-3000 Hz
    private static let mockMagnitudes: [Float] = {
        var mags = [Float](repeating: 0, count: 120)
        for i in 0..<120 {
            // Base noise floor
            mags[i] = Float.random(in: 0.05...0.15)
        }
        // Add signal peaks matching waterfall mock
        let peaks = [15, 35, 52, 70, 88, 105]
        for p in peaks {
            if p < 120 {
                mags[p] = Float.random(in: 0.5...0.9)
                if p > 0 { mags[p - 1] = Float.random(in: 0.2...0.4) }
                if p < 119 { mags[p + 1] = Float.random(in: 0.2...0.4) }
            }
        }
        return mags
    }()

    var body: some View {
        Canvas { context, size in
            let mags = Self.mockMagnitudes
            let barW = size.width / CGFloat(mags.count)

            for (i, mag) in mags.enumerated() {
                let barH = CGFloat(mag) * size.height
                let rect = CGRect(
                    x: CGFloat(i) * barW,
                    y: size.height - barH,
                    width: barW + 0.5,
                    height: barH
                )
                let color: Color = mag > 0.3 ? signal : signal.opacity(0.4)
                context.fill(Path(rect), with: .color(color))
            }

            // TX frequency marker line
            let txBin = CGFloat(appState.waterfall.txFreqHz / 3000.0) * CGFloat(mags.count)
            let markerX = txBin * barW
            let markerRect = CGRect(x: markerX - 0.5, y: 0, width: 1, height: size.height)
            context.fill(Path(markerRect), with: .color(accent))
        }
        .background(bgSurface)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(borderSubtle)
                .frame(height: 1)
        }
    }
}
