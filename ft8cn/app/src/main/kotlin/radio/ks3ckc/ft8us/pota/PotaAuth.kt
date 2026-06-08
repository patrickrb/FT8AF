package radio.ks3ckc.ft8us.pota

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserPool
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserSession
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationDetails
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.ChallengeContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.MultiFactorAuthenticationContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.AuthenticationHandler
import com.amazonaws.regions.Regions
import com.bg7yoz.ft8cn.GeneralVariables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Authenticates against pota.app's AWS Cognito user pool so the app can upload
 * activation logs directly (see [PotaClient.uploadAdif]).
 *
 * POTA's pool/client IDs are public — they ship in pota.app's own JS bundle and
 * are reproduced in the open-source `pota-adif-upload` Rust client.
 *
 * Two ways in, both ending in a stored refresh token the rest of the class treats
 * identically:
 *  - [login] (email + password) runs Cognito's USER_SRP_AUTH (password never
 *    leaves the SRP proof) via the AWS SDK. Only works for accounts that have a
 *    Cognito password.
 *  - [authorizeUrl] + [exchangeCode] drive the hosted-UI OAuth2 code+PKCE flow
 *    (see [radio.ks3ckc.ft8us.ui.pota.PotaOAuthDialog]). This is the only path
 *    that works for **federated** accounts (Google / Facebook / Login-with-Amazon),
 *    which have no Cognito password for SRP to verify.
 *
 * Either way:
 *  - [idToken] returns a short-lived JWT ID token, refreshing it from the stored
 *    refresh token via REFRESH_TOKEN_AUTH (a plain JSON POST — no SRP, no SDK)
 *    when the cached one is missing or near expiry.
 *
 * Storage note: the refresh token is a long-lived credential (effectively
 * standing account access), so it's persisted in an Android-Keystore-backed
 * [EncryptedSharedPreferences] file rather than plaintext — keeping it out of
 * reach of adb backup, filesystem extraction, and other apps.
 */
object PotaAuth {
    private const val TAG = "PotaAuth"

    // Public POTA Cognito identifiers (from pota.app's JS / the pota-adif-upload crate).
    private const val POOL_ID = "us-east-2_nA5jZ0klh"
    private const val CLIENT_ID = "7hluqct0n2nckib7i7sd5753oa"
    private val REGION = Regions.US_EAST_2
    private const val COGNITO_IDP = "https://cognito-idp.us-east-2.amazonaws.com/"

    // Hosted-UI (managed login) origin for the OAuth2 code flow used by federated
    // sign-in. From the pool's /.well-known/openid-configuration + pota.app's JS.
    private const val HOSTED_UI = "https://parksontheair.auth.us-east-2.amazoncognito.com"
    private const val OAUTH_SCOPE = "openid email phone profile"

    /**
     * The single redirect URI POTA registered on its Cognito app client. We can't
     * register our own (no custom scheme / localhost is accepted — every other
     * value returns redirect_mismatch), so the WebView flow watches for navigation
     * to this URL and lifts the `?code=` out before pota.app actually loads.
     */
    const val OAUTH_REDIRECT = "https://pota.app/"

    private const val PREFS = "pota_auth"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EMAIL = "email"

    // Refresh ~5 min before the ID token's nominal 60-min lifetime expires.
    private const val ID_TOKEN_TTL_MS = 55 * 60 * 1000L

    @Volatile private var cachedIdToken: String? = null
    @Volatile private var cachedIdTokenExpiryMs: Long = 0L

    // Lazily-built, cached encrypted store. EncryptedSharedPreferences.create is
    // relatively expensive (Keystore + Tink init), so reuse one instance.
    @Volatile private var encPrefs: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences {
        encPrefs?.let { return it }
        return synchronized(this) {
            encPrefs ?: buildPrefs(ctx.applicationContext).also { encPrefs = it }
        }
    }

    private fun buildPrefs(ctx: Context): SharedPreferences {
        return try {
            createEncryptedPrefs(ctx)
        } catch (e: Exception) {
            // Keystore can fail on a corrupted keyset (e.g. after an app restore to a
            // device with no matching key). Drop the unreadable file and rebuild it
            // once; the user simply has to sign in again.
            log("EncryptedSharedPreferences init failed, recreating: ${e.javaClass.simpleName}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ctx.deleteSharedPreferences(PREFS)
            } else {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            }
            createEncryptedPrefs(ctx)
        }
    }

