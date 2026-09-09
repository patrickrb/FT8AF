import FT8Audio
import FT8DSP
import SwiftUI

/// Thin progress bar showing the current position in the active mode's cycle
/// (15 s FT8 / 7.5 s FT4). Color alternates by slot parity: even slots =
/// accent/orange, odd = signal/blue. Guard time (the waveform-to-boundary
/// slack) is highlighted brighter.
struct SlotTimerBar: View {
    @Environment(AppState.self) private var appState

    @State private var progress: Double = 0
    @State private var isGuard: Bool = false
    @State private var isEvenSlot: Bool = true

    private let timer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()

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
            let profile = appState.settings.mode.profile
            // Use the same corrected clock LiveEngine schedules RX/TX with (NTP +
            // manual + auto-DT), so the bar and its parity never show a different
            // slot than the engine is actually using after a sync.
            let offsetMs = appState.clock
                .clockOffset(manualMs: appState.settings.manualClockOffsetMs)
                .combinedMs
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000) + offsetMs
            let ms = SlotClock.msIntoCycle(atUtcMs: nowMs, cycleMs: profile.cycleMs)
            progress = Double(ms) / Double(profile.cycleMs)
            // Guard band = the mode's message duration to the slot boundary.
            isGuard = ms >= profile.waveformMs
            // Determine slot parity from current slot ID
            let slotID = SlotClock.rxSlotID(atUtcMs: nowMs, rxOffsetMs: 0, cycleMs: profile.cycleMs)
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
