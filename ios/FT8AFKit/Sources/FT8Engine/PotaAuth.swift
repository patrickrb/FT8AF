import CryptoKit
import Foundation
import Security

/// Pure request-builders, response-parsers and OAuth helpers for pota.app's AWS
/// Cognito hosted-UI login (authorization-code + PKCE) and token refresh. No
/// networking here — the app-side `PotaAuthService` performs the actual
/// URLSession calls, stores the refresh token in the Keychain, and drives the
/// WKWebView. Ports the wire shapes verbatim from Android `PotaAuth.kt`.
///
/// POTA's pool / app-client IDs are public — they ship in pota.app's own JS
/// bundle and the open-source `pota-adif-upload` client. The app client is a
/// *public* client (no secret), so token exchange sends no Basic auth.
public enum PotaAuth {

    // MARK: - Public POTA Cognito identifiers

    /// Cognito user pool.
    public static let poolId = "us-east-2_nA5jZ0klh"
    /// Public app client (no secret).
    public static let clientId = "7hluqct0n2nckib7i7sd5753oa"
    /// Cognito IDP endpoint used for REFRESH_TOKEN_AUTH.
    public static let cognitoIdpURL = "https://cognito-idp.us-east-2.amazonaws.com/"
    /// Hosted-UI (managed login) origin for the OAuth2 code flow.
    public static let hostedUI = "https://parksontheair.auth.us-east-2.amazoncognito.com"
    /// OAuth scope requested at the authorize endpoint.
    public static let scope = "openid email phone profile"
    /// The single redirect URI POTA registered on its Cognito app client. We
    /// can't register our own (custom scheme / localhost all return
    /// redirect_mismatch), so the WebView watches for navigation to this URL and
    /// lifts the `?code=` out before pota.app actually loads.
    public static let redirectURI = "https://pota.app/"

    /// Refresh ~5 min before the ID token's nominal 60-min lifetime expires.
    public static let idTokenTTL: TimeInterval = 55 * 60

    // MARK: - Request value type

    /// A fully-built HTTP request the app layer just has to send.
    public struct Request: Equatable, Sendable {
        public let url: String
        public let method: String
        public let headers: [String: String]
        public let body: Data

        public init(url: String, method: String, headers: [String: String], body: Data) {
            self.url = url
            self.method = method
            self.headers = headers
            self.body = body
        }
    }

    // MARK: - PKCE

    /// A PKCE verifier/challenge pair for one hosted-UI login attempt (S256).
    public struct Pkce: Equatable, Sendable {
        public let verifier: String
        public let challenge: String

        public init(verifier: String, challenge: String) {
            self.verifier = verifier
            self.challenge = challenge
        }
    }

    /// Mint a fresh PKCE pair: verifier = 32 random bytes base64url (no padding),
    /// challenge = base64url(SHA256(verifier ASCII bytes)). Hold onto it for the
    /// matching `tokenExchangeRequest`.
    public static func newPkce() -> Pkce {
        var raw = [UInt8](repeating: 0, count: 32)
        // SecRandomCopyBytes is the CSPRNG; fall back to SystemRandomNumberGenerator
        // only if it ever fails (it effectively never does on iOS).
        if SecRandomCopyBytes(kSecRandomDefault, raw.count, &raw) != errSecSuccess {
            var rng = SystemRandomNumberGenerator()
            for i in raw.indices { raw[i] = UInt8.random(in: .min ... .max, using: &rng) }
        }
        let verifier = base64url(Data(raw))
        return Pkce(verifier: verifier, challenge: codeChallenge(forVerifier: verifier))
    }

    /// The S256 code challenge for a given verifier: base64url(SHA256(ascii)).
    /// Split out from `newPkce` so it's deterministically unit-testable.
    public static func codeChallenge(forVerifier verifier: String) -> String {
        let digest = SHA256.hash(data: Data(verifier.utf8))
        return base64url(Data(digest))
    }

    // MARK: - Authorize URL

