import FT8Engine
import Foundation

/// Posts one ADIF document to POTA's authenticated `/adif` endpoint with the
/// retry/backoff policy defined (and unit-tested) in `FT8Engine.PotaUpload`.
/// Networking only; request shaping, retry classification, and backoff timing
/// all live in the kit.
///
/// Direct port of Android's `PotaClient.uploadAdif`: raw-JWT Authorization
/// header, single multipart `adif` part, up to 3 attempts with 1s/2s/4s backoff,
/// retrying ONLY on 502/503/504 or a transport error (a 4xx / plain 500 is
/// terminal). A 401/403 surfaces as `.unauthorized` so the caller can re-prompt
/// for sign-in and retry.
actor PotaUploadService {
    static let shared = PotaUploadService()

    private init() {}

    /// The outcome of one document upload.
    enum Outcome: Equatable {
        case success
        /// Token rejected — the caller should re-authenticate and retry.
        case unauthorized
        /// Terminal failure, classified for a user-facing message.
        case failure(PotaUpload.FailureKind)
    }

    /// Upload a single ADIF document. `idToken` is a raw Cognito ID token.
    func uploadAdif(idToken: String, filename: String, adif: String) async -> Outcome {
        var last: Outcome = .failure(.other)
        for attempt in 1...PotaUpload.maxAttempts {
            if attempt > 1 {
                let backoff = PotaUpload.backoffSeconds(attempt: attempt - 1)
                try? await Task.sleep(nanoseconds: UInt64(backoff * 1_000_000_000))
            }
            let (outcome, retryable) = await attemptOnce(
                idToken: idToken, filename: filename, adif: adif)
            last = outcome
            if !retryable { break }
        }
        return last
    }

    /// One HTTP attempt. Returns the outcome and whether it's worth retrying
    /// (a transient gateway status or a transport error).
    private func attemptOnce(
        idToken: String, filename: String, adif: String
    ) async -> (Outcome, retryable: Bool) {
        let req = PotaUpload.uploadRequest(
            idToken: idToken, filename: filename, adif: adif,
            boundary: PotaUpload.newBoundary())
        guard let url = URL(string: req.url) else { return (.failure(.other), false) }

        var r = URLRequest(url: url)
        r.httpMethod = req.method
        r.httpBody = req.body
        r.timeoutInterval = 30
        for (k, v) in req.headers { r.setValue(v, forHTTPHeaderField: k) }

        do {
            let (_, resp) = try await URLSession.shared.data(for: r)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
            if PotaUpload.isSuccess(status: code) { return (.success, false) }
            if PotaUpload.isUnauthorized(status: code) { return (.unauthorized, false) }
            let retryable = PotaUpload.isRetryable(status: code)
            return (.failure(PotaUpload.classifyFailure(status: code)), retryable)
        } catch is CancellationError {
            return (.failure(.network), false)
        } catch {
            // Transport error (timeout, connection reset) — retryable.
            return (.failure(.network), true)
        }
    }

    /// Fetch the user's recent upload/processing jobs (authenticated). Raw JSON
    /// string, or nil on any failure. Not retried — it's a best-effort status peek.
    func jobs(idToken: String) async -> String? {
        let req = PotaUpload.jobsRequest(idToken: idToken)
        guard let url = URL(string: req.url) else { return nil }
        var r = URLRequest(url: url)
        r.httpMethod = req.method
        r.timeoutInterval = 15
        for (k, v) in req.headers { r.setValue(v, forHTTPHeaderField: k) }
        do {
            let (data, resp) = try await URLSession.shared.data(for: r)
            guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else { return nil }
            return String(data: data, encoding: .utf8)
        } catch {
            return nil
        }
    }
}
