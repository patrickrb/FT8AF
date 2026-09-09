import XCTest
import FT8Engine

final class PotaActivationsTests: XCTestCase {

    // 2026-07-13 12:00:00 UTC
    private let noonMs: Int64 = 1_783_944_000_000

    private func rec(
        call: String, qsoDate: String, timeOn: String
    ) -> QsoRecord {
        QsoRecord(
            call: call, gridsquare: "", mode: "FT8", rstSent: "", rstRcvd: "",
            qsoDate: qsoDate, timeOn: timeOn, qsoDateOff: "", timeOff: "",
            band: "20M", freq: "14.074", stationCallsign: "", myGridsquare: "",
            comment: "")
    }

    // MARK: - Stamps

    func testStampFormatsEpochMillisAsUtc() {
        XCTAssertEqual(PotaQsoWindow.stamp(ms: noonMs), "20260713120000")
        XCTAssertEqual(PotaQsoWindow.stamp(ms: 0), "19700101000000")
    }

    func testRowStampNormalizesVariableWidthTimeOn() {
        // App-logged HHMMSS passes through.
        XCTAssertEqual(PotaQsoWindow.rowStamp(qsoDate: "20260713", timeOn: "121530"),
                       "20260713121530")
        // Imported HHMM pads with trailing zeros.
        XCTAssertEqual(PotaQsoWindow.rowStamp(qsoDate: "20260713", timeOn: "1215"),
                       "20260713121500")
        // Dropped-leading-zero odd width ("815" = 08:15) gets the zero back.
        XCTAssertEqual(PotaQsoWindow.rowStamp(qsoDate: "20260713", timeOn: "815"),
                       "20260713081500")
        // Empty time -> midnight.
        XCTAssertEqual(PotaQsoWindow.rowStamp(qsoDate: "20260713", timeOn: ""),
                       "20260713000000")
    }

    // MARK: - Window scoping

    func testQsosInWindowIncludesOnlyRecordsBetweenStartAndEnd() {
        let records = [
            rec(call: "EARLY", qsoDate: "20260713", timeOn: "115959"),
            rec(call: "IN1", qsoDate: "20260713", timeOn: "120000"),  // == start: included
            rec(call: "IN2", qsoDate: "20260713", timeOn: "123000"),
            rec(call: "LATE", qsoDate: "20260713", timeOn: "130001"),
        ]
        let endMs = noonMs + 3_600_000  // 13:00:00Z
        let inWindow = PotaQsoWindow.qsos(in: records, startedAtMs: noonMs, endedAtMs: endMs)
        XCTAssertEqual(inWindow.map(\.call), ["IN1", "IN2"])
        XCTAssertEqual(
            PotaQsoWindow.qsoCount(records: records, startedAtMs: noonMs, endedAtMs: endMs), 2)
    }

    func testOpenActivationUsesFarFutureUpperBound() {
        let records = [
            rec(call: "OLD", qsoDate: "20260101", timeOn: "080000"),
            rec(call: "NOW", qsoDate: "20260713", timeOn: "140000"),
            rec(call: "NEXTDAY", qsoDate: "20260714", timeOn: "010000"),
        ]
        let inWindow = PotaQsoWindow.qsos(in: records, startedAtMs: noonMs, endedAtMs: nil)
        XCTAssertEqual(inWindow.map(\.call), ["NOW", "NEXTDAY"])
    }

    func testQsosInWindowSortedByQsoTimeNotInputOrder() {
        let records = [
            rec(call: "SECOND", qsoDate: "20260713", timeOn: "125000"),
            rec(call: "FIRST", qsoDate: "20260713", timeOn: "121000"),
        ]
        let inWindow = PotaQsoWindow.qsos(in: records, startedAtMs: noonMs, endedAtMs: nil)
        XCTAssertEqual(inWindow.map(\.call), ["FIRST", "SECOND"])
    }

    func testRepeatActivationAtSameParkOnlyCountsItsOwnWindow() {
        // The exporter-drift bug Android's PotaQsoWindow exists to prevent:
        // two visits to the same park must not lump QSOs together.
        let records = [
            rec(call: "VISIT1", qsoDate: "20260701", timeOn: "100000"),
            rec(call: "VISIT2", qsoDate: "20260713", timeOn: "123000"),
        ]
        XCTAssertEqual(
            PotaQsoWindow.qsoCount(records: records, startedAtMs: noonMs, endedAtMs: nil), 1)
    }

    // MARK: - Activation record

    func testParkRefsSplitsCommaSeparatedMultiPark() {
        let twoFer = PotaActivationRecord(
            parkRef: "K-1234, K-5678", startedAtMs: noonMs)
        XCTAssertEqual(twoFer.parkRefs, ["K-1234", "K-5678"])
        XCTAssertEqual(twoFer.parkRefsDisplay, "K-1234 + K-5678")
        // Trailing comma / blanks are dropped.
        let messy = PotaActivationRecord(parkRef: "K-1234,,", startedAtMs: noonMs)
        XCTAssertEqual(messy.parkRefs, ["K-1234"])
    }

    func testIsActiveTracksEndedAtMs() {
        var a = PotaActivationRecord(parkRef: "K-1234", startedAtMs: noonMs)
        XCTAssertTrue(a.isActive)
        a.endedAtMs = noonMs + 1000
        XCTAssertFalse(a.isActive)
    }

    func testCodableRoundTripIncludingOpenEnd() throws {
        let a = PotaActivationRecord(
            id: "abc", parkRef: "US-7443", notes: "windy", startedAtMs: noonMs,
            endedAtMs: nil, qsoCount: 3)
        let data = try JSONEncoder().encode([a])
        let back = try JSONDecoder().decode([PotaActivationRecord].self, from: data)
        XCTAssertEqual(back, [a])
        XCTAssertTrue(back[0].isActive)
    }

    // MARK: - History

    func testHistoryForDisplayDropsActiveAndSortsNewestFirst() {
        let active = PotaActivationRecord(parkRef: "K-0001", startedAtMs: noonMs + 500)
        let older = PotaActivationRecord(
            parkRef: "K-0002", startedAtMs: noonMs - 86_400_000, endedAtMs: noonMs - 86_000_000)
        let newer = PotaActivationRecord(
            parkRef: "K-0003", startedAtMs: noonMs - 3_600_000, endedAtMs: noonMs - 3_000_000)
        let history = potaHistoryForDisplay([older, active, newer])
        XCTAssertEqual(history.map(\.parkRef), ["K-0003", "K-0002"])
    }
}
