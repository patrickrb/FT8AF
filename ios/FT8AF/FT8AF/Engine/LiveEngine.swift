import FT8Audio
import FT8DSP
import FT8Engine
import Foundation
import Observation

/// Top-level coordinator that owns audio capture, the QSO auto-sequencer, and
/// the TX player. Runs the decode + waterfall pipelines as concurrent
/// structured tasks.  Lifecycle: create once, call `start(appState:)`, and
/// later `stop()`.
@Observable @MainActor
final class LiveEngine {
    private(set) var isRunning = false

    private let audio = AudioCaptureService()
    private let txPlayer = TxPlayerService()
    private var engineTask: Task<Void, Never>?
    private var rxOffsetMs: Int64 = 0

    // WSJT-X UDP interface (broadcast + inbound requests).
    private let udp = WsjtxUdpService()
    private var udpStatusTask: Task<Void, Never>?
    private var lastUdpConfig: WsjtxUdpService.Config?
    private var lastUdpBand = ""
    private var lastUdpHeartbeatMs: Int64 = 0
    /// Weakly held so `stop()` can clear the LIVE indicator it set in `start()`.
    private weak var appState: AppState?

    // QSO engine — created once start() has settings available.
    private var qsoEngine: QsoEngine?

    // MARK: - Public control methods (called from UI)

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

        // Attach the TX player node to the shared AVAudioEngine.
        txPlayer.configure(engine: audio.audioEngine)

        isRunning = true
        self.appState = appState
        appState.waterfall.isLive = true

        // Initialize the QSO engine from current settings.
        let settings = appState.settings
        let qso = QsoEngine(myCall: settings.myCall, myGrid: settings.myGrid)
        qso.band = settings.band
        qso.freqMhz = bandToFreqMhz(settings.band)
        qsoEngine = qso

