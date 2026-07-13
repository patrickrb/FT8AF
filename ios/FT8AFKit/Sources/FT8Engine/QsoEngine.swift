import FT8DSP
import Foundation

/// The last message we transmitted — drives what reply we expect next.
public enum TxStage: String, Codable, Equatable {
    case idle
    case cq
    case grid           // answered a CQ with our grid
    case report         // sent a signal report
    case rReport = "r_report" // sent R + report
    case rr73
    case bye73
}

/// Snapshot of QSO/TX state for the UI.
public struct QsoStatus: Codable, Equatable {
    public var active: Bool
    public var stage: TxStage
    public var target: String?
    public var txMessage: String?
    public var reportSent: Int?
    public var reportRcvd: Int?
}

/// What the engine wants the scheduler to do after processing a slot.
public enum QsoOutcome: Equatable {
    /// A QSO finished and should be logged.
    case completed(QsoRecord)
}

/// Classified content of a decoded reply.
private enum Content {
    case grid(String)
    case report(Int)
    case rReport(Int)
    case rr73
    case rrr
    case bye73
    case other
}

/// FT8 QSO auto-sequencer — a pure state machine ported from desktop qso.rs (in
/// spirit from `FT8TransmitSignal` + `FunctionOfTransmit`). It reacts to each RX
/// slot's decodes and decides the next TX message, covering both roles: calling
/// CQ (initiator) and answering a CQ (answerer). On completion it emits a
/// `QsoRecord` to log. No I/O lives here, so it is fully unit-testable; the
/// scheduler turns its decisions into encode + PTT + DB + events.
public final class QsoEngine {
    public var myCall: String
    public var myGrid: String       // 4-char
    public var band: String = ""
    public var freqMhz: String = ""
    public var active: Bool = false
    public var autoReturnToCq: Bool = true

    private var stage: TxStage = .idle
    private var target: String?
    private var gridRcvd: String?
    private var reportSent: Int?
    private var reportRcvd: Int?
    private var pendingTx: String?
    private var startedMs: Int64 = 0

    public init(myCall: String, myGrid: String) {
        self.myCall = myCall.uppercased()
        self.myGrid = grid4(myGrid).uppercased()
    }

    public func status() -> QsoStatus {
        QsoStatus(active: active, stage: stage, target: target,
                  txMessage: pendingTx, reportSent: reportSent, reportRcvd: reportRcvd)
    }

    /// The message queued for the next TX slot (nil when idle).
    public var txMessage: String? { pendingTx }

    /// Begin calling CQ.
    public func startCq() {
        resetQso()
        active = true
        stage = .cq
        pendingTx = "CQ \(myCall) \(myGrid)"
    }

    /// Begin answering a specific decoded CQ (operator tapped a station).
    public func answer(_ msg: DecodedMessage) {
        resetQso()
        active = true
        target = msg.callFrom.uppercased()
        gridRcvd = msg.grid.isEmpty ? nil : grid4(msg.grid).uppercased()
        reportSent = clampReport(msg.snr)
        stage = .grid
        startedMs = FT8Time.nowUnixMs()
        pendingTx = "\(target!) \(myCall) \(myGrid)"
    }

    /// Operator manually picks which standard message to send next (the WSJT-X
    /// Tx1–Tx5 buttons). Requires an active target; selecting `.cq`/`.idle` falls
    /// through to start-CQ / stop so the same control covers the whole strip.
    public func setStage(_ newStage: TxStage) {
        switch newStage {
        case .cq: return startCq()
        case .idle: return stop()
        default: break
        }
        guard let dx = target else { return } // no station selected yet
        active = true
        switch newStage {
        case .grid:
            pendingTx = "\(dx) \(myCall) \(myGrid)"
            stage = .grid
        case .report: sendReport(dx)
        case .rReport: sendRReport(dx)
        case .rr73: sendRr73(dx)
        case .bye73:
            pendingTx = "\(dx) \(myCall) 73"
            stage = .bye73
        case .cq, .idle: break // handled above
        }
    }

    /// Send a free-text / arbitrary message once.
    public func setFreeText(_ text: String) {
        active = true
        pendingTx = text.uppercased()
    }

    public func stop() {
        active = false
        pendingTx = nil
        stage = .idle
        // Clear the QSO context too, so a later manual setStage() can't re-activate
        // and transmit to a stale target/report from the QSO we just stopped.
        resetQso()
    }

    private func resetQso() {
        target = nil
        gridRcvd = nil
        reportSent = nil
        reportRcvd = nil
        startedMs = FT8Time.nowUnixMs()
    }

