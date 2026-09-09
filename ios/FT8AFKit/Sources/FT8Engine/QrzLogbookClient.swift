import Foundation

/// Pure request-builders and response-parsers for the QRZ.com logbook API
/// (logbook.qrz.com/api). No networking here — the app-side `OnlineLogService`
/// performs the actual URLSession calls. Ports the wire shapes from Android
/// `ThirdPartyService` (uploadAdifToQrz / CheckQRZConnection / parseQrzResult).
public enum QrzLogbookClient {

    /// All QRZ logbook calls POST to this single endpoint. POST keeps the API
    /// key (and ADIF) in the body rather than the URL, where it could leak via
    /// proxies or server access logs.
    public static let endpoint = "https://logbook.qrz.com/api"

    /// Form body for `ACTION=INSERT` uploading a single ADIF record.
    public static func insertBody(apiKey: String, adif: String) -> String {
        "KEY=\(formEncode(apiKey))&ACTION=INSERT&ADIF=\(formEncode(adif))"
    }

    /// Form body for `ACTION=STATUS` (the test-connection call).
    public static func statusBody(apiKey: String) -> String {
        "KEY=\(formEncode(apiKey))&ACTION=STATUS"
    }

    /// Extract the `RESULT` value from a QRZ response — an `&`-separated body
    /// of KEY=VALUE pairs (e.g. `RESULT=OK&COUNT=1&LOGID=...`). Returns nil if
    /// the response is nil/empty or carries no RESULT field.
    public static func result(of response: String?) -> String? {
        guard let response, !response.isEmpty else { return nil }
        for pair in response.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1,
                                omittingEmptySubsequences: false)
            if kv.count > 1, kv[0] == "RESULT" {
                return String(kv[1])
            }
        }
        return nil
    }

    /// INSERT success: RESULT=OK, or RESULT=REPLACE (the QSO already existed
    /// and was updated — still "the record is now on QRZ").
    public static func isInsertSuccess(_ response: String?) -> Bool {
        let r = result(of: response)
        return r == "OK" || r == "REPLACE"
    }

    /// STATUS success: RESULT=OK only.
    public static func isStatusOk(_ response: String?) -> Bool {
        result(of: response) == "OK"
    }

    /// application/x-www-form-urlencoded encoding matching Java's
    /// `URLEncoder.encode(s, UTF-8)` (what the Android app sends): unreserved
    /// `[A-Za-z0-9.*_-]` pass through, space becomes `+`, everything else is
    /// %XX-escaped per UTF-8 byte.
    public static func formEncode(_ s: String) -> String {
        var out = ""
        for byte in Array(s.utf8) {
            switch byte {
            case UInt8(ascii: "a")...UInt8(ascii: "z"),
                 UInt8(ascii: "A")...UInt8(ascii: "Z"),
                 UInt8(ascii: "0")...UInt8(ascii: "9"),
                 UInt8(ascii: "."), UInt8(ascii: "-"),
                 UInt8(ascii: "*"), UInt8(ascii: "_"):
                out.append(Character(UnicodeScalar(byte)))
            case UInt8(ascii: " "):
                out.append("+")
            default:
                out += String(format: "%%%02X", byte)
            }
        }
        return out
    }
}
