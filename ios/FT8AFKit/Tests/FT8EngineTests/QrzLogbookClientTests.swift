import XCTest
import FT8Engine

final class QrzLogbookClientTests: XCTestCase {

    func testEndpoint() {
        XCTAssertEqual(QrzLogbookClient.endpoint, "https://logbook.qrz.com/api")
    }

    // MARK: request bodies

    func testInsertBodyGolden() {
        let body = QrzLogbookClient.insertBody(
            apiKey: "ABCD-1234",
            adif: "<call:5>K1ABC <eor>\n"
        )
        XCTAssertEqual(
            body,
            "KEY=ABCD-1234&ACTION=INSERT&ADIF=%3Ccall%3A5%3EK1ABC+%3Ceor%3E%0A")
    }

    func testStatusBodyGolden() {
        XCTAssertEqual(QrzLogbookClient.statusBody(apiKey: "AB CD"),
                       "KEY=AB+CD&ACTION=STATUS")
    }

    // MARK: form encoding (Java URLEncoder-compatible)

    func testFormEncodePassesUnreservedCharacters() {
        XCTAssertEqual(QrzLogbookClient.formEncode("AZaz09.-*_"), "AZaz09.-*_")
    }

    func testFormEncodeSpaceIsPlus() {
        XCTAssertEqual(QrzLogbookClient.formEncode("a b"), "a+b")
    }

    func testFormEncodeEscapesReservedAndUtf8Bytes() {
        XCTAssertEqual(QrzLogbookClient.formEncode("<>:&=+\n"),
                       "%3C%3E%3A%26%3D%2B%0A")
        XCTAssertEqual(QrzLogbookClient.formEncode("é"), "%C3%A9")
    }

    // MARK: response parsing

    func testResultParsesFirstResultField() {
        XCTAssertEqual(QrzLogbookClient.result(of: "RESULT=OK&COUNT=1&LOGID=999"), "OK")
        XCTAssertEqual(QrzLogbookClient.result(of: "STATUS=FAIL&RESULT=FAIL&REASON=bad key"),
                       "FAIL")
        XCTAssertEqual(QrzLogbookClient.result(of: "RESULT=REPLACE&COUNT=1"), "REPLACE")
    }

    func testResultNilForMissingEmptyOrMalformed() {
        XCTAssertNil(QrzLogbookClient.result(of: nil))
        XCTAssertNil(QrzLogbookClient.result(of: ""))
        XCTAssertNil(QrzLogbookClient.result(of: "COUNT=1&LOGID=2"))
        XCTAssertNil(QrzLogbookClient.result(of: "RESULT")) // no '='
    }

    func testResultKeepsEqualsInsideValue() {
        XCTAssertEqual(QrzLogbookClient.result(of: "RESULT=A=B"), "A=B")
    }

    // MARK: success predicates

    func testIsInsertSuccessAcceptsOkAndReplace() {
        XCTAssertTrue(QrzLogbookClient.isInsertSuccess("RESULT=OK&COUNT=1"))
        // REPLACE = the QSO already existed and was updated — still a success.
        XCTAssertTrue(QrzLogbookClient.isInsertSuccess("RESULT=REPLACE&COUNT=1"))
        XCTAssertFalse(QrzLogbookClient.isInsertSuccess("RESULT=FAIL&REASON=x"))
        XCTAssertFalse(QrzLogbookClient.isInsertSuccess(nil))
    }

    func testIsStatusOkAcceptsOnlyOk() {
        XCTAssertTrue(QrzLogbookClient.isStatusOk("RESULT=OK&DATA=..."))
        XCTAssertFalse(QrzLogbookClient.isStatusOk("RESULT=REPLACE"))
        XCTAssertFalse(QrzLogbookClient.isStatusOk("RESULT=FAIL"))
        XCTAssertFalse(QrzLogbookClient.isStatusOk(nil))
    }
}
