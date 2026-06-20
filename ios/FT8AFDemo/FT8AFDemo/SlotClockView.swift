import SwiftUI
import FT8Audio

struct SlotClockView: View {
    @State private var nowMs: Int64 = Self.currentUtcMs()
    private let timer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            VStack(spacing: 32) {
                Spacer()

                // UTC time display
                Text(utcTimeString(nowMs))
                    .font(.system(size: 48, weight: .light, design: .monospaced))

                // Slot info
                let slotID = SlotClock.slotID(atUtcMs: nowMs)
                let parity = SlotClock.parity(slotID: slotID)
                let msInto = SlotClock.msIntoCycle(atUtcMs: nowMs)
                let progress = Double(msInto) / Double(SlotClock.cycleMs)

                VStack(spacing: 12) {
                    HStack(spacing: 24) {
                        LabeledValue(label: "Slot ID", value: "\(slotID)")
                        LabeledValue(label: "Parity", value: parity == 0 ? "Even" : "Odd")
                        LabeledValue(label: "Into Cycle", value: "\(msInto) ms")
                    }

                    ProgressView(value: progress)
                        .progressViewStyle(.linear)
                        .tint(progress < 0.84 ? .blue : .orange)
                        .padding(.horizontal, 32)

                    Text(progress < 0.84 ? "RX Window" : "Guard")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Text("Cycle: \(SlotClock.cycleMs) ms")
                    .font(.footnote)
                    .foregroundStyle(.tertiary)

                Spacer()
            }
            .navigationTitle("Slot Clock")
            .onReceive(timer) { _ in
                nowMs = Self.currentUtcMs()
            }
        }
    }

    private static func currentUtcMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private func utcTimeString(_ ms: Int64) -> String {
        let total = ms / 1000
        let h = (total % 86400) / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        let frac = (ms % 1000) / 100
        return String(format: "%02d:%02d:%02d.%d", h, m, s, frac)
    }
}

private struct LabeledValue: View {
    let label: String
    let value: String

    var body: some View {
        VStack(spacing: 2) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.system(.title3, design: .monospaced))
        }
    }
}
