import FT8Audio
import FT8DSP
import Foundation
import Observation

/// Top-level coordinator that owns audio capture and runs the decode +
/// waterfall pipelines as concurrent structured tasks. Lifecycle: create once,
/// call `start(appState:)`, and later `stop()`.
@Observable @MainActor
final class LiveEngine {
    private(set) var isRunning = false

    private let audio = AudioCaptureService()
    private var engineTask: Task<Void, Never>?
    private var rxOffsetMs: Int64 = 0
    /// Weakly held so `stop()` can clear the LIVE indicator it set in `start()`.
    private weak var appState: AppState?

    /// Start audio capture and kick off decode + waterfall loops.
    func start(appState: AppState) async {
        guard !isRunning else { return }

        let granted = await audio.requestPermission()
        guard granted else { return }

        do {
            try audio.start()
        } catch {
            return
        }

        isRunning = true
        self.appState = appState
        appState.waterfall.isLive = true

        let accumulator = audio.accumulator
        let rxOffset = rxOffsetMs

        // Build the MainActor state-mutation closures here, on the main actor, so
        // the background loops never reference `AppState` directly. The closures
        // are `@Sendable @MainActor`: they may be handed to the detached task, but
        // their captured `AppState` is only ever touched back on the main actor.
        let applyDecodes: @Sendable @MainActor ([DecodeMessage]) -> Void = { messages in
            appState.decode.messages.insert(contentsOf: messages, at: 0)
            // Trim to keep a reasonable history.
            if appState.decode.messages.count > 200 {
                appState.decode.messages.removeLast(appState.decode.messages.count - 200)
            }
        }
        let applyWaterfall: @Sendable @MainActor ([UInt8], [Float]) -> Void = { row, spectrum in
            appState.waterfall.rows.append(row)
            // Keep at most 120 rows for the scrolling waterfall.
            if appState.waterfall.rows.count > 120 {
                appState.waterfall.rows.removeFirst(appState.waterfall.rows.count - 120)
            }
            appState.waterfall.spectrum = spectrum
        }

        engineTask = Task.detached { [weak self] in
            await withTaskGroup(of: Void.self) { group in
                // Decode pipeline
                group.addTask {
                    await self?.runDecodeLoop(
                        accumulator: accumulator,
                        initialRxOffset: rxOffset,
                        applyDecodes: applyDecodes
                    )
                }
                // Waterfall pipeline
                group.addTask {
                    await self?.runWaterfallLoop(
                        accumulator: accumulator,
                        applyWaterfall: applyWaterfall
                    )
                }
            }
        }
    }

    /// Stop audio capture and cancel background tasks.
    func stop() {
        engineTask?.cancel()
        engineTask = nil
        audio.stop()
        isRunning = false
        appState?.waterfall.isLive = false
        appState = nil
    }

    // MARK: - Decode loop

    /// Polls for slot boundaries and runs the FT8 decoder at each transition.
    private nonisolated func runDecodeLoop(
        accumulator: SlotAccumulator,
        initialRxOffset: Int64,
        applyDecodes: @escaping @Sendable @MainActor ([DecodeMessage]) -> Void
    ) async {
        var rxOffsetMs = initialRxOffset
        var lastSlotID: Int64 = SlotClock.rxSlotID(
            atUtcMs: Int64(Date().timeIntervalSince1970 * 1000),
            rxOffsetMs: rxOffsetMs
        )

        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(200))
            if Task.isCancelled { break }

            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let currentSlot = SlotClock.rxSlotID(atUtcMs: nowMs, rxOffsetMs: rxOffsetMs)

            guard currentSlot != lastSlotID else { continue }
            lastSlotID = currentSlot

            // Extract the slot's audio and decode.
            let samples = accumulator.takeSlot()
            let decoder = FT8Decoder()
            decoder.feedSlot(samples)
            decoder.findSync()
            let decoded = decoder.decodeAll()

            // DT calibration from this slot's decodes.
            if !decoded.isEmpty {
                let dtValues = decoded.map(\.timeSec)
                rxOffsetMs = DtCalibrator.calibrate(
                    rxOffsetMs: rxOffsetMs,
                    decodedDtSec: dtValues
                )
            }

            // Map to UI messages and update state on MainActor.
            let utcTime = Self.utcTimeString(from: nowMs)
            let slotIndex = Int(SlotClock.parity(slotID: currentSlot))
            let uiMessages = decoded.map { msg in
                DecodeMessage(
                    utcTime: utcTime,
                    callFrom: msg.callFrom,
                    callTo: msg.callTo,
                    snr: msg.snr,
                    freqHz: msg.freqHz,
                    grid: msg.grid,
                    extra: msg.extra,
                    slotIndex: slotIndex
                )
            }

            if !uiMessages.isEmpty {
                await MainActor.run { applyDecodes(uiMessages) }
            }
        }
    }

    // MARK: - Waterfall loop

    /// Runs ~4x per second, building FFT rows and updating the waterfall +
    /// spectrum state.
    private nonisolated func runWaterfallLoop(
        accumulator: SlotAccumulator,
        applyWaterfall: @escaping @Sendable @MainActor ([UInt8], [Float]) -> Void
    ) async {
        let sampleRate = Int(FT8.sampleRate)
        let needed = WaterfallRowBuilder.samplesNeeded()
        let columns = WaterfallRowBuilder.columns(sampleRate: sampleRate)
        var builder = WaterfallRowBuilder()

        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(250))
            if Task.isCancelled { break }

            let samples = accumulator.peekRecent(needed)
            guard samples.count >= needed else { continue }

            // Compute Welch-averaged power spectrum.
            let power = FFTProcessor.welchPower(
                samples: samples,
                columns: columns
            )

            // Build the brightness row.
            let row = builder.buildRow(summedPower: power, sampleRate: sampleRate)

            // Normalize power for spectrum strip display (0...1 range).
            let maxPower = power.max() ?? 1.0
            let scale = maxPower > 0 ? 1.0 / maxPower : 1.0
            let spectrum = power.map { min($0 * scale, 1.0) }

            await MainActor.run { applyWaterfall(row.bins, spectrum) }
        }
    }

    // MARK: - Helpers

    private nonisolated static func utcTimeString(from epochMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(epochMs) / 1000.0)
        let fmt = DateFormatter()
        fmt.dateFormat = "HH:mm:ss"
        fmt.timeZone = TimeZone(identifier: "UTC")
        return fmt.string(from: date)
    }
}
