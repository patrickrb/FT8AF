import XCTest
@testable import FT8Engine

final class TxStageSelectorTests: XCTestCase {

    // MARK: chips

    func testChipOrderAndLabels() {
        XCTAssertEqual(TxStageSelector.chips, [.cq, .grid, .report, .rReport, .rr73, .bye73])
        XCTAssertEqual(TxStageSelector.chips.map(TxStageSelector.label(for:)),
                       ["CQ", "GRID", "RPT", "R-RPT", "RR73", "73"])
        XCTAssertEqual(TxStageSelector.label(for: .idle), "")
    }

    func testIndexOfStage() {
        XCTAssertEqual(TxStageSelector.index(of: .cq), 0)
        XCTAssertEqual(TxStageSelector.index(of: .bye73), 5)
        XCTAssertNil(TxStageSelector.index(of: .idle))
    }

    func testActiveChip() {
        XCTAssertTrue(TxStageSelector.isActive(.report, current: .report))
        XCTAssertFalse(TxStageSelector.isActive(.report, current: .rReport))
    }

    func testCompletedChips() {
        // At R-RPT: GRID and RPT are done; CQ never shows completed.
        XCTAssertTrue(TxStageSelector.isCompleted(.grid, current: .rReport))
        XCTAssertTrue(TxStageSelector.isCompleted(.report, current: .rReport))
        XCTAssertFalse(TxStageSelector.isCompleted(.cq, current: .rReport))
        XCTAssertFalse(TxStageSelector.isCompleted(.rReport, current: .rReport))
        XCTAssertFalse(TxStageSelector.isCompleted(.rr73, current: .rReport))
        // Idle marks nothing completed.
        XCTAssertFalse(TxStageSelector.isCompleted(.grid, current: .idle))
        // At the CQ baseline nothing is completed.
        XCTAssertFalse(TxStageSelector.isCompleted(.grid, current: .cq))
    }

    // MARK: header state

    func testHeaderTargetStates() {
        XCTAssertEqual(
            QsoPanelHeader.state(targetCall: "W1AW", isActivated: true,
                                 huntEnabled: false, huntCallsCQ: false, isTransmitting: true),
            .qsoing("W1AW"))
        XCTAssertEqual(
            QsoPanelHeader.state(targetCall: "W1AW", isActivated: true,
                                 huntEnabled: false, huntCallsCQ: false, isTransmitting: false),
            .waiting("W1AW"))
    }

    func testHeaderCqAndHuntStates() {
        // Plain CQ run.
        XCTAssertEqual(
            QsoPanelHeader.state(targetCall: "", isActivated: true,
                                 huntEnabled: false, huntCallsCQ: false, isTransmitting: true),
            .callingCq)
        // Silent listening hunt (armed, engine idle).
        XCTAssertEqual(
            QsoPanelHeader.state(targetCall: nil, isActivated: false,
                                 huntEnabled: true, huntCallsCQ: false, isTransmitting: false),
            .hunting)
        // Activated hunt without hunt-calls-CQ is still a listening state.
        XCTAssertEqual(
            QsoPanelHeader.state(targetCall: "", isActivated: true,
                                 huntEnabled: true, huntCallsCQ: false, isTransmitting: false),
            .hunting)
        // Hunt+CQ hybrid transmits CQ, so "Calling CQ" is correct (Android rule).
        XCTAssertEqual(
            QsoPanelHeader.state(targetCall: "", isActivated: true,
                                 huntEnabled: true, huntCallsCQ: true, isTransmitting: false),
            .callingCq)
        // Nothing armed.
        XCTAssertEqual(
            QsoPanelHeader.state(targetCall: nil, isActivated: false,
                                 huntEnabled: false, huntCallsCQ: false, isTransmitting: false),
            .idle)
    }

    func testHeaderTargetWinsOverHunt() {
        XCTAssertEqual(
            QsoPanelHeader.state(targetCall: "W1AW", isActivated: true,
                                 huntEnabled: true, huntCallsCQ: false, isTransmitting: false),
            .waiting("W1AW"))
    }
}
