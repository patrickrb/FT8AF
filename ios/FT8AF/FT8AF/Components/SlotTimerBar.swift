import FT8Audio
import SwiftUI

/// Thin progress bar showing the current position in the 15 s FT8 cycle.
/// Color alternates by slot parity: even slots = accent/orange, odd = signal/blue.
/// Guard time (last ~2.36 s) is highlighted brighter.
struct SlotTimerBar: View {
    @State private var progress: Double = 0
    @State private var isGuard: Bool = false
    @State private var isEvenSlot: Bool = true

    private let timer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()
    private static let guardThresholdMs: Int64 = 12_640 // FT8 message duration

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Rectangle()
                    .fill(bgSurface)

                Rectangle()
                    .fill(barColor)
                    .frame(width: geo.size.width * progress)
            }
        }
        .frame(height: 3)
        .onReceive(timer) { _ in
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let ms = SlotClock.msIntoCycle(atUtcMs: nowMs)
            progress = Double(ms) / Double(SlotClock.cycleMs)
            isGuard = ms >= Self.guardThresholdMs
            // Determine slot parity from current slot ID
            let slotID = SlotClock.rxSlotID(atUtcMs: nowMs, rxOffsetMs: 0)
            isEvenSlot = SlotClock.parity(slotID: slotID) == 0
        }
    }

    private var barColor: Color {
        if isGuard {
            return isEvenSlot ? accent : signal
        }
        return isEvenSlot ? accent.opacity(0.6) : signal.opacity(0.6)
    }
}
