import XCTest
import FT8Engine

/// Unit coverage for `PotaSelfSpot`, the pure logic behind a POTA self-spot POST:
/// dial-frequency math, request-body construction, and response classification.
/// Mirrors Android `PotaSpotFrequencyTest` + the body shape asserted in
/// `PotaClient.selfSpot`.
final class PotaSelfSpotTests: XCTestCase {

    // MARK: - Frequency math (port of PotaSpotFrequencyTest.kt)

    func testFrequencyKhzIsDialHzOverThousand() {
        // Same cases as Android's PotaSpotFrequencyTest.
        XCTAssertEqual(PotaSelfSpot.frequencyKhz(dialHz: 50_313_000), 50_313.0, accuracy: 0.0001)
        XCTAssertEqual(PotaSelfSpot.frequencyKhz(dialHz: 14_074_000), 14_074.0, accuracy: 0.0001)
        XCTAssertEqual(PotaSelfSpot.frequencyKhz(dialHz: 7_074_000), 7_074.0, accuracy: 0.0001)
    }

    func testFrequencyKhzNeverLeaksBareAudioOffset() {
        // A ~1 kHz audio offset must never surface as the spotted frequency.
        let spot = PotaSelfSpot.frequencyKhz(dialHz: 50_313_000)
        XCTAssertGreaterThan(spot, 1_000.0)
        XCTAssertEqual(spot, 50_313.0, accuracy: 0.0001)
    }

    func testDialHzForBandMatchesEngineTable() {
        XCTAssertEqual(PotaSelfSpot.dialHz(forBand: "20M"), 14_074_000)
        XCTAssertEqual(PotaSelfSpot.dialHz(forBand: "40M"), 7_074_000)
        XCTAssertEqual(PotaSelfSpot.dialHz(forBand: "6M"), 50_313_000)
        XCTAssertEqual(PotaSelfSpot.dialHz(forBand: "17M"), 18_100_000)
        // Unknown band falls back to 20 m, matching bandToFreqMhz's default.
        XCTAssertEqual(PotaSelfSpot.dialHz(forBand: "??"), 14_074_000)
    }

    func testFrequencyKhzForBandRoundTrips() {
        XCTAssertEqual(PotaSelfSpot.frequencyKhz(forBand: "20M"), 14_074.0, accuracy: 0.0001)
        XCTAssertEqual(PotaSelfSpot.frequencyKhz(forBand: "6M"), 50_313.0, accuracy: 0.0001)
        // 30 m dial (10.136 MHz) → 10136.0 kHz.
        XCTAssertEqual(PotaSelfSpot.frequencyKhz(forBand: "30M"), 10_136.0, accuracy: 0.0001)
    }

    // MARK: - Endpoint

    func testSpotURLMatchesAndroid() {
        XCTAssertEqual(PotaSelfSpot.spotURLString, "https://api.pota.app/spot")
    }

    // MARK: - Request body (port of PotaClient.selfSpot JSONObject)

    /// Decode a Request's JSON body back into a dictionary for order-independent
    /// field assertions.
    private func decodedBody(_ request: PotaSelfSpot.Request) throws -> [String: Any] {
        let data = try request.jsonBody()
        let obj = try JSONSerialization.jsonObject(with: data)
        return try XCTUnwrap(obj as? [String: Any])
    }

    func testRequestBodyFieldsMatchAndroidShape() throws {
        let request = PotaSelfSpot.Request(
            activator: "k1abc",
            spotter: "k1abc",
            frequencyKhz: 14_074.0,
            mode: "FT8",
            reference: "us-1234",
            comments: "CQ POTA via FT8AF")
        let body = try decodedBody(request)

        // Calls + reference uppercased (Android .uppercase()).
        XCTAssertEqual(body["activator"] as? String, "K1ABC")
        XCTAssertEqual(body["spotter"] as? String, "K1ABC")
        XCTAssertEqual(body["reference"] as? String, "US-1234")
        // frequency is a "%.1f" STRING, not a number (Android String.format).
        XCTAssertEqual(body["frequency"] as? String, "14074.0")
        XCTAssertEqual(body["mode"] as? String, "FT8")
        XCTAssertEqual(body["source"] as? String, "FT8AF")
        XCTAssertEqual(body["comments"] as? String, "CQ POTA via FT8AF")
    }

    func testRequestBodyFrequencyIsOneDecimalString() throws {
        // 6 m: 50313.0; a fractional dial still renders to one decimal.
        let sixM = try decodedBody(PotaSelfSpot.Request(
            activator: "W1AW", spotter: "W1AW", frequencyKhz: 50_313.0,
            mode: "FT8", reference: "US-0001"))
        XCTAssertEqual(sixM["frequency"] as? String, "50313.0")

        let rounded = try decodedBody(PotaSelfSpot.Request(
            activator: "W1AW", spotter: "W1AW", frequencyKhz: 7_074.05,
            mode: "FT8", reference: "US-0001"))
        XCTAssertEqual(rounded["frequency"] as? String, "7074.1")
    }

    func testRequestBodyFrequencyUsesDotDecimalRegardlessOfLocale() throws {
        // The frequency string is formatted with a POSIX locale, so it must
        // always use "." — a comma-decimal locale must never POST "14074,0".
        let body = try decodedBody(PotaSelfSpot.Request(
            activator: "W1AW", spotter: "W1AW", frequencyKhz: 14_074.0,
            mode: "FT8", reference: "US-0001"))
        let freq = body["frequency"] as? String
        XCTAssertEqual(freq, "14074.0")
        XCTAssertFalse(freq?.contains(",") ?? true)
    }

    func testRequestDefaultCommentMatchesAndroid() throws {
        let body = try decodedBody(PotaSelfSpot.Request(
            activator: "W1AW", spotter: "W1AW", frequencyKhz: 14_074.0,
            mode: "FT8", reference: "US-0001"))
        XCTAssertEqual(body["comments"] as? String, "CQ POTA via FT8AF")
    }

    // MARK: - Response classification

    func testParseResponseSuccessOn2xx() {
        XCTAssertEqual(PotaSelfSpot.parseResponse(statusCode: 200, body: nil), .success)
        XCTAssertEqual(
            PotaSelfSpot.parseResponse(
                statusCode: 201,
                body: Data(#"{"spotId":123}"#.utf8)),
            .success)
    }

    func testParseResponseFailureOnNon2xxCarriesStatus() {
        let result = PotaSelfSpot.parseResponse(
            statusCode: 500, body: Data("bad park ref".utf8))
        guard case let .failure(msg) = result else {
            return XCTFail("expected failure")
        }
        XCTAssertTrue(msg.contains("500"))
        XCTAssertTrue(msg.contains("bad park ref"))
        XCTAssertFalse(result.isSuccess)
    }

    func testParseResponseFailureWithoutBody() {
        let result = PotaSelfSpot.parseResponse(statusCode: 403, body: nil)
        guard case let .failure(msg) = result else {
            return XCTFail("expected failure")
        }
        XCTAssertTrue(msg.contains("403"))
    }
}
