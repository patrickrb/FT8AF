import FT8Engine
import Foundation

/// Live POTA activator-spot feed for the Hunt tab. Fetch + refresh state only;
/// all response decoding, filtering, and formatting is pure kit logic in
/// `FT8Engine.PotaSpots` (tested there with canned fixtures).
///
/// Mirrors Android's `PotaSpotsRepository`: same endpoint, 60 s refresh cadence
/// (driven by the screen's `.task` while the Hunt tab is visible), same
/// FT8-mode constraint for hunting — except the filter is applied client-side
/// behind a toggle so it flips instantly without a refetch.
@Observable @MainActor
final class PotaService {
    static let shared = PotaService()

    /// All decoded spots from the last successful fetch (every mode).
    var spots: [PotaSpot] = []
    var isLoading = false
    /// Human-readable failure from the last fetch, nil after a success.
    var lastError: String?
    var lastUpdatedAt: Date?
    /// Hunt-tab constraint: Android hunts FT8-relevant spots only; the toggle
    /// lets the operator peek at the full feed.
    var ft8Only = true

    /// Spots as the Hunt list shows them (mode filter + frequency sort).
    var displaySpots: [PotaSpot] {
        PotaSpots.forDisplay(spots, ft8Only: ft8Only)
    }

    private init() {}

    /// One fetch of the public activator-spot feed. Safe to call concurrently
    /// (re-entrant calls are dropped while a fetch is in flight).
    func refresh() async {
        if isLoading { return }
        guard let url = URL(string: PotaSpots.activatorSpotsURLString) else { return }
        isLoading = true
        defer { isLoading = false }

        var req = URLRequest(url: url)
        req.timeoutInterval = 10
        req.setValue("ft8af-1.0", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json", forHTTPHeaderField: "Accept")

        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
                let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
                lastError = "POTA feed error (HTTP \(code))"
                return
            }
            guard let decoded = PotaSpots.decode(data) else {
                lastError = "POTA feed returned unexpected data"
                return
            }
            spots = decoded
            lastError = nil
            lastUpdatedAt = Date()
        } catch is CancellationError {
            // Tab switched away mid-fetch — not an error.
        } catch {
            lastError = "POTA feed unreachable"
        }
    }
}