    /// The hosted-UI authorize URL to load in the login WebView for `codeChallenge`.
    /// GET {hostedUI}/oauth2/authorize?client_id=…&response_type=code&scope=…&
    /// redirect_uri=…&code_challenge=…&code_challenge_method=S256
    public static func authorizeURL(codeChallenge: String) -> String {
        let q = "client_id=\(clientId)"
            + "&response_type=code"
            + "&scope=\(formEncode(scope))"
            + "&redirect_uri=\(formEncode(redirectURI))"
            + "&code_challenge=\(codeChallenge)"
            + "&code_challenge_method=S256"
        return "\(hostedUI)/oauth2/authorize?\(q)"
    }

    // MARK: - Token exchange (authorization_code)

    /// Build the `POST {hostedUI}/oauth2/token` request that trades an
    /// authorization `code` for tokens. Public client → no client secret / no
    /// Basic auth; the body is application/x-www-form-urlencoded.
    public static func tokenExchangeRequest(code: String, codeVerifier: String) -> Request {
        let form = "grant_type=authorization_code"
            + "&client_id=\(clientId)"
            + "&code=\(formEncode(code))"
            + "&redirect_uri=\(formEncode(redirectURI))"
            + "&code_verifier=\(formEncode(codeVerifier))"
        return Request(
            url: "\(hostedUI)/oauth2/token",
            method: "POST",
            headers: [
                "Content-Type": "application/x-www-form-urlencoded",
                "Accept": "application/json",
            ],
            body: Data(form.utf8))
    }

    /// The tokens returned by the token endpoint.
    public struct TokenResponse: Equatable, Sendable {
        public let idToken: String
        public let refreshToken: String

        public init(idToken: String, refreshToken: String) {
            self.idToken = idToken
            self.refreshToken = refreshToken
        }
    }

    /// Parse the token-endpoint JSON: `{ id_token, refresh_token, … }`. Returns
    /// nil if the body isn't JSON or is missing either token (e.g. an
    /// `{"error":"invalid_grant"}` shape).
    public static func parseTokenResponse(_ data: Data?) -> TokenResponse? {
        guard let data,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = obj["id_token"] as? String, !id.isEmpty,
              let refresh = obj["refresh_token"] as? String, !refresh.isEmpty
        else { return nil }
        return TokenResponse(idToken: id, refreshToken: refresh)
    }

    // MARK: - Refresh (REFRESH_TOKEN_AUTH)

    /// Build the Cognito `InitiateAuth` request that trades the stored refresh
    /// token for a fresh ID token — a plain x-amz-json-1.1 POST, no SRP, no SDK.
    public static func refreshRequest(refreshToken: String) -> Request {
        // Hand-built so the body is deterministic (byte-stable) for tests; the
        // shape matches Android's JSONObject output.
        let body = "{\"AuthFlow\":\"REFRESH_TOKEN_AUTH\""
            + ",\"ClientId\":\"\(clientId)\""
            + ",\"AuthParameters\":{\"REFRESH_TOKEN\":\(jsonString(refreshToken))}}"
        return Request(
            url: cognitoIdpURL,
            method: "POST",
            headers: [
                "Content-Type": "application/x-amz-json-1.1",
                "X-Amz-Target": "AWSCognitoIdentityProviderService.InitiateAuth",
            ],
            body: Data(body.utf8))
    }

    /// Parse the refresh response: `{ "AuthenticationResult": { "IdToken": … } }`.
    /// Returns nil on a non-JSON body or an error shape (missing IdToken).
    public static func parseRefreshResponse(_ data: Data?) -> String? {
        guard let data,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let result = obj["AuthenticationResult"] as? [String: Any],
              let id = result["IdToken"] as? String, !id.isEmpty
        else { return nil }
        return id
    }

    // MARK: - Redirect classification (security-critical)

    /// Where a navigation in the OAuth WebView lands relative to POTA's
    /// registered redirect URI.
    public enum RedirectClassification: Equatable, Sendable {
        /// Not the redirect URI — let the WebView load it (Google, Cognito, …).
        case notRedirect
        /// The redirect URI but with no `code` (provider error / user cancelled).
        case noCode
        /// The redirect URI carrying the authorization `code` to exchange.
        case withCode(String)
    }