    private fun createEncryptedPrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Whether a refresh token is stored (i.e. the user has logged in at least once). */
    fun isLoggedIn(): Boolean {
        val ctx = GeneralVariables.getMainContext() ?: return false
        return !prefs(ctx).getString(KEY_REFRESH, null).isNullOrBlank()
    }

    /** Email the user last logged in with, for display. */
    fun loggedInEmail(): String? =
        GeneralVariables.getMainContext()?.let { prefs(it).getString(KEY_EMAIL, null) }

    fun logout() {
        cachedIdToken = null
        cachedIdTokenExpiryMs = 0L
        GeneralVariables.getMainContext()?.let { prefs(it).edit().clear().apply() }
        log("logout — cleared stored token")
    }

    /**
     * Log in with a pota.app account. On success the refresh token is persisted and
     * a usable ID token is cached. Returns the ID token on success.
     */
    suspend fun login(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        val ctx = GeneralVariables.getMainContext()
            ?: return@withContext Result.failure(IllegalStateException("no app context"))
        try {
            val session = srpLogin(ctx, email.trim(), password)
            val refresh = session.refreshToken?.token
            if (refresh.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("no refresh token in session"))
            }
            prefs(ctx).edit()
                .putString(KEY_REFRESH, refresh)
                .putString(KEY_EMAIL, email.trim())
                .apply()
            val id = session.idToken?.jwtToken
                ?: return@withContext Result.failure(IllegalStateException("no id token in session"))
            cachedIdToken = id
            cachedIdTokenExpiryMs = System.currentTimeMillis() + ID_TOKEN_TTL_MS
            log("login ok email=${email.trim()} (refresh stored)")
            Result.success(id)
        } catch (e: Exception) {
            log("login FAILED: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            Result.failure(e)
        }
    }

    /**
     * A valid ID token, refreshed from the stored refresh token when needed.
     * Returns null if the user has never logged in or the refresh failed (e.g.
     * the refresh token was revoked) — callers should then prompt for login.
     */
    suspend fun idToken(): String? = withContext(Dispatchers.IO) {
        cachedIdToken?.let {
            if (System.currentTimeMillis() < cachedIdTokenExpiryMs) return@withContext it
        }
        val ctx = GeneralVariables.getMainContext() ?: return@withContext null
        val refresh = prefs(ctx).getString(KEY_REFRESH, null)
        if (refresh.isNullOrBlank()) return@withContext null
        try {
            val id = refreshIdToken(refresh)
            cachedIdToken = id
            cachedIdTokenExpiryMs = System.currentTimeMillis() + ID_TOKEN_TTL_MS
            log("idToken refreshed")
            id
        } catch (e: Exception) {
            log("idToken refresh FAILED: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            null
        }
    }

    // --- Cognito SRP login via the AWS SDK (callback API wrapped as a coroutine) ---

    private suspend fun srpLogin(ctx: Context, email: String, password: String): CognitoUserSession =
        suspendCancellableCoroutine { cont ->
            val pool = CognitoUserPool(ctx, POOL_ID, CLIENT_ID, null, REGION)
            val handler = object : AuthenticationHandler {
                override fun onSuccess(userSession: CognitoUserSession, newDevice: com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoDevice?) {
                    if (cont.isActive) cont.resume(userSession)
                }

                override fun getAuthenticationDetails(continuation: AuthenticationContinuation, userId: String?) {
                    continuation.setAuthenticationDetails(AuthenticationDetails(email, password, null))
                    continuation.continueTask()
                }

                override fun getMFACode(continuation: MultiFactorAuthenticationContinuation) {
                    // POTA accounts don't use MFA; surface it as an error rather than hang.
                    if (cont.isActive) cont.resumeWith(Result.failure(UnsupportedOperationException("MFA not supported")))
                }

                override fun authenticationChallenge(continuation: ChallengeContinuation) {
                    if (cont.isActive) cont.resumeWith(Result.failure(UnsupportedOperationException("auth challenge not supported")))
                }

                override fun onFailure(exception: Exception) {
                    if (cont.isActive) cont.resumeWith(Result.failure(exception))
                }
            }
            // getSession runs the SRP handshake on the calling thread (already Dispatchers.IO).
            pool.getUser(email).getSession(handler)
        }

    // --- REFRESH_TOKEN_AUTH: trade the refresh token for a fresh ID token (no SRP) ---

    private fun refreshIdToken(refreshToken: String): String {
        val body = JSONObject().apply {
            put("AuthFlow", "REFRESH_TOKEN_AUTH")
            put("ClientId", CLIENT_ID)
            put("AuthParameters", JSONObject().put("REFRESH_TOKEN", refreshToken))
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(COGNITO_IDP).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-amz-json-1.1")
                setRequestProperty("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
            }
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                throw IllegalStateException("InitiateAuth http $code: ${err.take(200)}")
            }
            val resp = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            return JSONObject(resp)
                .getJSONObject("AuthenticationResult")
                .getString("IdToken")
        } finally {
            conn?.disconnect()
        }
    }