    /// Process the decoded messages of a finished RX slot. Updates the queued TX
    /// message for the next slot and returns a completion outcome if a QSO ended.
    public func processRx(_ messages: [DecodedMessage]) -> QsoOutcome? {
        if !active || stage == .idle { return nil }

        // The courtesy "73" queued on the previous slot has now had its TX slot;
        // wrap up (return to CQ / stop) regardless of incoming traffic this slot.
        if stage == .bye73 {
            finishQso()
            return nil
        }

        // Find a message addressed to us, from our target if we have one.
        let mine = messages.first { m in
            m.callTo.uppercased() == myCall
                && (target == nil || m.callFrom.uppercased() == target!)
        }
        guard let msg = mine else { return nil } // no reply this slot; scheduler retransmits

        if target == nil {
            target = msg.callFrom.uppercased()
            startedMs = FT8Time.nowUnixMs()
        }
        let dx = target!
        let content = classify(msg)

        switch stage {
        case .cq:
            switch content {
            case .grid(let g):
                gridRcvd = grid4(g).uppercased()
                reportSent = clampReport(msg.snr)
                sendReport(dx)
            case .report(let n):
                reportRcvd = n
                reportSent = clampReport(msg.snr)
                sendRReport(dx)
            default: break
            }
        case .grid:
            switch content {
            case .report(let n):
                reportRcvd = n
                // Our report reflects the DX signal from this decode (as WSJT-X recomputes).
                reportSent = clampReport(msg.snr)
                sendRReport(dx)
            case .rReport(let n):
                reportRcvd = n
                sendRr73(dx)
            default: break
            }
        case .report:
            switch content {
            case .rReport(let n):
                reportRcvd = n
                sendRr73(dx)
            case .rr73, .rrr, .bye73:
                return complete(dx)
            default: break
            }
        case .rReport:
            switch content {
            case .rr73, .rrr, .bye73:
                // Acknowledge with 73, then log.
                pendingTx = "\(dx) \(myCall) 73"
                stage = .bye73
                return complete(dx)
            default: break
            }
        case .rr73:
            switch content {
            case .bye73, .rr73, .rrr:
                return complete(dx)
            default: break
            }
        case .bye73, .idle:
            break
        }
        return nil
    }

    private func sendReport(_ dx: String) {
        let r = reportSent ?? 0
        pendingTx = "\(dx) \(myCall) \(fmtReport(r))"
        stage = .report
    }

    private func sendRReport(_ dx: String) {
        let r = reportSent ?? 0
        pendingTx = "\(dx) \(myCall) R\(fmtReport(r))"
        stage = .rReport
    }

    private func sendRr73(_ dx: String) {
        pendingTx = "\(dx) \(myCall) RR73"
        stage = .rr73
    }

    /// Force-log the current QSO and move on without waiting for the partner's
    /// 73 (the Active QSO panel's LOG action; port of Android
    /// `forceLogAndMoveOn`). Only produces a record when the QSO progressed far
    /// enough to be a real contact — both reports known, i.e. we have sent
    /// R-report or beyond (Android `shouldForceLog`: order >= 3). Either way
    /// the QSO context is cleared and we return to CQ / stop per
    /// `autoReturnToCq`.
    public func forceLog() -> QsoOutcome? {
        guard let dx = target else { return nil }
        let progressed = stage == .rReport || stage == .rr73 || stage == .bye73
        let outcome: QsoOutcome? = progressed ? .completed(makeRecord(dx)) : nil
        finishQso()
        return outcome
    }

    /// Abandon the current target and return to the CQ baseline (the Active QSO
    /// panel's ✕ action; port of Android `userResetToCQ`). Nothing is logged.
    public func abandonToCq() {
        finishQso()
    }

    private func complete(_ dx: String) -> QsoOutcome {
        let record = makeRecord(dx)

        // If a courtesy "73" is queued (the .rReport path set stage = .bye73),
        // leave pendingTx/stage so the scheduler transmits it; the next slot's
        // .bye73 handler in processRx then wraps up. Otherwise finish now.
        if stage != .bye73 {
            finishQso()
        }
        return .completed(record)
    }

    private func makeRecord(_ dx: String) -> QsoRecord {
        QsoRecord(
            call: dx,
            gridsquare: gridRcvd ?? "",
            mode: "FT8",
            rstSent: reportSent.map(fmtReport) ?? "",
            rstRcvd: reportRcvd.map(fmtReport) ?? "",
            qsoDate: FT8Time.yyyymmdd(fromMs: startedMs),
            timeOn: FT8Time.hhmmss(fromMs: startedMs),
            qsoDateOff: FT8Time.utcYyyymmdd(),
            timeOff: FT8Time.utcHhmmss(),
            band: band,
            freq: freqMhz,
            stationCallsign: myCall,
            myGridsquare: myGrid,
            comment: ""
        )
    }

    /// Return to calling CQ, or stop, per `autoReturnToCq`.
    private func finishQso() {
        if autoReturnToCq {
            resetQso()
            stage = .cq
            pendingTx = "CQ \(myCall) \(myGrid)"
        } else {
            stop()
        }
    }
}

/// FT8 reports are clamped to roughly [-30, +30].
func clampReport(_ snr: Int) -> Int { min(30, max(-30, snr)) }

/// Format a report as a signed, zero-padded 2+ digit string: -9 -> "-09", 5 -> "+05".
func fmtReport(_ n: Int) -> String { String(format: "%+03d", Int32(n)) }

private func classify(_ msg: DecodedMessage) -> Content {
    if !msg.grid.isEmpty { return .grid(msg.grid) }
    let extra = msg.extra.trimmingCharacters(in: .whitespacesAndNewlines)
    switch extra {
    case "RR73": return .rr73
    case "RRR": return .rrr
    case "73": return .bye73
    default: break
    }
    if extra.hasPrefix("R") {
        let rest = String(extra.dropFirst())
        if let n = Int(rest) { return .rReport(n) }
    }
    if let n = Int(extra) { return .report(n) }
    return .other
}
