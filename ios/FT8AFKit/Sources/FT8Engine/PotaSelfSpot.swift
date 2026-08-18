import Foundation

/// Pure logic for a POTA **self-spot** — the write that advertises your live
/// activation on `https://api.pota.app/spot` so hunters can find you. Network-free
/// and UI-free: request-body construction, dial-frequency math, and response
/// classification live here so they're unit-testable; the app-side
/// `PotaSelfSpotService` owns the URLSession POST.
///
/// Direct port of Android `radio.ks3ckc.ft8af.pota.PotaClient.selfSpot` +
/// `PotaSpotFrequency`: same base URL, `/spot` path, JSON body field set
/// (`activator`, `spotter`, `frequency`, `reference`, `mode`, `source`,
/// `comments`), and the dial-frequency-only rule for the spotted kHz.
///
/// This POST is **unauthenticated** — Android sends no idToken / Authorization
/// header for `/spot` (only `/adif` upload and `/user/jobs` carry the Cognito
/// token). So no auth plumbing is needed here.
public enum PotaSelfSpot {

    // MARK: - Endpoint

    /// Same base as the read endpoints (`PotaSpots`, `PotaParks`).
    public static let baseURL = "https://api.pota.app"

    /// Self-spot write path.
    public static let spotPath = "/spot"

    /// Full self-spot URL.
    public static var spotURLString: String { baseURL + spotPath }

    /// The comment Android posts with every FT8 self-spot. Kept identical so the
    /// two clients look the same to hunters.
    public static let defaultComment = "CQ POTA via FT8AF"

    /// The `source` tag Android stamps on the spot so pota.app attributes it to
    /// this app.
    public static let source = "FT8AF"

    // MARK: - Frequency math (port of PotaSpotFrequency.kt)

    /// The dial (carrier) frequency in kHz for a self-spot, from the dial
    /// frequency in Hz. Direct port of Android `potaSpotFrequencyKhz`:
    /// `bandHz / 1000.0`.
    ///
    /// A self-spot must advertise the **dial** frequency (e.g. 14074.0 kHz on
    /// 20 m) so hunters tune the shared FT8 calling frequency — NOT the audio
    /// waterfall offset (~200-3500 Hz), which would produce a ~1-3.5 kHz spot on
    /// every band. (Contrast the PSKReporter path, which reports
    /// `dial + audioOffset` because it maps an individual decoded signal.)
    public static func frequencyKhz(dialHz: Int64) -> Double {
        Double(dialHz) / 1000.0
    }

    /// Dial (carrier) frequency in Hz for one of the app's band strings
    /// ("20M", "40M", …). Mirrors `LiveEngine.bandToFreqMhz` so a self-spot
    /// advertises the exact FT8 dial the engine transmits on. Unknown bands fall
    /// back to the 20 m dial, matching that table's default.
    public static func dialHz(forBand band: String) -> Int64 {
        let mhz: Double
        switch band {
        case "160M": mhz = 1.840
        case "80M":  mhz = 3.573
        case "60M":  mhz = 5.357
        case "40M":  mhz = 7.074
        case "30M":  mhz = 10.136
        case "20M":  mhz = 14.074
        case "17M":  mhz = 18.100
        case "15M":  mhz = 21.074
        case "12M":  mhz = 24.915
        case "10M":  mhz = 28.074
        case "6M":   mhz = 50.313
        case "2M":   mhz = 144.174
        default:     mhz = 14.074
        }
        return Int64((mhz * 1_000_000).rounded())
    }

    /// Convenience: the spotted kHz for the app's current band string.
    public static func frequencyKhz(forBand band: String) -> Double {
        frequencyKhz(dialHz: dialHz(forBand: band))
    }

    // MARK: - Request

    /// One self-spot's payload. Field set + formatting mirror Android's
    /// `JSONObject` in `PotaClient.selfSpot`: callsigns and reference uppercased,
    /// frequency rendered as a `"%.1f"` **string** in kHz, plus the fixed
    /// `source` tag.
    public struct Request: Equatable, Sendable {
        public var activator: String
        public var spotter: String
        public var frequencyKhz: Double
        public var mode: String
        public var reference: String
        public var comments: String

        public init(
            activator: String,
            spotter: String,
            frequencyKhz: Double,
            mode: String,
            reference: String,
            comments: String = PotaSelfSpot.defaultComment
        ) {
            self.activator = activator
            self.spotter = spotter
            self.frequencyKhz = frequencyKhz
            self.mode = mode
            self.reference = reference
            self.comments = comments
        }

        /// The JSON fields as a dictionary, exactly matching Android's body:
        /// `frequency` is a `"%.1f"` string, calls/reference uppercased.
        public var jsonFields: [String: String] {
            [
                "activator": activator.uppercased(),
                "spotter": spotter.uppercased(),
                "frequency": String(format: "%.1f", frequencyKhz),
                "reference": reference.uppercased(),
                "mode": mode,
                "source": PotaSelfSpot.source,
                "comments": comments,
            ]
        }

        /// UTF-8 JSON body for the POST. Sorted keys keep it deterministic for
        /// tests; the server is order-insensitive.
        public func jsonBody() throws -> Data {
            try JSONSerialization.data(
                withJSONObject: jsonFields, options: [.sortedKeys])
        }
    }

    // MARK: - Response

    /// Outcome of a self-spot POST. Android treats any 2xx (non-null body) as
    /// success and everything else as failure; this mirrors that with a
    /// human-readable message for the failure toast.
    public enum Result: Equatable, Sendable {
        case success
        case failure(String)

        public var isSuccess: Bool {
            if case .success = self { return true }
            return false
        }
    }

    /// Classify a POST response the way Android's `httpPost` does: HTTP 2xx is
    /// success, anything else is a failure carrying the status (and a short
    /// snippet of the error body when present).
    public static func parseResponse(statusCode: Int, body: Data?) -> Result {
        if (200..<300).contains(statusCode) {
            return .success
        }
        var msg = "POTA self-spot failed (HTTP \(statusCode))"
        if let body, !body.isEmpty,
           let text = String(data: body, encoding: .utf8)?
               .trimmingCharacters(in: .whitespacesAndNewlines),
           !text.isEmpty {
            msg += ": " + String(text.prefix(160))
        }
        return .failure(msg)
    }
}
