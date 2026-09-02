import XCTest
import FT8Engine

final class CloudlogClientTests: XCTestCase {

    // MARK: normalizedAddress

    func testNormalizedAddressAppendsSlashAndTrims() {
        XCTAssertEqual(CloudlogClient.normalizedAddress("https://log.example.com"),
                       "https://log.example.com/")
        XCTAssertEqual(CloudlogClient.normalizedAddress("https://log.example.com/"),
                       "https://log.example.com/")
        XCTAssertEqual(CloudlogClient.normalizedAddress("  https://log.example.com \n"),
                       "https://log.example.com/")
        XCTAssertEqual(CloudlogClient.normalizedAddress("   "), "")
    }

    // MARK: qsoUploadRequest

    func testQsoUploadRequestGoldenBody() {
        let req = CloudlogClient.qsoUploadRequest(
            serverAddress: "https://log.example.com",
            apiKey: "cl123",
            stationId: "5",
            adif: "<call:5>K1ABC <eor>\n"
        )
        XCTAssertEqual(req?.url, "https://log.example.com/api/qso")
        XCTAssertEqual(
            req?.body,
            "{\"key\":\"cl123\",\"station_profile_id\":\"5\",\"type\":\"adif\","
                + "\"string\":\"<call:5>K1ABC <eor>\\n\"}"
        )
    }

    func testQsoUploadRequestEndpointHasNoTrailingSlash() {
        // Wavelog/Nextlog 308-redirect "api/qso/" on POST; the endpoint must be
        // the documented slash-less form.
        let req = CloudlogClient.qsoUploadRequest(
            serverAddress: "https://log.example.com/", apiKey: "k", stationId: "1", adif: "x")
        XCTAssertEqual(req?.url, "https://log.example.com/api/qso")
    }

    func testQsoUploadRequestNilWhenAddressOrKeyMissing() {
        XCTAssertNil(CloudlogClient.qsoUploadRequest(
            serverAddress: "", apiKey: "k", stationId: "1", adif: "x"))
        XCTAssertNil(CloudlogClient.qsoUploadRequest(
            serverAddress: "https://log.example.com", apiKey: "", stationId: "1", adif: "x"))
    }

    func testQsoUploadRequestBodyIsValidJson() throws {
        let req = try XCTUnwrap(CloudlogClient.qsoUploadRequest(
            serverAddress: "https://log.example.com",
            apiKey: "k\"ey\\", stationId: "2",
            adif: "<comment:9>tab\there\n <eor>\n"))
        let obj = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(req.body.utf8)) as? [String: String])
        XCTAssertEqual(obj["key"], "k\"ey\\")
        XCTAssertEqual(obj["station_profile_id"], "2")
        XCTAssertEqual(obj["type"], "adif")
        XCTAssertEqual(obj["string"], "<comment:9>tab\there\n <eor>\n")
    }

    // MARK: authUrl

    func testAuthUrl() {
        XCTAssertEqual(
            CloudlogClient.authUrl(serverAddress: "https://log.example.com", apiKey: "cl123"),
            "https://log.example.com/api/auth/cl123")
        XCTAssertNil(CloudlogClient.authUrl(serverAddress: "", apiKey: "cl123"))
        XCTAssertNil(CloudlogClient.authUrl(serverAddress: "https://log.example.com", apiKey: ""))
    }

    // MARK: isAuthValid

    func testIsAuthValidCloudlogShape() {
        XCTAssertTrue(CloudlogClient.isAuthValid(
            "<auth><status>Valid</status><rights>rw</rights></auth>"))
    }

    func testIsAuthValidToleratesWhitespaceAndXmlDeclaration() {
        // Wavelog/Nextlog return the same markers with extra noise.
        let noisy = """
        <?xml version="1.0" encoding="UTF-8"?>
        <auth>
          <status> Valid </status>
          <rights>
            rw
          </rights>
        </auth>
        """
        XCTAssertTrue(CloudlogClient.isAuthValid(noisy))
    }

    func testIsAuthValidRejectsReadOnlyInvalidAndMissing() {
        XCTAssertFalse(CloudlogClient.isAuthValid(
            "<auth><status>Valid</status><rights>r</rights></auth>"))
        XCTAssertFalse(CloudlogClient.isAuthValid(
            "<auth><status>Invalid</status><rights>rw</rights></auth>"))
        XCTAssertFalse(CloudlogClient.isAuthValid(""))
        XCTAssertFalse(CloudlogClient.isAuthValid(nil))
    }

    // MARK: isSuccessStatus

    func testIsSuccessStatus() {
        XCTAssertTrue(CloudlogClient.isSuccessStatus(200))
        XCTAssertTrue(CloudlogClient.isSuccessStatus(201)) // Cloudlog answers HTTP_CREATED
        XCTAssertFalse(CloudlogClient.isSuccessStatus(308))
        XCTAssertFalse(CloudlogClient.isSuccessStatus(401))
        XCTAssertFalse(CloudlogClient.isSuccessStatus(500))
    }
}
