import FT8Engine
import Foundation

/// Posts a POTA **self-spot** to `https://api.pota.app/spot` so hunters can find
/// an in-progress activation. Networking only; the request body, dial-frequency
/// math, and response classification are pure kit logic in
/// `FT8Engine.PotaSelfSpot` (tested there).
///
/// Direct port of Android's `PotaClient.selfSpot`: same endpoint, same
/// unauthenticated JSON POST (no idToken/Authorization — `/spot` is public,
/// unlike `/adif`), same request shaping (10 s timeout + User-Agent/Accept
/// headers) as `PotaService`/`PotaParkService`. A multi-park ("two-fer")
/// activation posts one spot per park, matching Android's per-ref loop.
actor PotaSelfSpotService {
    static let shared = PotaSelfSpotService()

    private let userAgent = "ft8af-1.0"
    private let timeout: TimeInterval = 10

    private init() {}

    /// Post one self-spot. Returns the kit's `.success` / `.failure(message)`
    /// outcome so the caller can toast the result.
    func selfSpot(
        activator: String,
        spotter: String,
        frequencyKhz: Double,
        mode: String,
        reference: String,
        comments: String = PotaSelfSpot.defaultComment
    ) async -> PotaSelfSpot.Result {
        let request = PotaSelfSpot.Request(
            activator: activator,
            spotter: spotter,
            frequencyKhz: frequencyKhz,
            mode: mode,
            reference: reference,
            comments: comments)

        guard let url = URL(string: PotaSelfSpot.spotURLString),
              let body = try? request.jsonBody() else {
            return .failure("POTA self-spot: bad request")
        }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = body
        req.timeoutInterval = timeout
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")

        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
            return PotaSelfSpot.parseResponse(statusCode: code, body: data)
        } catch is CancellationError {
            return .failure("POTA self-spot cancelled")
        } catch {
            return .failure("POTA self-spot unreachable")
        }
    }

    /// Post a self-spot for every park reference in a (possibly multi-park)
    /// activation. Returns the number of parks successfully spotted and the
    /// total attempted, mirroring Android's success-count loop.
    func selfSpotAll(
        activator: String,
        spotter: String,
        frequencyKhz: Double,
        mode: String,
        references: [String],
        comments: String = PotaSelfSpot.defaultComment
    ) async -> (successCount: Int, total: Int) {
        var success = 0
        for reference in references {
            let result = await selfSpot(
                activator: activator,
                spotter: spotter,
                frequencyKhz: frequencyKhz,
                mode: mode,
                reference: reference,
                comments: comments)
            if result.isSuccess { success += 1 }
        }
        return (success, references.count)
    }
}