    /// Classify a WebView navigation against POTA's hosted-UI redirect URI
    /// (`https://pota.app/`). **Security-critical:** origin is matched on parsed
    /// scheme + host + (absence of) port — never a raw `startsWith` — so a
    /// lookalike like `https://pota.app.evil.com/?code=…`, an `http://` downgrade,
    /// a `https://pota.app@evil.com/` userinfo trick, or an explicit alternate
    /// port can't masquerade as the redirect and trick us into exchanging an
    /// attacker-supplied code.
    public static func classifyRedirect(_ url: String) -> RedirectClassification {
        guard let comps = URLComponents(string: url) else { return .notRedirect }
        // Exact scheme (https) + host (pota.app), case-insensitive; reject any
        // explicit port (the real redirect carries none).
        guard comps.scheme?.lowercased() == "https",
              comps.host?.lowercased() == "pota.app",
              comps.port == nil
        else { return .notRedirect }
        // Path is always "/" or deeper for an https URL with a host; the redirect
        // lives at the origin root, so accept any path on the exact host.
        let code = comps.queryItems?.first(where: { $0.name == "code" })?.value
        if let code, !code.isEmpty { return .withCode(code) }
        return .noCode
    }

    // MARK: - JWT claims

    /// Decode a JWT ID token's `exp` claim to an absolute expiry `Date`. Returns
    /// nil if the token is malformed or carries no numeric `exp`. Used to drive
    /// the near-expiry refresh — never for signature verification (the token is
    /// only ever forwarded to POTA, which validates it).
    public static func jwtExpiry(_ idToken: String) -> Date? {
        guard let exp = jwtClaims(idToken)?["exp"] else { return nil }
        if let n = exp as? Double { return Date(timeIntervalSince1970: n) }
        if let n = exp as? Int { return Date(timeIntervalSince1970: Double(n)) }
        return nil
    }

    /// Pull the `email` claim out of a JWT ID token (for display only).
    public static func emailClaim(_ idToken: String) -> String? {
        guard let email = jwtClaims(idToken)?["email"] as? String, !email.isEmpty
        else { return nil }
        return email
    }

    /// Whether a cached ID token with the given `expiry` should be refreshed now:
    /// true when it has passed, or is within `skew` seconds of, `now`. A nil
    /// expiry (unreadable token) is treated as "refresh" so a bad token is never
    /// reused indefinitely.
    public static func needsRefresh(expiry: Date?, now: Date = Date(), skew: TimeInterval = 5 * 60) -> Bool {
        guard let expiry else { return true }
        return now.addingTimeInterval(skew) >= expiry
    }

    /// The effective expiry to cache for a freshly-obtained ID token: the earlier
    /// of the JWT's own `exp` and `now + idTokenTTL` (matches Android's fixed
    /// 55-min cap while still honouring a shorter server-side lifetime).
    public static func cacheExpiry(forIdToken idToken: String, now: Date = Date()) -> Date {
        let ttlBound = now.addingTimeInterval(idTokenTTL)
        guard let jwtExp = jwtExpiry(idToken) else { return ttlBound }
        return min(jwtExp, ttlBound)
    }

    private static func jwtClaims(_ idToken: String) -> [String: Any]? {
        let parts = idToken.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count >= 2, let payload = base64urlDecode(String(parts[1])) else { return nil }
        return (try? JSONSerialization.jsonObject(with: payload)) as? [String: Any]
    }

    // MARK: - Encoding helpers

    /// base64url with no padding (`+`→`-`, `/`→`_`, strip `=`).
    static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Decode a base64url (no-padding) string back to bytes.
    static func base64urlDecode(_ s: String) -> Data? {
        var b64 = s.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let pad = (4 - b64.count % 4) % 4
        b64 += String(repeating: "=", count: pad)
        return Data(base64Encoded: b64)
    }

    /// application/x-www-form-urlencoded percent-encoding matching Java's
    /// `URLEncoder.encode(s, UTF-8)`, so query/body params are byte-identical to
    /// Android: unreserved `[A-Za-z0-9.*_-]` pass through, space→`+`, else %XX per
    /// UTF-8 byte. (Same routine as QrzLogbookClient.formEncode.)
    static func formEncode(_ s: String) -> String {
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

    /// Minimal JSON string literal encoder (same as CloudlogClient.jsonString) so
    /// the refresh body is deterministic and unit-testable byte-for-byte.
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
