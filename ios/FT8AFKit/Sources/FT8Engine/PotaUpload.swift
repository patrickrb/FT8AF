import Foundation

/// Pure request-builders and retry policy for pota.app's *authenticated*
/// endpoints (ADIF upload + user jobs). No networking here — the app-side
/// `PotaUploadService` performs the URLSession calls and the retry loop. Ports
/// the wire shapes verbatim from Android `PotaClient.uploadAdif` / `getJobs`.
///
/// Auth note: POTA's API Gateway expects the Cognito ID token in the
/// `Authorization` header **as the raw JWT — not `Bearer <jwt>`**. Sending the
/// `Bearer` prefix gets a 401.
public enum PotaUpload {

    /// Base for the authenticated API (same host as the public spot feed).
    public static let baseURL = "https://api.pota.app"
    public static let userAgent = "ft8af-1.0"

    /// Total upload attempts (the initial try plus retries) before giving up.
    public static let maxAttempts = 3

    /// HTTP statuses that mean POTA's backend was *transiently* unavailable (a
    /// gateway timed out / the upstream Lambda was cold) rather than rejecting the
    /// log itself. Worth retrying; a 4xx or a plain 500 is not — it would fail
    /// again identically. POTA returns 502 when its API Gateway can't reach the
    /// upstream, exactly the failure observed in the field.
    static let retryableStatuses: Set<Int> = [502, 503, 504]

    /// True when a response `status` is a transient gateway failure worth
    /// retrying. (Transport errors — timeouts, connection resets — are retryable
    /// too, but that's judged at the call site where the error is in hand.)
    public static func isRetryable(status: Int) -> Bool {
        retryableStatuses.contains(status)
    }

    /// True when `status` means the token was rejected (401) or forbidden (403):
    /// the caller should re-prompt for sign-in rather than surface a hard error.
    public static func isUnauthorized(status: Int) -> Bool {
        status == 401 || status == 403
    }

    /// Whether an HTTP status counts as upload success (2xx).
    public static func isSuccess(status: Int) -> Bool {
        (200...299).contains(status)
    }

    /// Exponential backoff before retry `attempt` (1-based): 1s, 2s, 4s, … Keeps
    /// the total wait modest while giving a flapping gateway time to recover.
    public static func backoffSeconds(attempt: Int) -> Double {
        Double(1 << max(0, attempt - 1))
    }

    /// How an upload failed, mapped to a user-facing message by the screen.
    ///
    ///  - `.busy`: a gateway status (502/503/504) — POTA's backend was
    ///    transiently down. The service already retried with backoff, so tell the
    ///    user to try again shortly, *not* to go check their park ref.
    ///  - `.serverError`: any other 5xx — POTA accepted the request but rejected
    ///    the log, almost always a bad park ref or a callsign not registered to
    ///    the account.
    ///  - `.network`: a transport error (no HTTP status) — connectivity on our
    ///    side.
    ///  - `.other`: anything else (an unexpected 4xx, etc.).
    public enum FailureKind: Equatable, Sendable {
        case busy
        case serverError
        case network
        case other
    }

    /// Classify a failed upload for the UI. `status` is the HTTP status (nil for
    /// a transport error). Mirrors Android's `classifyUploadFailure`.
    public static func classifyFailure(status: Int?) -> FailureKind {
        guard let status else { return .network }
        if retryableStatuses.contains(status) { return .busy }
        if (500...599).contains(status) { return .serverError }
        return .other
    }

    // MARK: - Requests

    /// Build the multipart `POST {base}/adif` upload request. `idToken` goes in
    /// the `Authorization` header verbatim (raw JWT). The body is
    /// multipart/form-data with a SINGLE part named `adif`
    /// (Content-Type: application/octet-stream) carrying the ADIF UTF-8 document,
    /// matching the pota.app website uploader. `boundary` is injected so the body
    /// is deterministic under test; the app passes a fresh unique one.
    public static func uploadRequest(
        idToken: String, filename: String, adif: String, boundary: String
    ) -> PotaAuth.Request {
        var body = Data()
        var pre = "--\(boundary)\r\n"
        pre += "Content-Disposition: form-data; name=\"adif\"; filename=\"\(filename)\"\r\n"
        pre += "Content-Type: application/octet-stream\r\n\r\n"
        body.append(Data(pre.utf8))
        body.append(Data(adif.utf8))
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))

        return PotaAuth.Request(
            url: "\(baseURL)/adif",
            method: "POST",
            headers: [
                "Authorization": idToken,
                "User-Agent": userAgent,
                "Accept": "application/json",
                "Content-Type": "multipart/form-data; boundary=\(boundary)",
            ],
            body: body)
    }

    /// Build the authenticated `GET {base}/user/jobs` request (raw-JWT auth).
    public static func jobsRequest(idToken: String) -> PotaAuth.Request {
        PotaAuth.Request(
            url: "\(baseURL)/user/jobs",
            method: "GET",
            headers: [
                "Authorization": idToken,
                "User-Agent": userAgent,
                "Accept": "application/json",
            ],
            body: Data())
    }

    /// A fresh, unique multipart boundary (matches Android's
    /// `----ft8af<nanoTime>` shape). Not pure — used by the app when actually
    /// sending; tests pass a fixed boundary to `uploadRequest`.
    public static func newBoundary() -> String {
        "----ft8af\(UInt64(Date().timeIntervalSince1970 * 1_000_000))\(Int.random(in: 0..<1_000_000))"
    }
}
