import SwiftUI
import FT8DSP
import FT8Engine

struct QsoSimView: View {
    @State private var myCall = "K1ABC"
    @State private var myGrid = "FN42"
    @State private var dxCall = "W9XYZ"
    @State private var dxGrid = "EM48"
    @State private var engine: QsoEngine?
    @State private var log: [String] = []
    @State private var statusText = "Idle"
    @State private var stepCount = 0

    var body: some View {
        NavigationStack {
            Form {
                Section("My Station") {
                    TextField("My Call", text: $myCall)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    TextField("My Grid", text: $myGrid)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                }

                Section("DX Station (simulated)") {
                    TextField("DX Call", text: $dxCall)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    TextField("DX Grid", text: $dxGrid)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                }

                Section("Engine Status") {
                    Text(statusText)
                        .font(.system(.body, design: .monospaced))
                }

                Section("Controls") {
                    Button("Call CQ") {
                        startCq()
                    }

                    Button("Answer CQ") {
                        answerCq()
                    }

                    Button("Step RX") {
                        stepRx()
                    }
                    .disabled(engine == nil)

                    Button("Stop", role: .destructive) {
                        stop()
                    }
                    .disabled(engine == nil)
                }

                if !log.isEmpty {
                    Section("Log") {
                        ForEach(Array(log.enumerated()), id: \.offset) { _, entry in
                            Text(entry)
                                .font(.system(.caption, design: .monospaced))
                        }
                    }
                }
            }
            .navigationTitle("QSO Sim")
        }
    }

    private func ensureEngine() -> QsoEngine {
        if let e = engine { return e }
        let e = QsoEngine(myCall: myCall, myGrid: myGrid)
        engine = e
        return e
    }

    private func startCq() {
        let e = ensureEngine()
        e.myCall = myCall
        e.myGrid = myGrid
        e.startCq()
        stepCount = 0
        appendLog(">>> Started CQ")
        refreshStatus()
    }

    private func answerCq() {
        let e = ensureEngine()
        e.myCall = myCall
        e.myGrid = myGrid

        // Simulate receiving a CQ from DX
        var cqMsg = DecodedMessage()
        cqMsg.callTo = "CQ"
        cqMsg.callFrom = dxCall
        cqMsg.extra = ""
        cqMsg.grid = dxGrid
        cqMsg.rawText = "CQ \(dxCall) \(dxGrid)"
        cqMsg.snr = -10
        cqMsg.freqHz = 1000
        cqMsg.timeSec = 0
        cqMsg.score = 100
        cqMsg.i3 = 0
        cqMsg.n3 = 0
        cqMsg.a91 = []

        e.answer(cqMsg)
        stepCount = 0
        appendLog(">>> Answered CQ from \(dxCall)")
        refreshStatus()
    }

    private func stepRx() {
        guard let e = engine else { return }
        stepCount += 1

        // Build a synthetic RX message based on current engine stage
        let status = e.status()
        let rxMessages = buildSimulatedRx(for: status.stage, step: stepCount)

        appendLog("RX step \(stepCount): feeding \(rxMessages.count) message(s)")
        for m in rxMessages {
            appendLog("  < \(m.rawText)")
        }

        if let outcome = e.processRx(rxMessages) {
            if case .completed(let record) = outcome {
                appendLog("*** QSO COMPLETE with \(record.call) ***")
            }
        }

        refreshStatus()
    }

    private func stop() {
        engine?.stop()
        refreshStatus()
        appendLog(">>> Stopped")
    }

    private func refreshStatus() {
        guard let e = engine else {
            statusText = "Idle"
            return
        }
        let s = e.status()
        var parts = ["Stage: \(s.stage.rawValue)", "Active: \(s.active)"]
        if let t = s.target { parts.append("Target: \(t)") }
        if let tx = s.txMessage { parts.append("TX: \(tx)") }
        if let rs = s.reportSent { parts.append("Sent: \(rs)") }
        if let rr = s.reportRcvd { parts.append("Rcvd: \(rr)") }
        statusText = parts.joined(separator: "\n")
    }

    private func buildSimulatedRx(for stage: TxStage, step: Int) -> [DecodedMessage] {
        // Simulate the DX station's responses based on our current TX stage
        var msg = DecodedMessage()
        msg.snr = -12
        msg.freqHz = 1000
        msg.timeSec = 0
        msg.score = 100
        msg.i3 = 0
        msg.n3 = 0
        msg.a91 = []

        switch stage {
        case .cq:
            // DX answers our CQ
            msg.callTo = myCall
            msg.callFrom = dxCall
            msg.grid = dxGrid
            msg.extra = dxGrid
            msg.rawText = "\(myCall) \(dxCall) \(dxGrid)"

        case .grid:
            // DX sends signal report
            msg.callTo = myCall
            msg.callFrom = dxCall
            msg.extra = "-12"
            msg.grid = ""
            msg.rawText = "\(myCall) \(dxCall) -12"

        case .report:
            // DX sends R+report
            msg.callTo = myCall
            msg.callFrom = dxCall
            msg.extra = "R-10"
            msg.grid = ""
            msg.rawText = "\(myCall) \(dxCall) R-10"

        case .rReport:
            // DX sends RR73
            msg.callTo = myCall
            msg.callFrom = dxCall
            msg.extra = "RR73"
            msg.grid = ""
            msg.rawText = "\(myCall) \(dxCall) RR73"

        case .rr73, .bye73:
            // DX sends 73
            msg.callTo = myCall
            msg.callFrom = dxCall
            msg.extra = "73"
            msg.grid = ""
            msg.rawText = "\(myCall) \(dxCall) 73"

        case .idle:
            return []
        }

        return [msg]
    }

    private func appendLog(_ text: String) {
        log.append(text)
        // Keep log manageable
        if log.count > 50 {
            log.removeFirst(log.count - 50)
        }
    }
}
