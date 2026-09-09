import Foundation

/// Pure display/ordering rules for the TX message-selector chips in the
/// Active QSO panel (Android `TxSelector`: CQ / GRID / RPT / R-RPT / RR73 / 73).
/// The chip matching the engine's current stage renders active (amber); chips
/// for stages already passed render completed (cyan).
public enum TxStageSelector {
    /// Chip order as displayed. Note the canonical QSO progression is
    /// GRID → RPT → R-RPT → RR73 → 73, with CQ as the idle/baseline chip.
    public static let chips: [TxStage] = [.cq, .grid, .report, .rReport, .rr73, .bye73]

    /// Short label for a chip, matching Android's `TxSelector` labels.
    public static func label(for stage: TxStage) -> String {
        switch stage {
        case .cq: return "CQ"
        case .grid: return "GRID"
        case .report: return "RPT"
        case .rReport: return "R-RPT"
        case .rr73: return "RR73"
        case .bye73: return "73"
        case .idle: return ""
        }
    }

    /// Position of a stage in the QSO progression (CQ = 0 … 73 = 5); nil for idle.
    public static func index(of stage: TxStage) -> Int? {
        chips.firstIndex(of: stage)
    }

    /// Whether `chip` is the currently active (queued) message.
    public static func isActive(_ chip: TxStage, current: TxStage) -> Bool {
        chip == current
    }

    /// Whether `chip` represents a stage the QSO has already progressed past.
    /// Idle marks nothing completed; CQ never shows as completed (it is the
    /// baseline, not a QSO step).
    public static func isCompleted(_ chip: TxStage, current: TxStage) -> Bool {
        guard current != .idle, chip != .cq,
              let chipIdx = index(of: chip),
              let curIdx = index(of: current) else { return false }
        return chipIdx < curIdx
    }
}

/// Pure header-state rule for the Active QSO panel (Android `StationHeader`
/// wording: Hunting / Calling CQ / "QSOing with X" / "Waiting for X").
public enum QsoPanelHeader {
    public enum State: Equatable {
        case hunting
        case callingCq
        case qsoing(String)
        case waiting(String)
        case idle
    }

    /// - Parameters:
    ///   - targetCall: locked QSO partner, empty/nil when none
    ///   - isActivated: the sequencer is armed (CQ run or QSO)
    ///   - huntEnabled: HUNT (auto-answer CQs) is armed
    ///   - huntCallsCQ: the hunt-calls-CQ hybrid setting — a CQing hunt shows
    ///     "Calling CQ" (it *is* transmitting); only a silent listening hunt
    ///     shows "Hunting" (mirrors Android's `isHunting` rule).
    ///   - isTransmitting: audio is keyed this cycle
    public static func state(
        targetCall: String?,
        isActivated: Bool,
        huntEnabled: Bool,
        huntCallsCQ: Bool,
        isTransmitting: Bool
    ) -> State {
        if let dx = targetCall, !dx.isEmpty {
            return isTransmitting ? .qsoing(dx) : .waiting(dx)
        }
        if isActivated {
            return (huntEnabled && !huntCallsCQ) ? .hunting : .callingCq
        }
        if huntEnabled {
            return .hunting
        }
        return .idle
    }
}
