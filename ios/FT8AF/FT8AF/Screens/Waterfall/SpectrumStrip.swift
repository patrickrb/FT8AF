import SwiftUI

/// Vertical bar chart of live FFT magnitudes with a TX frequency marker.
/// Reads normalized magnitudes (0...1) from `appState.waterfall.spectrum`.
struct SpectrumStrip: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        Canvas { context, size in
            let mags = appState.waterfall.spectrum
            guard !mags.isEmpty else { return }
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
