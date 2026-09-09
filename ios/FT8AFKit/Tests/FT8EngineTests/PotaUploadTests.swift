import XCTest
import FT8Engine

final class PotaUploadTests: XCTestCase {

    // MARK: - uploadRequest (multipart)

    func testUploadRequestMultipartShape() {
        let req = PotaUpload.uploadRequest(
            idToken: "JWT.ID.TOKEN",
            filename: "pota-US-1234-20260817-1200.adi",
            adif: "FT8AF POTA\n<EOH>\n<CALL:5>K1ABC <EOR>\n",
            boundary: "----ft8afTESTBOUND")

        XCTAssertEqual(req.url, "https://api.pota.app/adif")
        XCTAssertEqual(req.method, "POST")
        // RAW JWT — never "Bearer <jwt>".
        XCTAssertEqual(req.headers["Authorization"], "JWT.ID.TOKEN")
        XCTAssertFalse(req.headers["Authorization"]?.hasPrefix("Bearer") ?? true)
        XCTAssertEqual(req.headers["User-Agent"], "ft8af-1.0")
        XCTAssertEqual(req.headers["Accept"], "application/json")
        XCTAssertEqual(req.headers["Content-Type"],
                       "multipart/form-data; boundary=----ft8afTESTBOUND")

        let body = String(data: req.body, encoding: .utf8) ?? ""
        let expected = "------ft8afTESTBOUND\r\n"
            + "Content-Disposition: form-data; name=\"adif\"; "
            + "filename=\"pota-US-1234-20260817-1200.adi\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n"
            + "FT8AF POTA\n<EOH>\n<CALL:5>K1ABC <EOR>\n"
            + "\r\n------ft8afTESTBOUND--\r\n"
        XCTAssertEqual(body, expected)
        // Exactly one part named "adif".
        let occurrences = body.components(separatedBy: "name=\"adif\"").count - 1
        XCTAssertEqual(occurrences, 1)
    }

    func testNewBoundaryIsUniqueAndPrefixed() {
        let a = PotaUpload.newBoundary()
        let b = PotaUpload.newBoundary()
        XCTAssertTrue(a.hasPrefix("----ft8af"))
        XCTAssertNotEqual(a, b)
    }

    // MARK: - jobsRequest

    func testJobsRequestShape() {
        let req = PotaUpload.jobsRequest(idToken: "JWT.ID")
        XCTAssertEqual(req.url, "https://api.pota.app/user/jobs")
        XCTAssertEqual(req.method, "GET")
        XCTAssertEqual(req.headers["Authorization"], "JWT.ID")
        XCTAssertEqual(req.headers["User-Agent"], "ft8af-1.0")
        XCTAssertEqual(req.headers["Accept"], "application/json")
        XCTAssertTrue(req.body.isEmpty)
    }

    // MARK: - retry classification + backoff

    func testIsRetryable() {
        for s in [502, 503, 504] { XCTAssertTrue(PotaUpload.isRetryable(status: s)) }
        for s in [200, 400, 401, 403, 404, 500, 501] {
            XCTAssertFalse(PotaUpload.isRetryable(status: s))
        }
    }

    func testIsUnauthorized() {
        XCTAssertTrue(PotaUpload.isUnauthorized(status: 401))
        XCTAssertTrue(PotaUpload.isUnauthorized(status: 403))
        XCTAssertFalse(PotaUpload.isUnauthorized(status: 500))
        XCTAssertFalse(PotaUpload.isUnauthorized(status: 200))
    }

    func testIsSuccess() {
        XCTAssertTrue(PotaUpload.isSuccess(status: 200))
        XCTAssertTrue(PotaUpload.isSuccess(status: 201))
        XCTAssertFalse(PotaUpload.isSuccess(status: 302))
        XCTAssertFalse(PotaUpload.isSuccess(status: 502))
    }

    func testBackoffSequence() {
        XCTAssertEqual(PotaUpload.backoffSeconds(attempt: 1), 1)
        XCTAssertEqual(PotaUpload.backoffSeconds(attempt: 2), 2)
        XCTAssertEqual(PotaUpload.backoffSeconds(attempt: 3), 4)
    }

    func testMaxAttempts() {
        XCTAssertEqual(PotaUpload.maxAttempts, 3)
    }

    // MARK: - failure classification

    func testClassifyFailure() {
        XCTAssertEqual(PotaUpload.classifyFailure(status: 502), .busy)
        XCTAssertEqual(PotaUpload.classifyFailure(status: 503), .busy)
        XCTAssertEqual(PotaUpload.classifyFailure(status: 500), .serverError)
        XCTAssertEqual(PotaUpload.classifyFailure(status: 599), .serverError)
        XCTAssertEqual(PotaUpload.classifyFailure(status: nil), .network)
        XCTAssertEqual(PotaUpload.classifyFailure(status: 404), .other)
    }
}
