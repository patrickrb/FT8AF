import XCTest
import FT8Engine

final class PotaAuthTests: XCTestCase {

    // Local base64url (no padding) so tests don't depend on FT8Engine internals.
    private func b64url(_ s: String) -> String {
        Data(s.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Build a `header.payload.sig` JWT with the given JSON payload.
    private func jwt(payload: String) -> String {
        "\(b64url("{\"alg\":\"none\"}")).\(b64url(payload)).sig"
    }

    // MARK: - PKCE

    func testCodeChallengeMatchesRfc7636Example() {
        // RFC 7636 Appendix B known verifier/challenge vector.
        let verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        XCTAssertEqual(
            PotaAuth.codeChallenge(forVerifier: verifier),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    func testNewPkceProducesUnpaddedUrlSafeVerifierAndMatchingChallenge() {
        let pkce = PotaAuth.newPkce()
        // 32 random bytes → 43-char base64url, no padding, url-safe alphabet only.
        XCTAssertEqual(pkce.verifier.count, 43)
        XCTAssertFalse(pkce.verifier.contains("="))
        XCTAssertFalse(pkce.verifier.contains("+"))
        XCTAssertFalse(pkce.verifier.contains("/"))
        XCTAssertEqual(pkce.challenge, PotaAuth.codeChallenge(forVerifier: pkce.verifier))
        // Two mints must differ (real randomness).
        XCTAssertNotEqual(pkce.verifier, PotaAuth.newPkce().verifier)
    }

    // MARK: - authorizeURL

    func testAuthorizeURLContainsAllOAuthParams() {
        let url = PotaAuth.authorizeURL(codeChallenge: "CHAL123")
        XCTAssertTrue(url.hasPrefix(
            "https://parksontheair.auth.us-east-2.amazoncognito.com/oauth2/authorize?"))
        XCTAssertTrue(url.contains("client_id=7hluqct0n2nckib7i7sd5753oa"))
        XCTAssertTrue(url.contains("response_type=code"))
        // Scope spaces are form-encoded as '+' (verbatim from Android's URLEncoder).
        XCTAssertTrue(url.contains("scope=openid+email+phone+profile"))
        // redirect_uri must be exactly https://pota.app/ percent-encoded.
        XCTAssertTrue(url.contains("redirect_uri=https%3A%2F%2Fpota.app%2F"))
        XCTAssertTrue(url.contains("code_challenge=CHAL123"))
        XCTAssertTrue(url.contains("code_challenge_method=S256"))
    }

    // MARK: - tokenExchangeRequest

    func testTokenExchangeRequestShape() {
        let req = PotaAuth.tokenExchangeRequest(code: "abc/123", codeVerifier: "ver-XYZ")
        XCTAssertEqual(req.url,
            "https://parksontheair.auth.us-east-2.amazoncognito.com/oauth2/token")
        XCTAssertEqual(req.method, "POST")
        XCTAssertEqual(req.headers["Content-Type"], "application/x-www-form-urlencoded")
        // Public client: no Authorization / Basic header.
        XCTAssertNil(req.headers["Authorization"])
        let body = String(data: req.body, encoding: .utf8) ?? ""
        XCTAssertEqual(
            body,
            "grant_type=authorization_code"
                + "&client_id=7hluqct0n2nckib7i7sd5753oa"
                + "&code=abc%2F123"
                + "&redirect_uri=https%3A%2F%2Fpota.app%2F"
                + "&code_verifier=ver-XYZ")
    }

    func testParseTokenResponseValid() {
        let json = #"{"id_token":"ID.jwt.sig","refresh_token":"RT123","token_type":"Bearer"}"#
        let parsed = PotaAuth.parseTokenResponse(Data(json.utf8))
        XCTAssertEqual(parsed?.idToken, "ID.jwt.sig")
        XCTAssertEqual(parsed?.refreshToken, "RT123")
    }

    func testParseTokenResponseErrorShapesReturnNil() {
        XCTAssertNil(PotaAuth.parseTokenResponse(Data(#"{"error":"invalid_grant"}"#.utf8)))
        // Missing refresh_token → nil (can't persist a session).
        XCTAssertNil(PotaAuth.parseTokenResponse(Data(#"{"id_token":"x"}"#.utf8)))
        XCTAssertNil(PotaAuth.parseTokenResponse(Data("not json".utf8)))
        XCTAssertNil(PotaAuth.parseTokenResponse(nil))
    }

    // MARK: - refreshRequest

    func testRefreshRequestShape() {
        let req = PotaAuth.refreshRequest(refreshToken: "RT-9")
        XCTAssertEqual(req.url, "https://cognito-idp.us-east-2.amazonaws.com/")
        XCTAssertEqual(req.method, "POST")
        XCTAssertEqual(req.headers["Content-Type"], "application/x-amz-json-1.1")
        XCTAssertEqual(req.headers["X-Amz-Target"],
            "AWSCognitoIdentityProviderService.InitiateAuth")
        let body = String(data: req.body, encoding: .utf8) ?? ""
        XCTAssertEqual(
            body,
            "{\"AuthFlow\":\"REFRESH_TOKEN_AUTH\""
                + ",\"ClientId\":\"7hluqct0n2nckib7i7sd5753oa\""
                + ",\"AuthParameters\":{\"REFRESH_TOKEN\":\"RT-9\"}}")
        // Body must be valid JSON with the refresh token nested correctly.
        let obj = try? JSONSerialization.jsonObject(with: req.body) as? [String: Any]
        let params = obj?["AuthParameters"] as? [String: Any]
        XCTAssertEqual(params?["REFRESH_TOKEN"] as? String, "RT-9")
    }

    func testParseRefreshResponse() {
        let ok = #"{"AuthenticationResult":{"IdToken":"NEW.jwt.sig","ExpiresIn":3600}}"#
        XCTAssertEqual(PotaAuth.parseRefreshResponse(Data(ok.utf8)), "NEW.jwt.sig")
        // Revoked refresh token → error shape, no AuthenticationResult.
        let err = #"{"__type":"NotAuthorizedException","message":"Refresh Token has been revoked"}"#
        XCTAssertNil(PotaAuth.parseRefreshResponse(Data(err.utf8)))
        XCTAssertNil(PotaAuth.parseRefreshResponse(nil))
    }

    // MARK: - classifyRedirect (security-critical)

    func testClassifyRedirectExtractsCode() {
        XCTAssertEqual(PotaAuth.classifyRedirect("https://pota.app/?code=abc"),
                       .withCode("abc"))
        // Case-insensitive host is still legit.
        XCTAssertEqual(PotaAuth.classifyRedirect("https://POTA.APP/?code=Xy9"),
                       .withCode("Xy9"))
    }

    func testClassifyRedirectNoCode() {
        XCTAssertEqual(PotaAuth.classifyRedirect("https://pota.app/"), .noCode)
        XCTAssertEqual(PotaAuth.classifyRedirect("https://pota.app/?error=access_denied"),
                       .noCode)
    }

    func testClassifyRedirectRejectsSpoofs() {
        // Lookalike host — the whole point of the parsed-origin check.
        XCTAssertEqual(PotaAuth.classifyRedirect("https://pota.app.evil.com/?code=steal"),
                       .notRedirect)
        // Scheme downgrade.
        XCTAssertEqual(PotaAuth.classifyRedirect("http://pota.app/?code=steal"),
                       .notRedirect)
        // Userinfo trick: real host is evil.com.
        XCTAssertEqual(PotaAuth.classifyRedirect("https://pota.app@evil.com/?code=steal"),
                       .notRedirect)
        // Explicit alternate port.
        XCTAssertEqual(PotaAuth.classifyRedirect("https://pota.app:8443/?code=steal"),
                       .notRedirect)
        // Different host entirely.
        XCTAssertEqual(PotaAuth.classifyRedirect("https://notpota.app/?code=steal"),
                       .notRedirect)
        // Cognito hosted-UI page mid-flow — not the redirect.
        XCTAssertEqual(
            PotaAuth.classifyRedirect(
                "https://parksontheair.auth.us-east-2.amazoncognito.com/oauth2/authorize?x=1"),
            .notRedirect)
    }

    // MARK: - JWT claims

    func testJwtExpiryAndEmail() {
        let exp = 1_800_000_000.0 // fixed epoch seconds
        let token = jwt(payload: "{\"email\":\"ken@example.com\",\"exp\":\(Int(exp))}")
        XCTAssertEqual(PotaAuth.jwtExpiry(token), Date(timeIntervalSince1970: exp))
        XCTAssertEqual(PotaAuth.emailClaim(token), "ken@example.com")
    }

    func testJwtMalformedReturnsNil() {
        XCTAssertNil(PotaAuth.jwtExpiry("not-a-jwt"))
        XCTAssertNil(PotaAuth.jwtExpiry("only.two"))  // payload "two" isn't base64 JSON
        XCTAssertNil(PotaAuth.emailClaim(""))
    }

    func testNeedsRefreshNearExpiry() {
        let exp = Date(timeIntervalSince1970: 1_000_000)
        // 10 min before expiry, 5-min skew → still good.
        XCTAssertFalse(PotaAuth.needsRefresh(
            expiry: exp, now: exp.addingTimeInterval(-600), skew: 300))
        // 2 min before expiry, 5-min skew → refresh.
        XCTAssertTrue(PotaAuth.needsRefresh(
            expiry: exp, now: exp.addingTimeInterval(-120), skew: 300))
        // Already expired → refresh.
        XCTAssertTrue(PotaAuth.needsRefresh(
            expiry: exp, now: exp.addingTimeInterval(60), skew: 300))
        // Unknown expiry → refresh.
        XCTAssertTrue(PotaAuth.needsRefresh(expiry: nil))
    }

    func testCacheExpiryHonoursShorterOfJwtExpAndTtl() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        // JWT exp far in the future → capped at now + 55 min.
        let farToken = jwt(payload: "{\"exp\":\(Int(now.timeIntervalSince1970) + 100_000)}")
        XCTAssertEqual(
            PotaAuth.cacheExpiry(forIdToken: farToken, now: now),
            now.addingTimeInterval(PotaAuth.idTokenTTL))
        // JWT exp sooner than 55 min → use the JWT's own exp.
        let soonEpoch = Int(now.timeIntervalSince1970) + 600
        let soonToken = jwt(payload: "{\"exp\":\(soonEpoch)}")
        XCTAssertEqual(
            PotaAuth.cacheExpiry(forIdToken: soonToken, now: now),
            Date(timeIntervalSince1970: Double(soonEpoch)))
    }
}
