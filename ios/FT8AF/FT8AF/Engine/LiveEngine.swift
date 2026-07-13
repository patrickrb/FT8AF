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

    // TX safety nets (watchdog + stop-after-N) — pure counting in the kit type.
    private var supervisor = TxSupervisor(nowMs: 0)
    // Stations that called us while we were busy — pure policy in the kit type.
    private var callerQueue = CallerQueue()
    // Pending delayed TX start (honors txDelayMs); cancelled by stopTx().
    private var txScheduleTask: Task<Void, Never>?
    // TUNE carrier countdown ticker.
    private var tuneTask: Task<Void, Never>?

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
        qso.autoReturnToCq = settings.autoCQAfterQSO
        qsoEngine = qso

        // Fresh TX safety-net + caller-queue state for this session.
        supervisor = TxSupervisor(nowMs: Int64(Date().timeIntervalSince1970 * 1000))
        callerQueue.removeAll()
        appState.tx.queuedCallers = []

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
        // Live spectrum-width source for the waterfall loop: read on the main
        // actor each tick so a settings change applies without restarting.
        let readSpectrumWidthHz: @Sendable @MainActor () -> Int = {
            appState.settings.spectrumWidthHz
        }
        let applyWaterfall: @Sendable @MainActor (WaterfallUpdate) -> Void = { update in
            let wf = appState.waterfall
            // Width changed live: the new rows span a different band, so drop
            // the old-width history rather than draw it against the new axis.
            if wf.displayMaxHz != update.displayMaxHz {
                wf.displayMaxHz = update.displayMaxHz
                wf.rows.removeAll()
                wf.rowTimestamps.removeAll()
            }
            wf.rows.append(update.bins)
            wf.rowTimestamps.append(update.timestamp)
            // Keep at most 120 rows for the scrolling waterfall (timestamps
            // stay in lockstep with the rows they annotate).
            if wf.rows.count > 120 {
                let overflow = wf.rows.count - 120
                wf.rows.removeFirst(overflow)
                wf.rowTimestamps.removeFirst(min(overflow, wf.rowTimestamps.count))
            }
            wf.spectrum = update.spectrum
            wf.inputPeak = update.inputPeak
            wf.inputRms = update.inputRms
            wf.updateCount += 1
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
                        readSpectrumWidthHz: readSpectrumWidthHz,
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
        txScheduleTask?.cancel()
        txScheduleTask = nil
        finishTune()
        txPlayer.stop()
        audio.stop()
        isRunning = false
        appState?.waterfall.isLive = false
        appState = nil
        qsoEngine = nil
    }

    /// Begin calling CQ.
    func callCQ() {
        stopTune()
        syncSettingsToQso()
        appState?.tx.conversationLog.removeAll()
        appState?.tx.targetSnr = nil
        qsoEngine?.startCq()
        armSupervisor()
        syncQsoStatusToUI()
    }

    /// Stop any active TX/QSO.
    func stopTx() {
        txScheduleTask?.cancel()
        txScheduleTask = nil
        qsoEngine?.stop()
        txPlayer.stop()
        appState?.tx.isTransmitting = false
        appState?.tx.targetSnr = nil
        callerQueue.removeAll()
        syncQsoStatusToUI()
    }

    /// Answer a station from a decoded message (user tapped "Call" in QsoSheet).
    func answerStation(_ msg: DecodeMessage) {
        stopTune()
        syncSettingsToQso()
        appState?.tx.conversationLog.removeAll()
        appState?.tx.targetSnr = msg.snr
        callerQueue.remove(callsign: msg.callFrom)
        armSupervisor()
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

    /// Operator picked a standard message chip (CQ/GRID/RPT/R-RPT/RR73/73)
    /// in the Active QSO panel.
    func selectTxStage(_ stage: TxStage) {
        guard let qso = qsoEngine else { return }
        syncSettingsToQso()
        qso.setStage(stage)
        supervisor.resetAttempts()
        syncQsoStatusToUI()
    }

    /// Force-log the current QSO (LOG action) and move on: to `nextCallsign`
    /// when the operator tapped a queued caller, otherwise to the head of the
    /// caller queue, otherwise back to CQ / stop per the auto-CQ setting.
    /// Mirrors Android `forceLogAndMoveOn`.
    func forceLogAndMoveOn(nextCallsign: String? = nil) {
        guard let qso = qsoEngine, let appState else { return }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)

        if case .completed(let record)? = qso.forceLog() {
            logCompletedQso(record)
        }
        appState.tx.targetSnr = nil
        appState.tx.conversationLog.removeAll()
        supervisor.resetAttempts()
        if appState.settings.autoCQAfterQSO {
            supervisor.resetWatchdog(nowMs: nowMs)
        }

        // Work the next caller: the tapped one, or the head of the queue.
        let next: QueuedCaller? = nextCallsign != nil
            ? callerQueue.dequeue(callsign: nextCallsign!)
            : callerQueue.dequeueNext()
        if let caller = next {
            startQsoWithCaller(caller, nowMs: nowMs)
        }
        syncQsoStatusToUI()
    }

    /// Abandon the current target without logging (✕ action): back to the CQ
    /// baseline or idle per the auto-CQ setting. Mirrors Android `userResetToCQ`.
    func clearActiveQso() {
        guard let qso = qsoEngine, let appState else { return }
        qso.abandonToCq()
        appState.tx.targetSnr = nil
        appState.tx.conversationLog.removeAll()
        supervisor.resetAttempts()
        supervisor.resetWatchdog(nowMs: Int64(Date().timeIntervalSince1970 * 1000))
        syncQsoStatusToUI()
    }

    // MARK: - TUNE carrier

    /// Latch/unlatch a steady carrier at the current TX audio frequency for
    /// antenna/amplifier tuning. Auto-stops at the settings timeout; the
    /// buffer itself is capped at the timeout, so playback ending enforces the
    /// hard max-on time even if the countdown ticker dies.
    func toggleTune() {
        guard let appState else { return }
        if appState.tx.isTuning {
            stopTune()
            appState.toast.show("Tune stopped", icon: "dot.radiowaves.right")
        } else {
            startTune()
        }
    }

    private func startTune() {
        guard let appState else { return }
        guard isRunning else {
            appState.toast.show("Start RX before tuning", icon: "exclamationmark.triangle")
            return
        }
        // Tune and FT8 TX are mutually exclusive (the TUNE pill is also
        // disabled in the UI while the sequencer is armed).
        guard !appState.tx.isActivated, !appState.tx.isTransmitting else { return }

        let timeout = max(1, appState.settings.tuneTimeoutSec)
        let samples = TuneTone.samples(
            freqHz: appState.waterfall.txFreqHz,
            sampleRate: Int(FT8.sampleRate),
            durationSec: timeout
        )
        guard !samples.isEmpty else { return }

        appState.tx.isTuning = true
        appState.tx.tuneRemainingSec = timeout
        appState.toast.show("Tuning at \(Int(appState.waterfall.txFreqHz)) Hz", icon: "dot.radiowaves.right")
        txPlayer.play(samples) { [weak self] in
            Task { @MainActor in self?.finishTune() }
        }
        tuneTask?.cancel()
        tuneTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, let self, let appState = self.appState,
                      appState.tx.isTuning else { return }
                appState.tx.tuneRemainingSec = max(0, appState.tx.tuneRemainingSec - 1)
            }
        }
    }

    /// Stop the tune carrier if one is active (tap, timeout, or TX taking over).
    func stopTune() {
        guard let appState, appState.tx.isTuning else { return }
        txPlayer.stop()
        finishTune()
    }

    /// Idempotent tune teardown (called from stop-tap, playback completion,
    /// and engine stop).
    private func finishTune() {
        tuneTask?.cancel()
        tuneTask = nil
        guard let appState, appState.tx.isTuning else { return }
        appState.tx.isTuning = false
        appState.tx.tuneRemainingSec = 0
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

        // Hand this slot's decodes to the PSK Reporter queue (policy-filtered,
        // rate-limited per callsign/band, flushed every ~5 min when enabled).
        let dialHz = Int64((Double(bandToFreqMhz(appState.settings.band)) ?? 14.074) * 1_000_000)
        OnlineLogService.shared.enqueuePskDecodes(decoded, dialFreqHz: dialHz, appState: appState)

        let settings = appState.settings
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)

        // Log incoming RX messages addressed to us for the conversation panel.
        let myCall = settings.myCall.uppercased()
        if !myCall.isEmpty {
            for msg in decoded where msg.callTo.uppercased() == myCall {
                let utcTime = Self.utcTimeString(from: nowMs)
                appState.tx.conversationLog.append(
                    QsoLogEntry(direction: .rx, message: msg.rawText.isEmpty ? "\(msg.callTo) \(msg.callFrom) \(msg.extra)" : msg.rawText,
                                snr: msg.snr, utcTime: utcTime)
                )
            }
        }

        // Track the last SNR heard from the current target for the QSO header.
        if let dx = qso.status().target?.uppercased(),
           let m = decoded.last(where: { $0.callFrom.uppercased() == dx }) {
            appState.tx.targetSnr = m.snr
        }

        // Hunt / auto-call-follow: lock a station while we have no target.
        autoAnswerIfHunting(decoded, slotID: slotID, nowMs: nowMs)

        // Caller queue: stations calling me that aren't the current target
        // wait their turn while we're busy (in a QSO or running CQ).
        enqueueCallers(decoded, nowMs: nowMs)

        // Capture pre-RX target to detect new QSO answers.
        let previousTarget = qso.status().target

        // A reply from the target (or any answer to our CQ) resets the
        // stop-after-N no-reply streak.
        if !myCall.isEmpty, decoded.contains(where: { m in
            m.callTo.uppercased() == myCall
                && (previousTarget == nil || m.callFrom.uppercased() == previousTarget!.uppercased())
        }) {
            supervisor.noteReply()
        }

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

        // The engine locked a caller on its own — their queue slot is obsolete,
        // and a fresh target restarts the safety-net clocks.
        if let dx = qso.status().target,
           previousTarget?.uppercased() != dx.uppercased() {
            callerQueue.remove(callsign: dx)
            armSupervisor()
        }

        // Handle QSO completion.
        if case .completed(let record) = outcome {
            logCompletedQso(record)
            // Auto-CQ keeps the run going, so refresh the watchdog on each
            // completed QSO (mirrors Android).
            if settings.autoCQAfterQSO {
                supervisor.resetWatchdog(nowMs: nowMs)
            }
        }

        // Previous QSO fully wrapped up → work the next queued caller.
        serviceCallerQueue(nowMs: nowMs)

        // A hunt that is idle-listening (no target locked, not CQing) is
        // behaving correctly — keep the watchdog fresh (mirrors Android).
        if appState.tx.huntEnabled, qso.status().target == nil, !settings.huntCallsCQ {
            supervisor.resetWatchdog(nowMs: nowMs)
        }

        // Sync QSO status back to UI.
        syncQsoStatusToUI()

        // TX scheduling: if the QSO engine has a message and this slot matches the
        // operator's selected TX parity, transmit. The audio keys up immediately,
        // so it plays in `slotID` itself — gate on that slot's parity (mirrors the
        // desktop engine's `maybe_transmit`), not the next slot's.
        // The TX safety nets run first: watchdog (N minutes of continuous TX
        // cycles) and stop-after-N (same message repeatedly unanswered).
        if let txMsg = qso.txMessage {
            if let reason = supervisor.stopReason(
                nowMs: nowMs,
                watchdogMin: settings.txWatchdogMin,
                stopAfterAttempts: settings.stopAfterAttempts
            ) {
                stopTx()
                switch reason {
                case .watchdog:
                    appState.toast.show(
                        "TX watchdog: stopped after \(settings.txWatchdogMin) min",
                        icon: "exclamationmark.triangle")
                case .noReply:
                    appState.toast.show(
                        "No reply after \(settings.stopAfterAttempts) attempts — TX stopped",
                        icon: "stop.circle")
                }
            } else if SlotClock.shouldTransmit(slotID: slotID, desiredParity: appState.tx.slotParity) {
                scheduleTx(message: txMsg, freqHz: appState.waterfall.txFreqHz)
                supervisor.noteTransmission(txMsg)
            }
        }
    }

    /// Auto-answer while no target is locked: HUNT answers other stations'
    /// CQs (skipping blocked and already-worked calls); the auto-call-follow
    /// setting answers stations calling us directly while we're idle; the
    /// hunt-calls-CQ setting turns a caller-less hunt into a CQ run.
    private func autoAnswerIfHunting(_ decoded: [DecodedMessage], slotID: Int64, nowMs: Int64) {
        guard let qso = qsoEngine, let appState else { return }
        let settings = appState.settings
        let myCall = settings.myCall.uppercased()
        guard myCall.count >= 3 else { return }
        guard qso.status().target == nil else { return }

        let blocked = settings.blockedCallsigns.map { $0.uppercased() }
        let worked = Set(appState.logbook.records.map { $0.call.uppercased() })
        func eligible(_ m: DecodedMessage) -> Bool {
            let from = m.callFrom.uppercased()
            return !from.isEmpty && from != myCall
                && !from.contains("<") && !blocked.contains(from)
        }

        var candidate: DecodedMessage?
        if appState.tx.huntEnabled {
            // Strongest un-worked station calling CQ.
            candidate = decoded
                .filter { $0.callTo.uppercased().hasPrefix("CQ") && eligible($0)
                    && !worked.contains($0.callFrom.uppercased()) }
                .max { $0.snr < $1.snr }
        }
        if candidate == nil, settings.autoCallFollow, !qso.active {
            // Someone calling us directly while we're idle.
            candidate = decoded
                .filter { $0.callTo.uppercased() == myCall && eligible($0) }
                .max { $0.snr < $1.snr }
        }

        if let msg = candidate {
            appState.tx.conversationLog.removeAll()
            qso.answer(msg)
            // They transmitted in the slot that just ended, so reply in slots
            // of the opposite parity — i.e. the parity of the slot now starting.
            appState.tx.slotParity = Int(SlotClock.parity(slotID: slotID))
            appState.tx.targetSnr = msg.snr
            armSupervisor()
            appState.toast.show("Answering \(msg.callFrom.uppercased())", icon: "target")
        } else if appState.tx.huntEnabled, settings.huntCallsCQ, !qso.active {
            // Hunting with no callers → call CQ ourselves.
            qso.startCq()
            armSupervisor()
        }
    }

    /// Queue stations calling me that aren't the current target (dedup by
    /// call, stale entries pruned) and mirror the queue into TxState.
    private func enqueueCallers(_ decoded: [DecodedMessage], nowMs: Int64) {
        guard let qso = qsoEngine, let appState else { return }
        defer { appState.tx.queuedCallers = callerQueue.callers.map(\.callsign) }
        callerQueue.removeStale(nowMs: nowMs)
        guard qso.active else { return }
        let myCall = appState.settings.myCall.uppercased()
        guard !myCall.isEmpty else { return }

        let target = qso.status().target
        for m in decoded where m.callTo.uppercased() == myCall {
            guard CallerQueue.shouldEnqueue(
                callsign: m.callFrom,
                myCall: myCall,
                currentTarget: target,
                blocked: appState.settings.blockedCallsigns
            ) else { continue }
            callerQueue.enqueue(QueuedCaller(
                callsign: m.callFrom.uppercased(),
                snr: m.snr,
                freqHz: m.freqHz,
                grid: m.grid,
                queuedAtMs: nowMs
            ))
        }
    }

    /// When the previous QSO has fully wrapped up (no target locked), start
    /// working the next queued caller. Runs after each slot's RX processing,
    /// so it also covers the courtesy-73 case where completion lands a slot
    /// before the target clears.
    private func serviceCallerQueue(nowMs: Int64) {
        guard let qso = qsoEngine, let appState else { return }
        guard qso.status().target == nil else { return }
        callerQueue.removeStale(nowMs: nowMs)
        guard let next = callerQueue.dequeueNext() else {
            appState.tx.queuedCallers = callerQueue.callers.map(\.callsign)
            return
        }
        startQsoWithCaller(next, nowMs: nowMs)
    }

    /// Begin a QSO with a dequeued caller: they already called us, so if they
    /// sent their grid we answer with a report (Tx2), otherwise with our grid.
    private func startQsoWithCaller(_ caller: QueuedCaller, nowMs: Int64) {
        guard let qso = qsoEngine, let appState else { return }
        appState.tx.conversationLog.removeAll()
        let msg = FT8DSP.DecodedMessage(
            callTo: appState.settings.myCall.uppercased(),
            callFrom: caller.callsign,
            extra: "",
            grid: caller.grid,
            rawText: "",
            snr: caller.snr,
            freqHz: caller.freqHz,
            timeSec: 0,
            score: 0,
            i3: 0,
            n3: 0,
            a91: [UInt8](repeating: 0, count: 12)
        )
        qso.answer(msg)
        if !caller.grid.isEmpty {
            // They already sent their grid — skip ours and send the report.
            qso.setStage(.report)
        }
        appState.tx.targetSnr = caller.snr
        armSupervisor()
        appState.tx.queuedCallers = callerQueue.callers.map(\.callsign)
        appState.toast.show("Working \(caller.callsign)", icon: "person.wave.2")
    }

    /// Persist + broadcast a completed QSO record (shared by the auto-sequence
    /// completion path and the force-log action).
    private func logCompletedQso(_ record: QsoRecord) {
        guard let appState else { return }
        var record = record
        // Assign a unique ID based on the current record count.
        let nextId = Int64((appState.logbook.records.map { $0.id ?? 0 }.max() ?? 0) + 1)
        record.id = nextId
        appState.logbook.records.insert(record, at: 0)
        QsoLogStore.save(appState.logbook.records)
        // Broadcast the logged QSO over the WSJT-X UDP interface.
        broadcastUdpQso(record)
        // Upload to Cloudlog/QRZ when enabled (fire-and-forget with retry;
        // marks the record's synced flags and re-persists on success).
        OnlineLogService.shared.handleQsoLogged(record, appState: appState)
        // Trigger QSO celebration animation.
        appState.tx.qsoCompletedAt = Date()
    }

    /// Restart both TX safety nets (fresh CQ run / new target).
    private func armSupervisor() {
        supervisor.resetWatchdog(nowMs: Int64(Date().timeIntervalSince1970 * 1000))
        supervisor.resetAttempts()
    }

    /// Generate FT8 audio and play it, honoring the operator's TX delay and
    /// the late-start clip rule.
    private func scheduleTx(message: String, freqHz: Float) {
        guard let appState else { return }
        guard !appState.tx.isTuning else { return } // tune and FT8 TX are exclusive
        guard let samples = FT8Encoder.generateFT8(message, baseFreqHz: freqHz) else { return }

        let txDelayMs = max(0, appState.settings.txDelayMs)
        txScheduleTask?.cancel()
        txScheduleTask = Task { @MainActor [weak self] in
            if txDelayMs > 0 {
                try? await Task.sleep(for: .milliseconds(txDelayMs))
            }
            guard !Task.isCancelled else { return }
            self?.beginTxPlayback(message: message, samples: samples)
        }
    }

    /// Start playback now: clip the leading `max(0, msIntoCycle - slack)` ms
    /// when we're late into the 15 s cycle so the TX still ends on the cycle
    /// boundary. An on-time start clips NOTHING — clipping normal starts
    /// destroys the leading Costas sync (see CLAUDE.md, TX pipeline gotcha #2).
    private func beginTxPlayback(message: String, samples: [Float]) {
        guard let appState else { return }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let clipMs = TxTiming.lateStartClipMs(
            msIntoCycle: SlotClock.msIntoCycle(atUtcMs: nowMs),
            slackMs: Int64(appState.settings.lateStartToleranceMs)
        )
        let skip = TxTiming.clipSampleCount(clipMs: clipMs, sampleRate: Int(FT8.sampleRate))
        guard skip < samples.count else { return } // hopelessly late — skip this cycle
        let playSamples = skip > 0 ? Array(samples[skip...]) : samples

        // Log the TX message to conversation panel.
        appState.tx.conversationLog.append(
            QsoLogEntry(direction: .tx, message: message, snr: nil,
                        utcTime: Self.utcTimeString(from: nowMs))
        )

        appState.tx.isTransmitting = true
        txPlayer.play(playSamples) { [weak self] in
            Task { @MainActor in
                self?.appState?.tx.isTransmitting = false
            }
        }
    }

    // MARK: - Waterfall loop

    /// Runs ~4x per second, building FFT rows and updating the waterfall +
    /// spectrum state. Reads the operator's spectrum-width setting each tick so
    /// a change applies live (display-only — the FT8 decoder's fixed range is
    /// untouched), stamps a UTC label on the first row of each 15 s period, and
    /// meters the RX input level for the info-bar indicator.
    private nonisolated func runWaterfallLoop(
        accumulator: SlotAccumulator,
        readSpectrumWidthHz: @escaping @Sendable @MainActor () -> Int,
        applyWaterfall: @escaping @Sendable @MainActor (WaterfallUpdate) -> Void
    ) async {
        let sampleRate = Int(FT8.sampleRate)
        let needed = WaterfallRowBuilder.samplesNeeded()
        var builder = WaterfallRowBuilder()
        var timestampGate = WaterfallTimestampGate()

        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(250))
            if Task.isCancelled { break }

            let displayMaxHz = Float(await MainActor.run { readSpectrumWidthHz() })
            let columns = WaterfallRowBuilder.columns(sampleRate: sampleRate, maxHz: displayMaxHz)

            let samples = accumulator.peekRecent(needed)
            guard samples.count >= needed else { continue }

            // RX input level from the most recent ~250 ms (the window Android
            // meters), classified later by the pure AudioInputLevel.
            let levelWindow = min(samples.count, sampleRate / 4)
            let level = AudioInputLevel.measure(Array(samples.suffix(levelWindow)))

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

            // Stamp the UTC label once on the first row of each FT8 period
            // (label shows the period start, matching Android).
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            var timestamp: String?
            if timestampGate.shouldDraw(utcMs: nowMs) {
                timestamp = WaterfallTimestampGate.utcLabel(
                    forUtcMs: WaterfallTimestampGate.slotStartMs(utcMs: nowMs)
                )
            }

            let update = WaterfallUpdate(
                bins: row.bins,
                spectrum: spectrum,
                displayMaxHz: displayMaxHz,
                timestamp: timestamp,
                inputPeak: level.peak,
                inputRms: level.rms
            )
            await MainActor.run { applyWaterfall(update) }
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
        qso.autoReturnToCq = s.autoCQAfterQSO
    }

    /// Reflect QSO engine status into the UI TxState.
    private func syncQsoStatusToUI() {
        guard let qso = qsoEngine, let appState else { return }
        let status = qso.status()
        appState.tx.isActivated = status.active
        appState.tx.stage = mapStage(status.stage)
        appState.tx.qsoStage = status.stage
        appState.tx.targetCall = status.target ?? ""
        appState.tx.txMessage = status.txMessage ?? ""
        appState.tx.queuedCallers = callerQueue.callers.map(\.callsign)
        if status.target == nil {
            appState.tx.targetSnr = nil
        }
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
        udp.onReply = { [weak self] call, grid, snr, deltaFreq in
            guard let self, let appState = self.appState else { return }
            // Honor the requested audio frequency: answer on the tone the companion asked
            // for, not whatever the current TX frequency happens to be.
            if deltaFreq > 0 { appState.waterfall.txFreqHz = Float(deltaFreq) }
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

/// One tick of the waterfall pipeline, handed from the background loop to the
/// MainActor state application: the new brightness row, the normalized
/// spectrum, the display width the row was built to, an optional UTC label for
/// a 15 s period boundary, and the RX input level of the metering window.
struct WaterfallUpdate: Sendable {
    let bins: [UInt8]
    let spectrum: [Float]
    let displayMaxHz: Float
    let timestamp: String?
    let inputPeak: Float
    let inputRms: Float
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
