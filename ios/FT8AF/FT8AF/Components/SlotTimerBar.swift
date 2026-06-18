import FT8Audio
import SwiftUI

/// Thin progress bar showing the current position in the 15 s FT8 cycle.
/// Blue for RX window, orange for guard time (last ~2.36 s).
struct SlotTimerBar: View {
    @State private var progress: Double = 0
    @State private var isGuard: Bool = false

    private let timer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()
    private static let guardThresholdMs: Int64 = 12_640 // FT8 message duration

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Rectangle()
                    .fill(bgSurface)

                Rectangle()
                    .fill(isGuard ? accent : signal)
                    .frame(width: geo.size.width * progress)
            }
        }
        .frame(height: 3)
        .onReceive(timer) { _ in
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let ms = SlotClock.msIntoCycle(atUtcMs: nowMs)
            progress = Double(ms) / Double(SlotClock.cycleMs)
            isGuard = ms >= Self.guardThresholdMs
        }
    }
}
