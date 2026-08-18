import XCTest
import FT8Engine

/// Unit tests for the pure park-picker helpers in `PotaParks`: distance ranking,
/// nearest-location selection, ref deduplication, filtering, and tolerant JSON
/// decoding of the pota.app read-API responses. Direct port of Android's
/// `PotaParkPickerLogicTest` (plus decode coverage for the endpoints ported from
/// `PotaClient`).
final class PotaParksTests: XCTestCase {

    private func park(
        _ ref: String, _ name: String, lat: Double = 0, lng: Double = 0
    ) -> PotaPark {
        PotaPark(reference: ref, name: name, locationDesc: "", latitude: lat, longitude: lng)
    }

    // MARK: - deduplicateRefs

    func testDeduplicateRefsSplitsCommaSeparated() {
        XCTAssertEqual(PotaParks.deduplicateRefs(["K-1234,K-5678"]), ["K-1234", "K-5678"])
    }

    func testDeduplicateRefsRemovesDuplicatesPreservingFirst() {
        XCTAssertEqual(
            PotaParks.deduplicateRefs(["K-1234", "K-5678,K-1234"]),
            ["K-1234", "K-5678"])
    }

    func testDeduplicateRefsTrimsWhitespaceAndSkipsEmpty() {
        XCTAssertEqual(
            PotaParks.deduplicateRefs([" K-0001 , , K-0002 "]),
            ["K-0001", "K-0002"])
    }

    func testDeduplicateRefsEmptyInput() {
        XCTAssertTrue(PotaParks.deduplicateRefs([]).isEmpty)
    }

    func testDeduplicateRefsPreservesMostRecentFirstOrder() {
        XCTAssertEqual(
            PotaParks.deduplicateRefs(["K-0003", "K-0002", "K-0001"]),
            ["K-0003", "K-0002", "K-0001"])
    }

    // MARK: - findNearestLocationCodes

    func testFindNearestLocationCodesPicksNClosest() {
        // Philadelphia (39.95, -75.17); PA and NJ are closest.
        let locations = [
            PotaLocation(locationDesc: "US-PA", locationName: "Pennsylvania", latitude: 40.27, longitude: -76.88),
            PotaLocation(locationDesc: "US-CA", locationName: "California", latitude: 36.78, longitude: -119.42),
            PotaLocation(locationDesc: "US-NJ", locationName: "New Jersey", latitude: 40.06, longitude: -74.41),
            PotaLocation(locationDesc: "US-TX", locationName: "Texas", latitude: 31.97, longitude: -99.90),
        ]
        let result = PotaParks.findNearestLocationCodes(
            userLat: 39.95, userLng: -75.17, locations: locations, count: 2)
        XCTAssertEqual(result, ["US-NJ", "US-PA"])
    }

    func testFindNearestLocationCodesReturnsFewerWhenInputSmall() {
        let locations = [
            PotaLocation(locationDesc: "US-PA", locationName: "Pennsylvania", latitude: 40.27, longitude: -76.88),
        ]
        let result = PotaParks.findNearestLocationCodes(
            userLat: 39.95, userLng: -75.17, locations: locations, count: 5)
        XCTAssertEqual(result, ["US-PA"])
    }

    func testBroadeningCandidateCountRescuesRealRegionFromBadCenters() {
        // POTA ships wrong centers for some foreign regions: ZA-FS, RU-VL, RO-OT
        // all report centers in Kansas, out-ranking a Kansas user's real state.
        let locations = [
            PotaLocation(locationDesc: "ZA-FS", locationName: "Free State", latitude: 38.9717, longitude: -95.2355),
            PotaLocation(locationDesc: "RU-VL", locationName: "Vladimir", latitude: 39.0904, longitude: -94.5877),
            PotaLocation(locationDesc: "RO-OT", locationName: "Olt", latitude: 39.768, longitude: -94.8509),
            PotaLocation(locationDesc: "US-KS", locationName: "Kansas", latitude: 38.5266, longitude: -96.7265),
            PotaLocation(locationDesc: "US-MO", locationName: "Missouri", latitude: 38.4561, longitude: -92.2884),
        ]
        let narrow = PotaParks.findNearestLocationCodes(
            userLat: 39.0, userLng: -94.6, locations: locations, count: 3)
        XCTAssertFalse(narrow.contains("US-KS"))

        let wide = PotaParks.findNearestLocationCodes(
            userLat: 39.0, userLng: -94.6, locations: locations,
            count: PotaParks.nearbyLocationCandidates)
        XCTAssertTrue(wide.contains("US-KS"))
        XCTAssertTrue(wide.contains("US-MO"))
    }

    // MARK: - sortParksByDistance

