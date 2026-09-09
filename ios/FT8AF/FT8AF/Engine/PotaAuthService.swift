import FT8Engine
import Foundation

/// App-side driver for POTA's Cognito hosted-UI login. All request shaping and
/// parsing is pure kit logic in `FT8Engine.PotaAuth` (unit-tested); this class
/// just moves bytes with URLSession, keeps the refresh token in the Keychain,
/// and caches the short-lived ID token in memory.
///
/// Mirrors Android `PotaAuth`: the WKWebView captures an authorization `code`,
/// `exchange(code:verifier:)` trades it for tokens (storing the refresh token),
/// and `idToken()` mints a fresh ID token on demand from the stored refresh
/// token via REFRESH_TOKEN_AUTH when the cached one is missing / near expiry.
///
/// Security: only the **refresh token** (and the display email) touch the
/// Keychain. The ID token stays in memory. Tokens are never logged.
@Observable @MainActor
final class PotaAuthService {
    static let shared = PotaAuthService()

    private static let refreshAccount = "refresh_token"
    private static let emailAccount = "email"

    /// The account email from the last login (`email` claim of the ID token),
    /// for display. Loaded from the Keychain at init.
    private(set) var email: String?

    // Cached ID token + its effective expiry. Never persisted.
    private var cachedIdToken: String?
    private var cachedIdTokenExpiry: Date?
    // Serialises concurrent idToken() callers so a burst of uploads triggers at
    // most one refresh round-trip.
    private var refreshTask: Task<String?, Never>?

    private init() {
        email = KeychainStore.get(account: Self.emailAccount)
    }

    /// Whether a refresh token is stored (the user has logged in at least once).
    var isSignedIn: Bool {
        !(KeychainStore.get(account: Self.refreshAccount) ?? "").isEmpty
    }

    /// Sign out: drop the cached ID token and wipe the stored refresh token/email.
    func signOut() {
        cachedIdToken = nil
        cachedIdTokenExpiry = nil
        refreshTask?.cancel()
        refreshTask = nil
        KeychainStore.remove(account: Self.refreshAccount)
        KeychainStore.remove(account: Self.emailAccount)
        email = nil
    }

    /// Exchange a hosted-UI authorization `code` (with the PKCE `verifier` used to
    /// build the authorize URL) for tokens. Stores the refresh token + email and
    /// caches the ID token. Returns true on success.
    func exchange(code: String, verifier: String) async -> Bool {
        let req = PotaAuth.tokenExchangeRequest(code: code, codeVerifier: verifier)
        guard let data = await Self.send(req),
              let tokens = PotaAuth.parseTokenResponse(data)
        else { return false }

        KeychainStore.set(tokens.refreshToken, account: Self.refreshAccount)
        let claimedEmail = PotaAuth.emailClaim(tokens.idToken) ?? ""
        KeychainStore.set(claimedEmail, account: Self.emailAccount)
        email = claimedEmail.isEmpty ? nil : claimedEmail
        cache(idToken: tokens.idToken)
        return true
    }

    /// A valid ID token, refreshed from the stored refresh token when the cached
    /// one is missing or near expiry. Returns nil if the user has never logged in
    /// or the refresh failed (e.g. the refresh token was revoked) — callers
    /// should then prompt for login.
    func idToken() async -> String? {
        if let token = cachedIdToken, !PotaAuth.needsRefresh(expiry: cachedIdTokenExpiry) {
            return token
        }
        // Coalesce concurrent refreshes.
        if let inFlight = refreshTask { return await inFlight.value }
        let task = Task { () -> String? in await self.refreshFromKeychain() }
        refreshTask = task
        let result = await task.value
        refreshTask = nil
        return result
    }

    private func refreshFromKeychain() async -> String? {
        guard let refresh = KeychainStore.get(account: Self.refreshAccount), !refresh.isEmpty
        else { return nil }
        let req = PotaAuth.refreshRequest(refreshToken: refresh)
        guard let data = await Self.send(req),
              let id = PotaAuth.parseRefreshResponse(data)
        else { return nil }
        cache(idToken: id)
        return id
    }

    private func cache(idToken: String) {
        cachedIdToken = idToken
        cachedIdTokenExpiry = PotaAuth.cacheExpiry(forIdToken: idToken)
    }

    /// Send a pre-built kit request. Returns the response body only on a 2xx
    /// (the token/refresh endpoints answer JSON either way, but a non-2xx carries
    /// an error shape our parsers already reject — treating it as nil keeps the
    /// "prompt for login" path simple). nonisolated so the round-trip runs off
    /// the main actor.
    private nonisolated static func send(_ req: PotaAuth.Request) async -> Data? {
        guard let url = URL(string: req.url) else { return nil }
        var r = URLRequest(url: url)
        r.httpMethod = req.method
        r.httpBody = req.body
        r.timeoutInterval = 15
        for (k, v) in req.headers { r.setValue(v, forHTTPHeaderField: k) }
        do {
            let (data, resp) = try await URLSession.shared.data(for: r)
            guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode)
            else { return nil }
            return data
        } catch {
            return nil
        }
    }
}
