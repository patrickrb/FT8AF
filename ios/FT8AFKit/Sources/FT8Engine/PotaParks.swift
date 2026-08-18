import Foundation

/// Park metadata from pota.app's read API. Mirrors Android
/// `radio.ks3ckc.ft8af.pota.model.PotaPark`: filled from the park-lookup
/// endpoint (`/park/<ref>`) or the location-parks endpoint
/// (`/location/parks/<code>`).
public struct PotaPark: Equatable, Identifiable, Sendable {
    public var reference: String
    public var name: String
    public var locationDesc: String
    public var latitude: Double
    public var longitude: Double
    public var grid: String
    public var activations: Int
    public var qsos: Int

    public var id: String { reference }

    public init(
        reference: String,
        name: String,
        locationDesc: String,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        grid: String = "",
        activations: Int = 0,
        qsos: Int = 0
    ) {
        self.reference = reference
        self.name = name
        self.locationDesc = locationDesc
        self.latitude = latitude
        self.longitude = longitude
        self.grid = grid
        self.activations = activations
        self.qsos = qsos
    }
}

/// A POTA location code (e.g. "US-PA", "VE-ON") with its center coordinates.
/// Mirrors Android `radio.ks3ckc.ft8af.pota.model.PotaLocation`.
public struct PotaLocation: Equatable, Sendable {
    public var locationDesc: String
    public var locationName: String
    public var latitude: Double
    public var longitude: Double

    public init(locationDesc: String, locationName: String, latitude: Double, longitude: Double) {
        self.locationDesc = locationDesc
        self.locationName = locationName
        self.latitude = latitude
        self.longitude = longitude
    }
}

/// A park paired with its distance (km) from the user's position.
/// Mirrors Android `radio.ks3ckc.ft8af.pota.model.PotaParkWithDistance`.
public struct PotaParkWithDistance: Equatable, Identifiable, Sendable {
    public var park: PotaPark
    public var distanceKm: Double

    public var id: String { park.reference }

    public init(park: PotaPark, distanceKm: Double) {
        self.park = park
        self.distanceKm = distanceKm
    }
}

/// Pure decode + ranking logic for the POTA park picker (recent + nearby park
/// discovery). Network-free; the app-side `PotaParkService` owns the URLSession
/// fetches. A faithful port of Android's `PotaClient` (read endpoints) and the
/// pure helpers in `PotaParkRepository`.
public enum PotaParks {

    // MARK: - Endpoints (read-only; no auth)

    /// pota.app read API base. Same host Android's `PotaClient` uses.
    public static let baseURL = "https://api.pota.app"

    /// GET park details by reference (e.g. "US-1234").
    public static func parkURLString(reference: String) -> String {
        "\(baseURL)/park/\(urlEncode(reference.trimmingCharacters(in: .whitespaces).uppercased()))"
    }

    /// GET all POTA location codes with their center coordinates.
    public static let locationsURLString = "\(baseURL)/locations"

    /// GET all parks within a POTA location code (e.g. "US-PA").
    public static func locationParksURLString(locationCode: String) -> String {
        "\(baseURL)/location/parks/\(urlEncode(locationCode.trimmingCharacters(in: .whitespaces).uppercased()))"
    }

    // MARK: - Ranking constants (ported from PotaParkRepository)

    /// Number of nearest parks returned by `nearbyParks`.
    public static let nearbyLimit = 50

    /// POTA's `/locations` endpoint ships wrong center coordinates for a chunk
    /// of foreign regions (ZA-FS, RU-VL, RO-OT all report centers in Kansas),
    /// so the nearest-by-center ranking is noisy. We pull parks from a generous
    /// set of candidate regions — enough that the user's true region(s) are
    /// always included despite the bad rows — then rank by each park's own
    /// (correct) coordinates.
    public static let nearbyLocationCandidates = 12

    /// Final safety net: drop parks farther than this so a continent-away park
    /// (from a mislocated region) never surfaces under "Nearby".
    public static let nearbyMaxDistanceKm = 1_000.0

    // MARK: - Decoding (tolerant, mirroring Android optString/optDouble/optInt)

    /// Decode a single `/park/<ref>` response into a `PotaPark`. The response has
    /// no `reference` field, so the caller's requested `reference` (uppercased,
    /// trimmed) is used. Returns nil when the payload isn't a JSON object.
    public static func decodePark(_ data: Data, reference: String) -> PotaPark? {
        guard let root = try? JSONSerialization.jsonObject(with: data),
              let o = root as? [String: Any] else { return nil }
        return PotaPark(
            reference: reference.trimmingCharacters(in: .whitespaces).uppercased(),
            name: str(o["name"]),
            locationDesc: str(o["locationDesc"]),
            latitude: dbl(o["latitude"]),
            longitude: dbl(o["longitude"]),
            grid: str(o["grid"]),
            activations: int(o["activations"]),
            qsos: int(o["qsos"])
        )
    }

    /// Decode the `/locations` JSON array. Rows missing a latitude/longitude are
    /// skipped (Android drops NaN rows). Returns nil only when the payload isn't
    /// a JSON array.
    public static func decodeLocations(_ data: Data) -> [PotaLocation]? {
        guard let root = try? JSONSerialization.jsonObject(with: data),
              let arr = root as? [Any] else { return nil }
        var out: [PotaLocation] = []
        out.reserveCapacity(arr.count)
        for element in arr {
            guard let o = element as? [String: Any] else { continue }
            guard let lat = optDbl(o["latitude"]), let lng = optDbl(o["longitude"]) else { continue }
            out.append(PotaLocation(
                locationDesc: str(o["locationDesc"]),
                locationName: str(o["locationName"]),
                latitude: lat,
                longitude: lng
            ))
        }
        return out
    }

