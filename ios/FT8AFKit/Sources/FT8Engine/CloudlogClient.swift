import Foundation

/// Pure request-builders and response-parsers for the Cloudlog/Wavelog/Nextlog
/// HTTP API. No networking here — the app-side `OnlineLogService` performs the
/// actual URLSession calls. Ports the wire shapes from Android
/// `ThirdPartyService` (uploadAdifToCloudlog / CheckCloudlogConnection).
public enum CloudlogClient {

    /// A fully-built HTTP request the app layer just has to send.
    public struct Request: Equatable, Sendable {
        public let url: String
        /// JSON body for POSTs; empty for GETs.
        public let body: String
    }

    /// Normalize a server address: trim whitespace and ensure a trailing "/".
    public static func normalizedAddress(_ address: String) -> String {
        let t = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return t }
        return t.hasSuffix("/") ? t : t + "/"
    }

    /// Build the `POST api/qso` request that uploads one ADIF record. The JSON
    /// key order matches Android's JSONStringer output so payloads are
    /// byte-identical across platforms. Returns nil when the address or API
    /// key is missing (nothing to send).
    ///
    /// Note: the documented endpoint is `api/qso` with NO trailing slash —
    /// Wavelog/Nextlog 308-redirect the slashed form on POST.
    public static func qsoUploadRequest(
        serverAddress: String, apiKey: String, stationId: String, adif: String
    ) -> Request? {
        let base = normalizedAddress(serverAddress)
        guard !base.isEmpty, !apiKey.isEmpty else { return nil }
        let body = "{\"key\":\(jsonString(apiKey))"
            + ",\"station_profile_id\":\(jsonString(stationId))"
            + ",\"type\":\"adif\""
            + ",\"string\":\(jsonString(adif))}"
        return Request(url: base + "api/qso", body: body)
    }

    /// URL for the test-connection GET (`api/auth/<key>`). The key is a path
    /// segment, so the URL is credential-bearing — never log it. Returns nil
    /// when address or key is missing.
    public static func authUrl(serverAddress: String, apiKey: String) -> String? {
        let base = normalizedAddress(serverAddress)
        guard !base.isEmpty, !apiKey.isEmpty else { return nil }
        return base + "api/auth/" + apiKey
    }

    /// Parse the `api/auth` response. Cloudlog, Wavelog and Nextlog all
    /// implement it but differ in whitespace/XML-declaration noise, so match
    /// the meaningful markers on a whitespace-stripped copy.
    public static func isAuthValid(_ response: String?) -> Bool {
        guard let response, !response.isEmpty else { return false }
        let compact = response.filter { !$0.isWhitespace }
        return compact.contains("<status>Valid</status>")
            && compact.contains("<rights>rw</rights>")
    }

    /// Whether an HTTP status code counts as upload success. Cloudlog answers
    /// 201 Created for a stored QSO; some deployments answer 200.
    public static func isSuccessStatus(_ code: Int) -> Bool {
        code == 200 || code == 201
    }

    /// Minimal JSON string literal encoder (quote, backslash, control chars)
    /// so request bodies are deterministic and unit-testable byte-for-byte.
    static func jsonString(_ s: String) -> String {
        var out = "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out + "\""
    }
}
