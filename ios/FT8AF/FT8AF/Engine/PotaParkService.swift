import FT8Engine
import Foundation

/// Read-only POTA park discovery for the Activate-tab park picker. Fetches park
/// details, location codes, and per-location park lists from pota.app's public
/// (no-auth) read API; all decoding + distance ranking is pure kit logic in
/// `FT8Engine.PotaParks` (tested there with canned fixtures).
///
/// Direct port of Android's `PotaClient` (read endpoints) + `PotaParkRepository`
/// discovery: same endpoints, same 30-minute in-memory cache TTL, same
/// wide-candidate + max-distance nearby ranking. An `actor` gives the caches the
/// same thread-safety Android gets from `ConcurrentHashMap`/`@Volatile` without a
/// lock, since overlapping sheet opens can fetch concurrently.
///
/// SCOPE: read-only. No self-spot / upload / auth here — those are separate work.
actor PotaParkService {
    static let shared = PotaParkService()

    private let userAgent = "ft8af-1.0"
    private let timeout: TimeInterval = 10
    private let cacheTTL: TimeInterval = 30 * 60  // 30 minutes, matching Android

    // Caches (actor-isolated → no data races even under overlapping fetches).
    private var cachedLocations: [PotaLocation]?
    private var locationsTimestamp: Date?
    private var parkCache: [String: [PotaPark]] = [:]
    private var parkCacheTimestamp: [String: Date] = [:]
    private var lookupCache: [String: PotaPark] = [:]

    private init() {}

    // MARK: - Discovery (mirrors PotaParkRepository)

    /// The `PotaParks.nearbyLimit` closest parks to (`userLat`, `userLng`),
    /// sorted ascending by distance. Fetches parks for the
    /// `nearbyLocationCandidates` closest location codes concurrently (a wide net
    /// to absorb POTA's mislocated region centers), then ranks by each park's own
    /// coordinates and drops anything beyond `nearbyMaxDistanceKm`. Returns [] on
    /// any network failure (the picker degrades to recents + manual entry).
    func nearbyParks(userLat: Double, userLng: Double) async -> [PotaParkWithDistance] {
        guard let locations = await locations() else { return [] }
        let closest = PotaParks.findNearestLocationCodes(
            userLat: userLat, userLng: userLng,
            locations: locations, count: PotaParks.nearbyLocationCandidates)

        // Fetch the candidate park lists concurrently — one slow region shouldn't
        // serialize the whole sheet open (Android uses async/awaitAll).
        var allParks: [PotaPark] = []
        await withTaskGroup(of: [PotaPark]?.self) { group in
            for code in closest {
                group.addTask { await self.parksForLocation(code) }
            }
            for await result in group {
                if let result { allParks.append(contentsOf: result) }
            }
        }
        return PotaParks.sortParksByDistance(
            allParks, userLat: userLat, userLng: userLng,
            limit: PotaParks.nearbyLimit, maxDistanceKm: PotaParks.nearbyMaxDistanceKm)
    }

    /// Park details for recent park references (from activation history), newest
    /// first. Refs are deduped/split; details resolved via the cached lookup
    /// endpoint. Unknown refs (lookup failed) are dropped.
    func recentParksWithDetails(rawRefs: [String]) async -> [PotaPark] {
        let refs = PotaParks.deduplicateRefs(rawRefs)
        var out: [PotaPark] = []
        for ref in refs {
            if let park = await lookupParkCached(ref) { out.append(park) }
        }
        return out
    }

    // MARK: - Endpoint wrappers with caching

    private func lookupParkCached(_ reference: String) async -> PotaPark? {
        let key = reference.trimmingCharacters(in: .whitespaces).uppercased()
        if key.isEmpty { return nil }
        if let hit = lookupCache[key] { return hit }
        guard let data = await httpGet(PotaParks.parkURLString(reference: key)),
              let park = PotaParks.decodePark(data, reference: key) else { return nil }
        lookupCache[key] = park
        return park
    }

    private func locations() async -> [PotaLocation]? {
        if let cached = cachedLocations, let ts = locationsTimestamp,
           Date().timeIntervalSince(ts) < cacheTTL {
            return cached
        }
        guard let data = await httpGet(PotaParks.locationsURLString),
              let result = PotaParks.decodeLocations(data) else {
            return cachedLocations  // fall back to a stale cache if present
        }
        cachedLocations = result
        locationsTimestamp = Date()
        return result
    }

    private func parksForLocation(_ locationCode: String) async -> [PotaPark]? {
        let code = locationCode.trimmingCharacters(in: .whitespaces).uppercased()
        if code.isEmpty { return nil }
        if let cached = parkCache[code], let ts = parkCacheTimestamp[code],
           Date().timeIntervalSince(ts) < cacheTTL {
            return cached
        }
        guard let data = await httpGet(PotaParks.locationParksURLString(locationCode: code)),
              let result = PotaParks.decodeParks(data) else {
            return parkCache[code]  // stale fallback
        }
        parkCache[code] = result
        parkCacheTimestamp[code] = Date()
        return result
    }

    // MARK: - HTTP

    /// One GET returning the raw body on HTTP 200, nil on any error/non-200.
    /// Mirrors `PotaService.refresh`'s request shaping (timeout + headers).
    private func httpGet(_ urlString: String) async -> Data? {
        guard let url = URL(string: urlString) else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = timeout
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else { return nil }
            return data
        } catch {
            return nil
        }
    }
}
