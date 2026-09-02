import XCTest
import FT8Engine

final class PotaSpotsTests: XCTestCase {

    /// Canned excerpt of a real `api.pota.app/spot/activator` response
    /// (2026-07-13), covering the shapes the decoder must survive: frequency as
    /// "14061.0" and as "14074" (no decimal), null parkName, a lowercase-ish
    /// activator, an RBN spotter with suffix, and a non-FT8 mode.
    private let fixtureJson = """
    [
      {"spotId": 53435147, "activator": "KU5J", "frequency": "14061.0", "mode": "CW",
       "reference": "US-7443", "parkName": null,
       "spotTime": "2026-07-13T19:44:50", "spotter": "KG1A", "comments": "TnxTom",
       "source": "Web", "invalid": null,
       "name": "DeGray Lower Lake State Recreation Area", "locationDesc": "US-AR",
       "grid4": "EM34", "grid6": "EM34ke", "latitude": 34.1783, "longitude": -93.0978,
       "count": 7, "expire": 1},
      {"spotId": 53436877, "activator": "vb7f", "frequency": "18100.0", "mode": "FT8",
       "reference": "CA-5540", "parkName": null,
       "spotTime": "2026-07-13T20:14:32", "spotter": "G4IRN-#",
       "comments": "RBN -18 dB via G4IRN-#", "source": "RBN", "invalid": null,
       "name": "Beacon Hill Park", "locationDesc": "CA-BC",
       "grid4": "CN88", "grid6": "CN88hj", "latitude": 48.4124, "longitude": -123.365,
       "count": 51, "expire": 1783},
      {"spotId": 53435371, "activator": "VE6BR", "frequency": "14074", "mode": "ft8",
       "reference": "CA-0632", "parkName": null,
       "spotTime": "2026-07-13T19:48:16", "spotter": "VE6BR", "comments": null,
       "source": "Web", "invalid": null,
       "name": "Beauvais Lake Provincial Park", "locationDesc": "CA-AB",
       "grid4": "DN29", "grid6": "DN29wj", "latitude": 49.4109, "longitude": -114.119,
       "count": 15, "expire": 207}
    ]
    """

    private var fixtureData: Data { Data(fixtureJson.utf8) }

    // MARK: - Decoding

    func testDecodeParsesAllRowsWithFeedFieldMapping() throws {
        let spots = try XCTUnwrap(PotaSpots.decode(fixtureData))
        XCTAssertEqual(spots.count, 3)

        let cw = spots[0]
        XCTAssertEqual(cw.activator, "KU5J")
        XCTAssertEqual(cw.frequencyKhz, 14061.0, accuracy: 0.001)
        XCTAssertEqual(cw.mode, "CW")
        XCTAssertEqual(cw.reference, "US-7443")
        // parkName comes from the feed's "name" key ("parkName" is null).
        XCTAssertEqual(cw.parkName, "DeGray Lower Lake State Recreation Area")
        XCTAssertEqual(cw.locationDesc, "US-AR")
        XCTAssertEqual(cw.spotter, "KG1A")
        XCTAssertEqual(cw.spotTimeUtc, "2026-07-13T19:44:50")
        XCTAssertEqual(cw.comments, "TnxTom")
    }

    func testDecodeUppercasesActivatorAndParsesIntegerStringFrequency() throws {
        let spots = try XCTUnwrap(PotaSpots.decode(fixtureData))
        XCTAssertEqual(spots[1].activator, "VB7F")
        // "14074" (no decimal point) still parses.
        XCTAssertEqual(spots[2].frequencyKhz, 14074.0, accuracy: 0.001)
        // null comments -> "".
        XCTAssertEqual(spots[2].comments, "")
    }

    func testDecodeReturnsNilForNonArrayPayload() {
        XCTAssertNil(PotaSpots.decode(Data("{\"oops\": true}".utf8)))
        XCTAssertNil(PotaSpots.decode(Data("not json".utf8)))
    }

    func testDecodeSkipsMalformedElementsInsteadOfFailingBatch() throws {
        let json = """
        [ 42, {"activator": "W1AW", "frequency": "7074", "mode": "FT8",
               "reference": "US-0001"} ]
        """
        let spots = try XCTUnwrap(PotaSpots.decode(Data(json.utf8)))
        XCTAssertEqual(spots.count, 1)
        XCTAssertEqual(spots[0].activator, "W1AW")
        // Missing fields become "" (Android optString behavior).
        XCTAssertEqual(spots[0].parkName, "")
        XCTAssertEqual(spots[0].spotTimeUtc, "")
    }

    func testDecodeUnparseableFrequencyBecomesZero() throws {
        let json = """
        [{"activator": "W1AW", "frequency": "QRP", "mode": "FT8", "reference": "US-0001"}]
        """
        let spots = try XCTUnwrap(PotaSpots.decode(Data(json.utf8)))
        XCTAssertEqual(spots[0].frequencyKhz, 0)
    }

    // MARK: - Display filter + sort

    func testForDisplayFT8OnlyFiltersCaseInsensitivelyAndSortsByFrequency() throws {
        let spots = try XCTUnwrap(PotaSpots.decode(fixtureData))
        let shown = PotaSpots.forDisplay(spots, ft8Only: true)
        // CW row dropped; "ft8" (lowercase mode) kept; ascending frequency.
        XCTAssertEqual(shown.map(\.activator), ["VE6BR", "VB7F"])
        XCTAssertEqual(shown.map(\.frequencyKhz), [14074.0, 18100.0])
    }