        // Bring up the WSJT-X UDP interface for this session.
        setupUdp(appState: appState)

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
            appState.waterfall.updateCount += 1
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
        udpStatusTask?.cancel()
        udpStatusTask = nil
        udp.stop()
        lastUdpConfig = nil
        txPlayer.stop()
        audio.stop()
        isRunning = false
        appState?.waterfall.isLive = false
        appState = nil
        qsoEngine = nil
    }

    /// Begin calling CQ.
    func callCQ() {
        syncSettingsToQso()
        appState?.tx.conversationLog.removeAll()
        qsoEngine?.startCq()
        syncQsoStatusToUI()
    }

    /// Stop any active TX/QSO.
    func stopTx() {
        qsoEngine?.stop()
        txPlayer.stop()
        syncQsoStatusToUI()
    }

    /// Answer a station from a decoded message (user tapped "Call" in QsoSheet).
    func answerStation(_ msg: DecodeMessage) {
        syncSettingsToQso()
        appState?.tx.conversationLog.removeAll()
        let decoded = FT8DSP.DecodedMessage(
            callTo: msg.callTo,
            callFrom: msg.callFrom,
            extra: msg.extra,
            grid: msg.grid,
            rawText: "",
            snr: msg.snr,
            freqHz: msg.freqHz,
            timeSec: 0,
            score: 0,
            i3: 0,
            n3: 0,
            a91: [UInt8](repeating: 0, count: 12)
        )
        qsoEngine?.answer(decoded)
        syncQsoStatusToUI()
    }

    /// Toggle hunt mode (auto-answer incoming CQs).
    func toggleHunt() {
        guard let appState else { return }
        appState.tx.huntEnabled.toggle()
    }

    /// Switch TX slot parity (TX1 ↔ TX2).
    func toggleSlotParity() {
        guard let appState else { return }
        appState.tx.slotParity = appState.tx.slotParity == 0 ? 1 : 0
    }

    // MARK: - Decode loop

    /// Polls for slot boundaries and runs the FT8 decoder at each transition.
    /// After decoding, feeds results to the QSO engine and handles TX scheduling.
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

            // Feed decoded messages to QSO engine and handle TX on main actor.
            await MainActor.run { [decoded] in
                self.processQsoRx(decoded, slotID: currentSlot)
            }
        }
    }

    // MARK: - QSO processing + TX scheduling (MainActor)

    /// Process decoded messages through the QSO engine and schedule TX if needed.
    private func processQsoRx(_ decoded: [DecodedMessage], slotID: Int64) {
        guard let qso = qsoEngine, let appState else { return }

        // Sync latest settings into the engine before processing.
        syncSettingsToQso()

        // Broadcast this slot's decodes over the WSJT-X UDP interface.
        broadcastUdpDecodes(decoded)

        // Log incoming RX messages addressed to us for the conversation panel.
        let myCall = appState.settings.myCall.uppercased()
        if !myCall.isEmpty {
            for msg in decoded where msg.callTo.uppercased() == myCall {
                let utcTime = Self.utcTimeString(from: Int64(Date().timeIntervalSince1970 * 1000))
                appState.tx.conversationLog.append(
                    QsoLogEntry(direction: .rx, message: msg.rawText.isEmpty ? "\(msg.callTo) \(msg.callFrom) \(msg.extra)" : msg.rawText,
                                snr: msg.snr, utcTime: utcTime)
                )
            }
        }

        // Capture pre-RX target to detect new QSO answers.
        let previousTarget = qso.status().target

        // Feed decodes to the QSO sequencer.
        let outcome = qso.processRx(decoded)

        // Auto-open QSO sheet when our CQ gets answered (target goes from nil to non-nil).
        let newTarget = qso.status().target
        if previousTarget == nil, let dx = newTarget {
            // Find the decoded message from this station to use as autoOpenMessage.
            if let answerMsg = decoded.first(where: { $0.callFrom.uppercased() == dx.uppercased() }) {
                appState.tx.autoOpenMessage = DecodeMessage(
                    utcTime: Self.utcTimeString(from: Int64(Date().timeIntervalSince1970 * 1000)),
                    callFrom: answerMsg.callFrom,
                    callTo: answerMsg.callTo,
                    snr: answerMsg.snr,
                    freqHz: answerMsg.freqHz,
                    grid: answerMsg.grid,
                    extra: answerMsg.extra,
                    slotIndex: Int(SlotClock.parity(slotID: slotID))
                )
            }
        }

        // Handle QSO completion.
        if case .completed(var record) = outcome {
            // Assign a unique ID based on the current record count.
            let nextId = Int64((appState.logbook.records.map { $0.id ?? 0 }.max() ?? 0) + 1)
            record.id = nextId
            appState.logbook.records.insert(record, at: 0)
            QsoLogStore.save(appState.logbook.records)
            // Broadcast the logged QSO over the WSJT-X UDP interface.
            broadcastUdpQso(record)
            // Trigger QSO celebration animation.
            appState.tx.qsoCompletedAt = Date()
        }

        // Sync QSO status back to UI.
        syncQsoStatusToUI()

        // TX scheduling: if QSO engine has a message and slot parity matches, transmit.
        if let txMsg = qso.txMessage {
            let nextSlotParity = Int(SlotClock.parity(slotID: slotID + 1))
            let desiredParity = appState.tx.slotParity
            if nextSlotParity == desiredParity {
                scheduleTx(message: txMsg, freqHz: appState.waterfall.txFreqHz)
            }
        }
    }

    /// Generate FT8 audio and play it through the speaker.
    private func scheduleTx(message: String, freqHz: Float) {
        guard let appState else { return }
        guard let samples = FT8Encoder.generateFT8(message, baseFreqHz: freqHz) else { return }

        // Log the TX message to conversation panel.
        let utcTime = Self.utcTimeString(from: Int64(Date().timeIntervalSince1970 * 1000))
        appState.tx.conversationLog.append(
            QsoLogEntry(direction: .tx, message: message, snr: nil, utcTime: utcTime)
        )

        appState.tx.isTransmitting = true
        txPlayer.play(samples) { [weak self] in
            Task { @MainActor in
                self?.appState?.tx.isTransmitting = false
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

    /// Push current user settings into the QSO engine.
    private func syncSettingsToQso() {
        guard let qso = qsoEngine, let appState else { return }
        let s = appState.settings
        qso.myCall = s.myCall.uppercased()
        qso.myGrid = s.myGrid.uppercased()
        qso.band = s.band
        qso.freqMhz = bandToFreqMhz(s.band)
    }

    /// Reflect QSO engine status into the UI TxState.
    private func syncQsoStatusToUI() {
        guard let qso = qsoEngine, let appState else { return }
        let status = qso.status()
        appState.tx.isActivated = status.active
        appState.tx.stage = mapStage(status.stage)
        appState.tx.targetCall = status.target ?? ""
        appState.tx.txMessage = status.txMessage ?? ""
    }

    private func mapStage(_ stage: TxStage) -> TxUIStage {
        switch stage {
        case .idle: return .idle
        case .cq: return .cqSent
        case .grid, .report: return .reportSent
        case .rReport, .rr73: return .rrSent
        case .bye73: return .complete
        }
    }

    /// Map amateur band name to a common FT8 frequency in MHz.
    private func bandToFreqMhz(_ band: String) -> String {
        switch band {
        case "160M": return "1.840"
        case "80M": return "3.573"
        case "60M": return "5.357"
        case "40M": return "7.074"
        case "30M": return "10.136"
        case "20M": return "14.074"
        case "17M": return "18.100"
        case "15M": return "21.074"
        case "12M": return "24.915"
        case "10M": return "28.074"
        case "6M": return "50.313"
        case "2M": return "144.174"
        default: return "14.074"
        }
    }

    private nonisolated static func utcTimeString(from epochMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(epochMs) / 1000.0)
        let fmt = DateFormatter()
        fmt.dateFormat = "HH:mm:ss"
        fmt.timeZone = TimeZone(identifier: "UTC")
        return fmt.string(from: date)
    }

    // MARK: - WSJT-X UDP interface

    /// Wire the UDP service's inbound handlers to the transmit path, apply the
    /// current settings, and start the periodic Status/Heartbeat loop.
    private func setupUdp(appState: AppState) {
        udp.onReply = { [weak self] call, grid, snr in
            guard let self, let appState = self.appState else { return }
            let msg = DecodeMessage(
                utcTime: Self.utcTimeString(from: Int64(Date().timeIntervalSince1970 * 1000)),
                callFrom: call, callTo: "", snr: Int(snr),
                freqHz: appState.waterfall.txFreqHz, grid: grid, extra: "", slotIndex: 0
            )
            self.answerStation(msg)
        }
        udp.onHaltTx = { [weak self] _ in self?.stopTx() }
        udp.onFreeText = { [weak self] text, send in
            guard let self, send else { return }
            self.qsoEngine?.setFreeText(text)
            self.syncQsoStatusToUI()
        }
        udp.onReplay = {} // decodes are re-sent inside the service

        applyUdpConfig(appState.settings)
        udp.sendClear()
        udp.clearReplayCache()
        lastUdpBand = appState.settings.band

        udpStatusTask?.cancel()
        udpStatusTask = Task { [weak self] in
            while !Task.isCancelled {
                self?.tickUdp()
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    private func applyUdpConfig(_ s: SettingsState) {
        let cfg = WsjtxUdpService.Config(
            enabled: s.udpEnabled,
            host: s.udpHost,
            port: UInt16(exactly: s.udpPort) ?? 2237,
            acceptRequests: s.udpAcceptRequests
        )
        lastUdpConfig = cfg
        udp.apply(cfg)
    }

    /// Runs ~1 Hz: re-applies config if the operator changed it, then sends a
    /// Status (and a Heartbeat every ~15 s); clears companions on a band change.
    private func tickUdp() {
        guard let appState else { return }
        applyUdpConfigIfChanged(appState.settings)
        guard udp.isEnabled else { return }
        if appState.settings.band != lastUdpBand {
            lastUdpBand = appState.settings.band
            udp.sendClear()
            udp.clearReplayCache()
        }
        udp.sendStatus(buildUdpStatus())
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        if nowMs - lastUdpHeartbeatMs >= 15_000 {
            lastUdpHeartbeatMs = nowMs
            udp.sendHeartbeat(version: Self.appVersion)
        }
    }

    private func applyUdpConfigIfChanged(_ s: SettingsState) {
        let cfg = WsjtxUdpService.Config(
            enabled: s.udpEnabled, host: s.udpHost,
            port: UInt16(exactly: s.udpPort) ?? 2237, acceptRequests: s.udpAcceptRequests
        )
        if cfg != lastUdpConfig {
            lastUdpConfig = cfg
            udp.apply(cfg)
        }
    }

    private func buildUdpStatus() -> WsjtxCodec.Status {
        let s = appState?.settings
        let qsoStatus = qsoEngine?.status()
        let dialMhz = Double(bandToFreqMhz(s?.band ?? "20M")) ?? 14.074
        let df = UInt32(max(0, Int(appState?.waterfall.txFreqHz ?? 1500)))
        return WsjtxCodec.Status(
            dialFreqHz: UInt64(dialMhz * 1_000_000), mode: "FT8",
            dxCall: qsoStatus?.target ?? "", report: "", txMode: "FT8",
            txEnabled: qsoStatus?.active ?? false,
            transmitting: appState?.tx.isTransmitting ?? false, decoding: isRunning,
            rxDf: df, txDf: df, deCall: s?.myCall ?? "", deGrid: s?.myGrid ?? "",
            dxGrid: "", txWatchdog: false, subMode: "", fastMode: false,
            specialOpMode: 0, freqTolerance: 0xFFFF_FFFF, trPeriod: 15,
            configName: "Default", txMessage: qsoStatus?.txMessage ?? ""
        )
    }

    private func broadcastUdpDecodes(_ decoded: [DecodedMessage]) {
        guard udp.isEnabled, !decoded.isEmpty else { return }
        let timeMs = UInt32(Int64(Date().timeIntervalSince1970 * 1000) % 86_400_000)
        for m in decoded {
            let text = m.rawText.isEmpty ? "\(m.callTo) \(m.callFrom) \(m.extra)" : m.rawText
            udp.sendDecode(WsjtxCodec.Decode(
                isNew: true, timeMs: timeMs, snr: Int32(m.snr), deltaTime: Double(m.timeSec),
                deltaFreq: UInt32(max(0, Int(m.freqHz))), mode: "FT8", message: text,
                lowConfidence: false, offAir: false
            ))
        }
    }

    private func broadcastUdpQso(_ record: QsoRecord) {
        guard udp.isEnabled else { return }
        let dialMhz = Double(record.freq) ?? (Double(bandToFreqMhz(record.band)) ?? 14.074)
        let txFreqHz = UInt64(dialMhz * 1_000_000)
            + UInt64(max(0, Int(appState?.waterfall.txFreqHz ?? 0)))
        let q = WsjtxCodec.QsoLogged(
            timeOff: .fromAdif(record.qsoDateOff, record.timeOff),
            dxCall: record.call, dxGrid: record.gridsquare, txFreqHz: txFreqHz,
            mode: record.mode, reportSent: record.rstSent, reportReceived: record.rstRcvd,
            txPower: "", comments: record.comment, name: "",
            timeOn: .fromAdif(record.qsoDate, record.timeOn),
            operatorCall: record.stationCallsign, myCall: record.stationCallsign,
            myGrid: record.myGridsquare, exchangeSent: "", exchangeReceived: "", propMode: ""
        )
        udp.sendQsoLogged(q, adif: Adif.export([record]))
    }

    private static var appVersion: String {
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "1.0"
    }
}

/// Initializer extension on DecodedMessage for constructing from individual
/// fields (used when mapping UI DecodeMessage back to the DSP type).
extension FT8DSP.DecodedMessage {
    init(callTo: String, callFrom: String, extra: String, grid: String,
         rawText: String, snr: Int, freqHz: Float, timeSec: Float,
         score: Int, i3: UInt8, n3: UInt8, a91: [UInt8]) {
        self.init()
        self.callTo = callTo
        self.callFrom = callFrom
        self.extra = extra
        self.grid = grid
        self.rawText = rawText
        self.snr = snr
        self.freqHz = freqHz
        self.timeSec = timeSec
        self.score = score
        self.i3 = i3
        self.n3 = n3
        self.a91 = a91
    }
}