    func testSortParksByDistanceOrdersAscending() {
        let parks = [
            park("K-0001", "Far Park", lat: 34.05, lng: -118.24),   // LA
            park("K-0002", "Near Park", lat: 40.06, lng: -74.41),   // NJ
            park("K-0003", "Medium Park", lat: 38.90, lng: -77.03), // DC
        ]
        let result = PotaParks.sortParksByDistance(parks, userLat: 39.95, userLng: -75.17, limit: 50)
        XCTAssertEqual(result.map(\.park.reference), ["K-0002", "K-0003", "K-0001"])
    }

    func testSortParksByDistanceRespectsLimit() {
        let parks = (1...100).map { i in
            park(String(format: "K-%04d", i), "Park \(i)", lat: 40.0 + Double(i) * 0.01, lng: -75.0)
        }
        let result = PotaParks.sortParksByDistance(parks, userLat: 40.0, userLng: -75.0, limit: 50)
        XCTAssertEqual(result.count, 50)
    }

    func testSortParksByDistanceSkipsZeroCoordinates() {
        let parks = [
            park("K-0001", "No Coords", lat: 0.0, lng: 0.0),
            park("K-0002", "Real Park", lat: 40.06, lng: -74.41),
        ]
        let result = PotaParks.sortParksByDistance(parks, userLat: 39.95, userLng: -75.17, limit: 50)
        XCTAssertEqual(result.map(\.park.reference), ["K-0002"])
    }

    func testSortParksByDistanceDropsBeyondMaxDistance() {
        let parks = [
            park("US-0001", "Local Park", lat: 40.06, lng: -74.41),  // NJ near Philadelphia
            park("RU-0024", "Far Park", lat: 55.0, lng: 40.0),       // Russia ~8000 km
        ]
        let result = PotaParks.sortParksByDistance(
            parks, userLat: 39.95, userLng: -75.17, limit: 50, maxDistanceKm: 1_000.0)
        XCTAssertEqual(result.map(\.park.reference), ["US-0001"])
    }

    func testSortParksByDistanceKeepsAllWhenNoCap() {
        let parks = [
            park("US-0001", "Local Park", lat: 40.06, lng: -74.41),
            park("RU-0024", "Far Park", lat: 55.0, lng: 40.0),
        ]
        let result = PotaParks.sortParksByDistance(parks, userLat: 39.95, userLng: -75.17, limit: 50)
        XCTAssertEqual(result.map(\.park.reference), ["US-0001", "RU-0024"])
    }

    func testSortParksByDistanceIncludesDistance() {
        let parks = [park("K-0001", "Park", lat: 40.06, lng: -74.41)]
        let result = PotaParks.sortParksByDistance(parks, userLat: 39.95, userLng: -75.17, limit: 50)
        XCTAssertEqual(result.count, 1)
        XCTAssertGreaterThan(result[0].distanceKm, 0.0)
    }

    // MARK: - filterParks / filterNearbyParks

    func testFilterParksReturnsAllWhenBlank() {
        let parks = [park("K-0001", "Valley Forge"), park("K-0002", "Liberty Bell")]
        XCTAssertEqual(PotaParks.filterParks(parks, query: "").count, 2)
        XCTAssertEqual(PotaParks.filterParks(parks, query: "  ").count, 2)
    }

    func testFilterParksMatchesReferenceCaseInsensitive() {
        let parks = [park("K-0001", "Valley Forge"), park("K-0002", "Liberty Bell")]
        let result = PotaParks.filterParks(parks, query: "k-0001")
        XCTAssertEqual(result.map(\.reference), ["K-0001"])
    }

    func testFilterParksMatchesNameSubstring() {
        let parks = [
            park("K-0001", "Valley Forge National Historical Park"),
            park("K-0002", "Liberty Bell Trail"),
        ]
        let result = PotaParks.filterParks(parks, query: "forge")
        XCTAssertEqual(result.count, 1)
        XCTAssertTrue(result[0].name.contains("Forge"))
    }

    func testFilterParksEmptyWhenNoMatch() {
        XCTAssertTrue(PotaParks.filterParks([park("K-0001", "Valley Forge")], query: "xyz").isEmpty)
    }

    func testFilterNearbyParksReturnsAllWhenBlank() {
        let items = [
            PotaParkWithDistance(park: park("K-0001", "Park One"), distanceKm: 10),
            PotaParkWithDistance(park: park("K-0002", "Park Two"), distanceKm: 20),
        ]
        XCTAssertEqual(PotaParks.filterNearbyParks(items, query: "").count, 2)
    }

    func testFilterNearbyParksMatchesByReferenceOrName() {
        let items = [
            PotaParkWithDistance(park: park("K-0001", "Park Alpha"), distanceKm: 10),
            PotaParkWithDistance(park: park("K-0002", "Park Beta"), distanceKm: 20),
        ]
        XCTAssertEqual(PotaParks.filterNearbyParks(items, query: "alpha").count, 1)
        XCTAssertEqual(PotaParks.filterNearbyParks(items, query: "K-0002").count, 1)
    }

    // MARK: - JSON decoding (pota.app read API shapes from PotaClient.kt)

