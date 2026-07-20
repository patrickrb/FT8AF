import XCTest
import FT8Engine

final class PotaAdifTests: XCTestCase {

    // 2026-07-13 12:00:00 UTC
    private let noonMs: Int64 = 1_783_944_000_000

    private func rec(
        call: String, grid: String = "FN31", mode: String = "FT8",
        qsoDate: String = "20260713", timeOn: String = "123000",
        timeOff: String = "123115"
    ) -> QsoRecord {
        QsoRecord(
            call: call, gridsquare: grid, mode: mode, rstSent: "-05", rstRcvd: "-08",
            qsoDate: qsoDate, timeOn: timeOn, qsoDateOff: qsoDate, timeOff: timeOff,
            band: "20M", freq: "14.074", stationCallsign: "KD2OGR",
            myGridsquare: "FN20", comment: "")
    }

    private func activation(
        parkRef: String = "US-7443", endedAtMs: Int64? = nil
    ) -> PotaActivationRecord {
        PotaActivationRecord(parkRef: parkRef, startedAtMs: noonMs, endedAtMs: endedAtMs)
    }

    // MARK: - Golden document

    func testSingleParkExportMatchesAndroidFieldSetAndOrder() throws {
        let docs = Adif.potaActivationExport(
            records: [rec(call: "W1AW")], activation: activation())
        XCTAssertEqual(docs.count, 1)
        let doc = docs[0]
        XCTAssertEqual(doc.parkRef, "US-7443")
        XCTAssertEqual(doc.filename, "pota-US-7443-20260713-1200.adi")
        XCTAssertEqual(doc.content, """
        FT8AF POTA Activation US-7443
        <ADIF_VER:5>3.1.4 <PROGRAMID:5>FT8AF <EOH>
        <CALL:4>W1AW <GRIDSQUARE:4>FN31 <MODE:3>FT8 <BAND:3>20M <FREQ:6>14.074 \
        <RST_SENT:3>-05 <RST_RCVD:3>-08 <QSO_DATE:8>20260713 <TIME_ON:6>123000 \
        <TIME_OFF:6>123115 <STATION_CALLSIGN:6>KD2OGR <MY_GRIDSQUARE:4>FN20 \
        <MY_SIG:4>POTA <MY_SIG_INFO:7>US-7443 <EOR>

        """)
    }

    func testEmptyFieldsAreOmittedNotEmittedZeroLength() {
        var r = rec(call: "W1AW")
        r.gridsquare = ""
        r.timeOff = ""
        let docs = Adif.potaActivationExport(records: [r], activation: activation())
        let content = docs[0].content
        XCTAssertFalse(content.contains("<GRIDSQUARE"))
        XCTAssertFalse(content.contains("<TIME_OFF"))
        // MY_GRIDSQUARE (non-empty) still present.
        XCTAssertTrue(content.contains("<MY_GRIDSQUARE:4>FN20"))
        XCTAssertTrue(content.contains("<CALL:4>W1AW"))
    }

    func testFieldLengthIsUtf8BytesNotCharacters() {
        // "Ω" is 1 character but 2 UTF-8 bytes; a character count would misalign
        // every following field for the POTA parser.
        var r = rec(call: "W1AW")
        r.stationCallsign = "ΩΩ"
        let docs = Adif.potaActivationExport(records: [r], activation: activation())
        XCTAssertTrue(docs[0].content.contains("<STATION_CALLSIGN:4>ΩΩ "))
    }

    // MARK: - Mode handling

    func testFt4AndFt2ExportAsMfskSubmodes() {
        let docs = Adif.potaActivationExport(
            records: [rec(call: "W1AW", mode: "FT4")], activation: activation())
        XCTAssertTrue(docs[0].content.contains("<MODE:4>MFSK <SUBMODE:3>FT4 "))
        XCTAssertFalse(docs[0].content.contains("<MODE:3>FT4"))

        let ft2 = Adif.potaActivationExport(
            records: [rec(call: "W1AW", mode: "ft2")], activation: activation())
        XCTAssertTrue(ft2[0].content.contains("<MODE:4>MFSK <SUBMODE:3>FT2 "))
    }

    func testFt8AndOtherModesPassThroughUnchanged() {
        let docs = Adif.potaActivationExport(
            records: [rec(call: "W1AW", mode: "FT8")], activation: activation())
        XCTAssertTrue(docs[0].content.contains("<MODE:3>FT8 "))
        XCTAssertFalse(docs[0].content.contains("SUBMODE"))
    }

    // MARK: - Multi-park (two-fer)

    func testTwoFerProducesOneDocumentPerParkWithPinnedSigInfo() {
        let docs = Adif.potaActivationExport(
            records: [rec(call: "W1AW"), rec(call: "JA1ABC", timeOn: "124500")],
            activation: activation(parkRef: "K-1234,K-5678"))
        XCTAssertEqual(docs.map(\.parkRef), ["K-1234", "K-5678"])
        XCTAssertEqual(docs.map(\.filename),
                       ["pota-K-1234-20260713-1200.adi", "pota-K-5678-20260713-1200.adi"])
        // Same QSOs in both documents, MY_SIG_INFO pinned to each single park.
        for doc in docs {
            XCTAssertTrue(doc.content.contains("<CALL:4>W1AW"))
            XCTAssertTrue(doc.content.contains("<CALL:6>JA1ABC"))
            XCTAssertTrue(doc.content.contains("<MY_SIG_INFO:6>\(doc.parkRef) "))
            XCTAssertFalse(doc.content.contains("K-1234,K-5678"))
        }
    }

    // MARK: - Window scoping + empty result

    func testExportScopesToActivationWindow() {
        let records = [
            rec(call: "BEFORE", timeOn: "115900"),
            rec(call: "DURING", timeOn: "123000"),
            rec(call: "AFTER", timeOn: "140100"),
        ]
        let endMs = noonMs + 3_600_000  // 13:00Z
        let docs = Adif.potaActivationExport(
            records: records, activation: activation(endedAtMs: endMs))
        let content = docs[0].content
        XCTAssertTrue(content.contains("DURING"))
        XCTAssertFalse(content.contains("BEFORE"))
        XCTAssertFalse(content.contains("AFTER"))
    }

    func testNoQsosInWindowReturnsEmptyListNotHeaderOnlyFiles() {
        let docs = Adif.potaActivationExport(
            records: [rec(call: "OLD", qsoDate: "20250101")],
            activation: activation(parkRef: "K-1234,K-5678"))
        XCTAssertEqual(docs, [])
    }

    func testFilenameStampIsUtcAcrossActivationStartTimes() {
        // Epoch start renders as 1970 in UTC regardless of device timezone.
        let docs = Adif.potaActivationExport(
            records: [rec(call: "W1AW", qsoDate: "19700101", timeOn: "000000")],
            activation: PotaActivationRecord(parkRef: "K-0001", startedAtMs: 0))
        XCTAssertEqual(docs[0].filename, "pota-K-0001-19700101-0000.adi")
    }
}
