import SwiftUI

/// Vertical bar chart of live FFT magnitudes with a TX frequency marker.
/// Reads normalized magnitudes (0...1) from `appState.waterfall.spectrum`.
/// Tap or drag to set the TX frequency.
struct SpectrumStrip: View {
    @Environment(AppState.self) private var appState
    @State private var touchFreq: Float?

    private let maxFreqHz: Float = 3000

    var body: some View {
        GeometryReader { geo in
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
                let txX = CGFloat(appState.waterfall.txFreqHz / maxFreqHz) * size.width
                let markerRect = CGRect(x: txX - 0.5, y: 0, width: 1, height: size.height)
                context.fill(Path(markerRect), with: .color(accent))

                // Touch frequency indicator
                if let tf = touchFreq {
                    let touchX = CGFloat(tf / maxFreqHz) * size.width
                    let touchRect = CGRect(x: touchX - 0.5, y: 0, width: 1, height: size.height)
                    context.fill(Path(touchRect), with: .color(target.opacity(0.7)))

                    // Frequency label
                    let label = context.resolve(Text("\(Int(tf)) Hz")
                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                        .foregroundStyle(textPrimary))
                    let labelX = min(max(touchX, 30), size.width - 30)
                    context.draw(label, at: CGPoint(x: labelX, y: 8), anchor: .top)
                }
            }
            .background(bgSurface)
            .overlay(alignment: .bottom) {
                Rectangle()
                    .fill(borderSubtle)
                    .frame(height: 1)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let freq = Float(value.location.x / geo.size.width) * maxFreqHz
                        touchFreq = max(100, min(freq, maxFreqHz))
                    }
                    .onEnded { value in
                        let freq = Float(value.location.x / geo.size.width) * maxFreqHz
                        let clamped = max(100, min(freq, maxFreqHz))
                        appState.waterfall.txFreqHz = clamped
                        // Clear touch indicator after short delay
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                            touchFreq = nil
                        }
                    }
            )
        }
    }
}