    /// Decode a `/location/parks/<code>` JSON array. Malformed elements are
    /// skipped. Returns nil only when the payload isn't a JSON array.
    public static func decodeParks(_ data: Data) -> [PotaPark]? {
        guard let root = try? JSONSerialization.jsonObject(with: data),
              let arr = root as? [Any] else { return nil }
        var out: [PotaPark] = []
        out.reserveCapacity(arr.count)
        for element in arr {
            guard let o = element as? [String: Any] else { continue }
            out.append(PotaPark(
                reference: str(o["reference"]),
                name: str(o["name"]),
                locationDesc: str(o["locationDesc"]),
                latitude: dbl(o["latitude"]),
                longitude: dbl(o["longitude"]),
                grid: str(o["grid"]),
                activations: int(o["activations"]),
                qsos: int(o["qsos"])
            ))
        }
        return out
    }

    // MARK: - Pure ranking helpers (unit-tested; port of PotaParkRepository)

    /// Pick the `count` POTA location codes whose center is nearest to
    /// (`userLat`, `userLng`). Returns each row's `locationDesc` (e.g. "US-PA").
    public static func findNearestLocationCodes(
        userLat: Double,
        userLng: Double,
        locations: [PotaLocation],
        count: Int
    ) -> [String] {
        locations
            .map { ($0, haversineKm(lat1: userLat, lon1: userLng, lat2: $0.latitude, lon2: $0.longitude)) }
            .sorted { $0.1 < $1.1 }
            .prefix(count)
            .map { $0.0.locationDesc }
    }

    /// Compute the distance from (`userLat`, `userLng`) to each park, sort
    /// ascending, and return the closest `limit`. Parks farther than
    /// `maxDistanceKm` are dropped (defaults to no cap). Parks whose coordinates
    /// are both zero (no coordinates) are skipped, matching Android.
    public static func sortParksByDistance(
        _ parks: [PotaPark],
        userLat: Double,
        userLng: Double,
        limit: Int,
        maxDistanceKm: Double = .greatestFiniteMagnitude
    ) -> [PotaParkWithDistance] {
        parks
            .filter { $0.latitude != 0.0 || $0.longitude != 0.0 }
            .map {
                PotaParkWithDistance(
                    park: $0,
                    distanceKm: haversineKm(
                        lat1: userLat, lon1: userLng, lat2: $0.latitude, lon2: $0.longitude)
                )
            }
            .filter { $0.distanceKm <= maxDistanceKm }
            .sorted { $0.distanceKm < $1.distanceKm }
            .prefix(limit)
            .map { $0 }
    }

    /// Split comma-separated park reference strings and deduplicate, preserving
    /// first-seen (most-recent) order. Port of Android's `deduplicateRefs`.
    public static func deduplicateRefs(_ rawRefs: [String]) -> [String] {
        var seen: [String] = []
        var seenSet = Set<String>()
        for raw in rawRefs {
            for part in raw.split(separator: ",", omittingEmptySubsequences: false) {
                let ref = part.trimmingCharacters(in: .whitespaces)
                if !ref.isEmpty && seenSet.insert(ref).inserted {
                    seen.append(ref)
                }
            }
        }
        return seen
    }

    /// Filter parks by name or reference (case-insensitive substring match).
    /// Port of Android's `filterParks`.
    public static func filterParks(_ parks: [PotaPark], query: String) -> [PotaPark] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty { return parks }
        return parks.filter {
            $0.reference.lowercased().contains(q) || $0.name.lowercased().contains(q)
        }
    }

    /// Filter nearby parks by name or reference (case-insensitive substring
    /// match). Port of Android's `filterNearbyParks`.
    public static func filterNearbyParks(
        _ parks: [PotaParkWithDistance], query: String
    ) -> [PotaParkWithDistance] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty { return parks }
        return parks.filter {
            $0.park.reference.lowercased().contains(q) || $0.park.name.lowercased().contains(q)
        }
    }

    // MARK: - Tolerant JSON field extraction (matches PotaSpots)

    private static func str(_ any: Any?) -> String {
        if let s = any as? String { return s }
        if any == nil || any is NSNull { return "" }
        if let n = any as? NSNumber { return n.stringValue }
        return ""
    }

    /// Double with a 0 default (Android `optDouble(key, 0.0)`).
    private static func dbl(_ any: Any?) -> Double { optDbl(any) ?? 0 }

    /// Optional double: nil when the field is absent/null/unparseable
    /// (Android `optDouble(key, NaN)` + `isNaN` check for locations).
    private static func optDbl(_ any: Any?) -> Double? {
        if let n = any as? NSNumber { return n.doubleValue }
        if let s = any as? String { return Double(s) }
        return nil
    }

    private static func int(_ any: Any?) -> Int {
        if let n = any as? NSNumber { return n.intValue }
        if let s = any as? String { return Int(s) ?? 0 }
        return 0
    }

    private static func urlEncode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? s
    }
}
