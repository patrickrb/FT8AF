import XCTest
import FT8Engine

final class OnlineLogSyncTests: XCTestCase {

    private func rec(
        id: Int64, call: String = "K1ABC",
        syncedCloudlog: Bool = false, syncedQrz: Bool = false
    ) -> QsoRecord {
        QsoRecord(id: id, call: call, gridsquare: "FN42", mode: "FT8", rstSent: "-08",
                  rstRcvd: "-12", qsoDate: "20260618", timeOn: "123000",
                  qsoDateOff: "20260618", timeOff: "123115", band: "20M", freq: "14.074",
                  stationCallsign: "KD2OGR", myGridsquare: "FN20", comment: "",
                  syncedCloudlog: syncedCloudlog, syncedQrz: syncedQrz)
    }

    // MARK: QsoRecord sync flags — persistence compatibility

    func testLegacyJsonWithoutSyncFlagsDecodesWithFalseDefaults() throws {
        // Exactly what QsoLogStore wrote before the sync flags existed.
        let legacy = """
        [{"id":1,"call":"W1AW","gridsquare":"FN31","mode":"FT8","rstSent":"-05",
          "rstRcvd":"-08","qsoDate":"20260618","timeOn":"123000",
          "qsoDateOff":"20260618","timeOff":"123115","band":"20M","freq":"14.074",
          "stationCallsign":"KD2OGR","myGridsquare":"FN20","comment":""}]
        """
        let records = try JSONDecoder().decode([QsoRecord].self, from: Data(legacy.utf8))
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records[0].call, "W1AW")
        XCTAssertFalse(records[0].syncedCloudlog)
        XCTAssertFalse(records[0].syncedQrz)
    }

    func testSyncFlagsRoundTripThroughJson() throws {
        let original = rec(id: 3, syncedCloudlog: true, syncedQrz: true)
        let data = try JSONEncoder().encode([original])
        let decoded = try JSONDecoder().decode([QsoRecord].self, from: data)
        XCTAssertEqual(decoded, [original])
        XCTAssertTrue(decoded[0].syncedCloudlog)
        XCTAssertTrue(decoded[0].syncedQrz)
    }

    func testMemberwiseInitDefaultsFlagsToFalse() {
        let r = rec(id: 1)
        XCTAssertFalse(r.syncedCloudlog)
        XCTAssertFalse(r.syncedQrz)
    }

    // MARK: unsynced selection (mirror of Android unsyncedFilter)

    func testUnsyncedEmptyWhenNoServiceEnabled() {
        let records = [rec(id: 1), rec(id: 2)]
        XCTAssertTrue(OnlineLogSync.unsynced(records, cloudlogEnabled: false,
                                             qrzEnabled: false).isEmpty)
        XCTAssertEqual(OnlineLogSync.unsyncedCount(records, cloudlogEnabled: false,
                                                   qrzEnabled: false), 0)
    }

    func testUnsyncedCloudlogOnly() {
        let records = [
            rec(id: 1, syncedCloudlog: true),
            rec(id: 2, syncedCloudlog: false, syncedQrz: true),
        ]
        let out = OnlineLogSync.unsynced(records, cloudlogEnabled: true, qrzEnabled: false)
        XCTAssertEqual(out.map(\.id), [2])
    }

    func testUnsyncedQrzOnly() {
        let records = [
            rec(id: 1, syncedQrz: true),
            rec(id: 2),
        ]
        let out = OnlineLogSync.unsynced(records, cloudlogEnabled: false, qrzEnabled: true)
        XCTAssertEqual(out.map(\.id), [2])
    }

    func testUnsyncedBothServicesIsOrOfPending() {
        let records = [
            rec(id: 1, syncedCloudlog: true, syncedQrz: true),  // fully synced — skipped
            rec(id: 2, syncedCloudlog: true, syncedQrz: false), // QRZ pending
            rec(id: 3, syncedCloudlog: false, syncedQrz: true), // Cloudlog pending
            rec(id: 4),                                         // both pending
        ]
        let out = OnlineLogSync.unsynced(records, cloudlogEnabled: true, qrzEnabled: true)
        XCTAssertEqual(out.map(\.id), [2, 3, 4])
        XCTAssertEqual(OnlineLogSync.unsyncedCount(records, cloudlogEnabled: true,
                                                   qrzEnabled: true), 3)
    }

    // MARK: single-record ADIF for online uploads

    func testSingleRecordAdifGolden() {
        let r = rec(id: 7)
        let expected =
            "<call:5>K1ABC "
            + "<gridsquare:4>FN42 "
            + "<mode:3>FT8 "
            + "<rst_sent:3>-08 "
            + "<rst_rcvd:3>-12 "
            + "<qso_date:8>20260618 "
            + "<time_on:6>123000 "
            + "<band:3>20M "
            + "<qso_date_off:8>20260618 "
            + "<time_off:6>123115 "
            + "<freq:6>14.074 "
            + "<station_callsign:6>KD2OGR "
            + "<my_gridsquare:4>FN20 "
            + "<comment:0> <eor>\n"
        XCTAssertEqual(Adif.singleRecord(r), expected)
    }

    func testSingleRecordAdifOmitsEmptyOptionalsAndHasNoHeader() {
        let r = QsoRecord(call: "W1AW", gridsquare: "", mode: "", rstSent: "",
                          rstRcvd: "", qsoDate: "", timeOn: "", qsoDateOff: "",
                          timeOff: "", band: "", freq: "", stationCallsign: "",
                          myGridsquare: "", comment: "")
        XCTAssertEqual(Adif.singleRecord(r), "<call:4>W1AW <comment:0> <eor>\n")
    }
}