    func testForDisplayAllModesKeepsEverythingSorted() throws {
        let spots = try XCTUnwrap(PotaSpots.decode(fixtureData))
        let shown = PotaSpots.forDisplay(spots, ft8Only: false)
        XCTAssertEqual(shown.map(\.activator), ["KU5J", "VE6BR", "VB7F"])
    }

    // MARK: - Formatting

    func testFrequencyTextMatchesAndroidFormat() {
        let spot = PotaSpot(
            activator: "W1AW", frequencyKhz: 14074, mode: "FT8", reference: "US-0001",
            parkName: "", locationDesc: "", spotter: "", spotTimeUtc: "", comments: "")
        XCTAssertEqual(spot.frequencyText, "14074.0 kHz")
        XCTAssertEqual(spot.frequencyHz, 14_074_000)
    }

    func testAgeSecondsParsesUtcTimestampWithAndWithoutFraction() {
        let now = date(iso: "2026-07-13T20:15:00")
        XCTAssertEqual(PotaSpots.ageSeconds(spotTimeUtc: "2026-07-13T20:14:32", now: now), 28)
        // Fractional seconds (as POTA sometimes emits) are ignored.
        XCTAssertEqual(
            PotaSpots.ageSeconds(spotTimeUtc: "2026-07-13T20:14:32.2233333", now: now), 28)
        // Future timestamp (clock skew) clamps to 0.
        XCTAssertEqual(PotaSpots.ageSeconds(spotTimeUtc: "2026-07-13T20:16:00", now: now), 0)
    }

    func testAgeSecondsReturnsNilForGarbage() {
        let now = Date()
        XCTAssertNil(PotaSpots.ageSeconds(spotTimeUtc: "", now: now))
        XCTAssertNil(PotaSpots.ageSeconds(spotTimeUtc: "yesterday", now: now))
        XCTAssertNil(PotaSpots.ageSeconds(spotTimeUtc: "2026-07-13", now: now))
    }

    // MARK: - Band mapping

    func testBandForFrequencyMapsFT8DialsAndInBandSpots() {
        XCTAssertEqual(PotaSpots.band(forFrequencyKhz: 14074), "20M")
        XCTAssertEqual(PotaSpots.band(forFrequencyKhz: 7074), "40M")
        XCTAssertEqual(PotaSpots.band(forFrequencyKhz: 18100), "17M")
        XCTAssertEqual(PotaSpots.band(forFrequencyKhz: 50313), "6M")
        // Anywhere in-band maps, not just the FT8 dial.
        XCTAssertEqual(PotaSpots.band(forFrequencyKhz: 14061.0), "20M")
        XCTAssertEqual(PotaSpots.band(forFrequencyKhz: 28320), "10M")
    }

    func testBandForFrequencyReturnsNilOutsideAllocations() {
        XCTAssertNil(PotaSpots.band(forFrequencyKhz: 0))
        XCTAssertNil(PotaSpots.band(forFrequencyKhz: 6000))     // between 60m and 40m
        XCTAssertNil(PotaSpots.band(forFrequencyKhz: 144_174))  // 2m: not in the picker
    }

    // MARK: - Activation-time refresh cadence

    private let interval: Int64 = 60_000

    func testShouldRefreshTrueWhenActivatingAndNeverRefreshed() {
        XCTAssertTrue(PotaSpots.shouldRefreshPotaSpots(
            isActivating: true, lastRefreshMs: nil, nowMs: 1_000_000, intervalMs: interval))
    }

    func testShouldRefreshTrueWhenActivatingAndStale() {
        // Last refresh well beyond the interval.
        XCTAssertTrue(PotaSpots.shouldRefreshPotaSpots(
            isActivating: true, lastRefreshMs: 0, nowMs: 90_000, intervalMs: interval))
    }

    func testShouldRefreshFalseWhenNotActivating() {
        // Even with no prior refresh, an idle app must not poll the feed.
        XCTAssertFalse(PotaSpots.shouldRefreshPotaSpots(
            isActivating: false, lastRefreshMs: nil, nowMs: 1_000_000, intervalMs: interval))
        XCTAssertFalse(PotaSpots.shouldRefreshPotaSpots(
            isActivating: false, lastRefreshMs: 0, nowMs: 90_000, intervalMs: interval))
    }

    func testShouldRefreshFalseWhenActivatingButWithinInterval() {
        // Refreshed 59 s ago — the Hunt tab (or a prior activation tick) just
        // fetched, so don't double-fetch.
        XCTAssertFalse(PotaSpots.shouldRefreshPotaSpots(
            isActivating: true, lastRefreshMs: 1_000, nowMs: 60_000, intervalMs: interval))
    }

    func testShouldRefreshTrueAtExactlyIntervalBoundary() {
        // Elapsed == intervalMs counts as due (>=), one ms less does not.
        XCTAssertTrue(PotaSpots.shouldRefreshPotaSpots(
            isActivating: true, lastRefreshMs: 0, nowMs: interval, intervalMs: interval))
        XCTAssertFalse(PotaSpots.shouldRefreshPotaSpots(
            isActivating: true, lastRefreshMs: 0, nowMs: interval - 1, intervalMs: interval))
    }

    // MARK: - Helpers

    private func date(iso: String) -> Date {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.timeZone = TimeZone(identifier: "UTC")
        fmt.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return fmt.date(from: iso)!
    }
}