    func testDecodeParkParsesFields() throws {
        // GET /park/<ref> — object without a "reference" field (caller supplies it).
        let json = """
        {"parkId": 6789, "reference": "US-1234", "name": "Valley Forge National Historical Park",
         "latitude": 40.1015, "longitude": -75.4210, "grid": "FN20id",
         "locationDesc": "US-PA", "activations": 342, "qsos": 51234,
         "firstActivator": "K3XYZ", "firstActivationDate": "2015-06-13"}
        """
        let park = try XCTUnwrap(PotaParks.decodePark(Data(json.utf8), reference: "us-1234"))
        XCTAssertEqual(park.reference, "US-1234")  // from the requested ref, uppercased
        XCTAssertEqual(park.name, "Valley Forge National Historical Park")
        XCTAssertEqual(park.locationDesc, "US-PA")
        XCTAssertEqual(park.latitude, 40.1015, accuracy: 0.0001)
        XCTAssertEqual(park.longitude, -75.4210, accuracy: 0.0001)
        XCTAssertEqual(park.grid, "FN20id")
        XCTAssertEqual(park.activations, 342)
        XCTAssertEqual(park.qsos, 51234)
    }

    func testDecodeParkToleratesMissingFields() throws {
        let park = try XCTUnwrap(PotaParks.decodePark(Data("{}".utf8), reference: "US-9999"))
        XCTAssertEqual(park.reference, "US-9999")
        XCTAssertEqual(park.name, "")
        XCTAssertEqual(park.latitude, 0.0)
        XCTAssertEqual(park.activations, 0)
    }

    func testDecodeParkReturnsNilOnNonObject() {
        XCTAssertNil(PotaParks.decodePark(Data("[1,2,3]".utf8), reference: "US-1"))
        XCTAssertNil(PotaParks.decodePark(Data("not json".utf8), reference: "US-1"))
    }

    func testDecodeLocationsParsesAndSkipsMissingCoords() throws {
        // GET /locations — array; a row lacking coordinates is dropped.
        let json = """
        [
          {"locationId": 1, "locationDesc": "US-PA", "locationName": "Pennsylvania",
           "latitude": 40.9946, "longitude": -77.6055, "parks": 3100},
          {"locationId": 2, "locationDesc": "US-XX", "locationName": "No Coords", "parks": 0},
          {"locationId": 3, "locationDesc": "CA-ON", "locationName": "Ontario",
           "latitude": 50.0, "longitude": -85.0, "parks": 900}
        ]
        """
        let locations = try XCTUnwrap(PotaParks.decodeLocations(Data(json.utf8)))
        XCTAssertEqual(locations.count, 2)
        XCTAssertEqual(locations[0].locationDesc, "US-PA")
        XCTAssertEqual(locations[0].locationName, "Pennsylvania")
        XCTAssertEqual(locations[0].latitude, 40.9946, accuracy: 0.0001)
        XCTAssertEqual(locations[1].locationDesc, "CA-ON")
    }

    func testDecodeLocationsReturnsNilOnNonArray() {
        XCTAssertNil(PotaParks.decodeLocations(Data("{}".utf8)))
    }

    func testDecodeParksParsesArray() throws {
        // GET /location/parks/<code> — array of parks with their own coordinates.
        let json = """
        [
          {"reference": "US-1234", "name": "Valley Forge NHP", "latitude": 40.1015,
           "longitude": -75.4210, "grid": "FN20id", "locationDesc": "US-PA",
           "parktypeDesc": "National Historical Park", "activations": 342, "qsos": 51234},
          {"reference": "US-5678", "name": "Ridley Creek State Park", "latitude": 39.9490,
           "longitude": -75.4530, "grid": "FN20", "locationDesc": "US-PA",
           "activations": 88, "qsos": 9001}
        ]
        """
        let parks = try XCTUnwrap(PotaParks.decodeParks(Data(json.utf8)))
        XCTAssertEqual(parks.count, 2)
        XCTAssertEqual(parks[0].reference, "US-1234")
        XCTAssertEqual(parks[0].name, "Valley Forge NHP")
        XCTAssertEqual(parks[0].latitude, 40.1015, accuracy: 0.0001)
        XCTAssertEqual(parks[1].reference, "US-5678")
        XCTAssertEqual(parks[1].activations, 88)
    }

    func testDecodeParksReturnsNilOnNonArray() {
        XCTAssertNil(PotaParks.decodeParks(Data("{}".utf8)))
    }

    // MARK: - Endpoint URL construction

    func testEndpointURLs() {
        XCTAssertEqual(PotaParks.parkURLString(reference: "us-1234"), "https://api.pota.app/park/US-1234")
        XCTAssertEqual(PotaParks.locationsURLString, "https://api.pota.app/locations")
        XCTAssertEqual(
            PotaParks.locationParksURLString(locationCode: "us-pa"),
            "https://api.pota.app/location/parks/US-PA")
    }
}