    // --- Hosted-UI OAuth2 (authorization code + PKCE) for federated sign-in ---

    /** A PKCE verifier/challenge pair for one hosted-UI login attempt. */
    data class Pkce(val verifier: String, val challenge: String)

    /** Mint a fresh PKCE pair (S256). Hold onto it for the matching [exchangeCode]. */
    fun newPkce(): Pkce {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = b64url(raw)
        val challenge = b64url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        )
        return Pkce(verifier, challenge)
    }

    /** The hosted-UI authorize URL to load in the login WebView for [pkce]. */
    fun authorizeUrl(pkce: Pkce): String {
        val q = buildString {
            append("client_id=").append(CLIENT_ID)
            append("&response_type=code")
            append("&scope=").append(URLEncoder.encode(OAUTH_SCOPE, "UTF-8"))
            append("&redirect_uri=").append(URLEncoder.encode(OAUTH_REDIRECT, "UTF-8"))
            append("&code_challenge=").append(pkce.challenge)
            append("&code_challenge_method=S256")
        }
        return "$HOSTED_UI/oauth2/authorize?$q"
    }

    /**
     * Exchange a hosted-UI authorization [code] for tokens, store the refresh token
     * (so later uploads mint ID tokens silently via [idToken]), and return the ID
     * token. [verifier] must be the one from the [Pkce] used to build [authorizeUrl].
     */
    suspend fun exchangeCode(code: String, verifier: String): Result<String> = withContext(Dispatchers.IO) {
        val ctx = GeneralVariables.getMainContext()
            ?: return@withContext Result.failure(IllegalStateException("no app context"))
        try {
            val form = buildString {
                append("grant_type=authorization_code")
                append("&client_id=").append(CLIENT_ID)
                append("&code=").append(URLEncoder.encode(code, "UTF-8"))
                append("&redirect_uri=").append(URLEncoder.encode(OAUTH_REDIRECT, "UTF-8"))
                append("&code_verifier=").append(verifier)
            }
            val obj = JSONObject(postForm("$HOSTED_UI/oauth2/token", form))
            val refresh = obj.optString("refresh_token", "")
            val id = obj.optString("id_token", "")
            if (refresh.isBlank() || id.isBlank()) {
                return@withContext Result.failure(IllegalStateException("token response missing tokens"))
            }
            val email = emailFromIdToken(id).orEmpty()
            prefs(ctx).edit()
                .putString(KEY_REFRESH, refresh)
                .putString(KEY_EMAIL, email)
                .apply()
            cachedIdToken = id
            cachedIdTokenExpiryMs = System.currentTimeMillis() + ID_TOKEN_TTL_MS
            log("oauth login ok email=$email (refresh stored)")
            Result.success(id)
        } catch (e: Exception) {
            log("oauth exchange FAILED: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            Result.failure(e)
        }
    }

    private fun postForm(urlStr: String, form: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(form.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                throw IllegalStateException("token http $code: ${err.take(200)}")
            }
            return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }

    /** Pull the `email` claim out of a JWT ID token (for display only). */
    private fun emailFromIdToken(idToken: String): String? = try {
        val parts = idToken.split(".")
        if (parts.size < 2) null
        else {
            val payload = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                StandardCharsets.UTF_8,
            )
            JSONObject(payload).optString("email").ifBlank { null }
        }
    } catch (_: Exception) {
        null
    }

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            val ctx = GeneralVariables.getMainContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(File(dir, "debug.log"), true).use { it.append("$ts PotaAuth: $msg\n") }
        } catch (_: Exception) {
        }
    }
}
