package radio.ks3ckc.ft8af.qrz

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.k1af.ft8af.GeneralVariables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Client for the QRZ XML callsign API (xmldata.qrz.com).
 *
 * Spec: https://www.qrz.com/XML/current_spec.html — GET requests with
 * `;`-separated query parameters, e.g.:
 *   https://xmldata.qrz.com/xml/current/?username=USER;password=PASS;agent=APP
 *   https://xmldata.qrz.com/xml/current/?s=SESSION;callsign=W5XO
 *
 * Holds an in-memory session key (re-auths on expiry) and a per-callsign
 * LRU cache. All failures (no creds, bad creds, no XML subscription,
 * network down, callsign not found) surface as null to callers; the
 * most recent diagnostic is stored in `lastError`.
 */
object QrzXmlClient {
    private const val TAG = "QrzXmlClient"
    private const val DEFAULT_BASE_URL = "https://xmldata.qrz.com/xml/current/"
    private const val AGENT = "ft8af-1.0"
    private const val CACHE_MAX = 200

    @VisibleForTesting
    @JvmField
    internal var baseUrl: String = DEFAULT_BASE_URL

    private val sessionMutex = Mutex()
    @Volatile private var sessionKey: String? = null

    private val cache = LruCache<String, QrzLookup>(CACHE_MAX)

    @Volatile var lastError: String? = null
        private set

    data class QrzLookup(val imageUrl: String?)

    /**
     * Test the configured credentials. Returns a human-readable status
     * suitable for surfacing in the Settings UI: "OK" on success,
     * otherwise a short message like "Username/password incorrect".
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val user = GeneralVariables.qrzXmlUsername.orEmpty()
        val pass = GeneralVariables.qrzXmlPassword.orEmpty()
        if (user.isEmpty() || pass.isEmpty()) return@withContext "Missing username or password"

        sessionMutex.withLock { sessionKey = null }
        val key = ensureSession(user, pass)
        if (key != null) "OK" else (lastError ?: "Auth failed")
    }

    /**
     * Clear cached lookups. Call when credentials change so previously
     * failed lookups can be retried with the new auth.
     */
    fun clearCache() {
        // cache.clear() is synchronized inside LruCache, so this is safe to call
        // from the main thread (Settings save) while avatar lookups read the cache
        // on Dispatchers.IO.
        cache.clear()
        lastError = null
    }

    suspend fun lookup(callsign: String): QrzLookup? = withContext(Dispatchers.IO) {
        val key = callsign.trim().uppercase()
        if (key.isEmpty()) return@withContext null

        cache.get(key)?.let { return@withContext it }

        val user = GeneralVariables.qrzXmlUsername.orEmpty()
        val pass = GeneralVariables.qrzXmlPassword.orEmpty()
        if (user.isEmpty() || pass.isEmpty()) {
            lastError = "QRZ XML credentials not configured"
            log("lookup($key) skipped: no creds")
            return@withContext null
        }

        var session = ensureSession(user, pass) ?: return@withContext null
        var xml = fetch("s=$session;callsign=${urlEncode(key)}") ?: return@withContext null

        if (looksLikeInvalidSession(xml)) {
            log("session invalidated, re-authenticating")
            sessionMutex.withLock { sessionKey = null }
            session = ensureSession(user, pass) ?: return@withContext null
            xml = fetch("s=$session;callsign=${urlEncode(key)}") ?: return@withContext null
        }

        val error = parseTag(xml, "Error")
        if (error != null) {
            lastError = error
            log("lookup($key) error: $error")
            // Dump a snippet of the response for diagnosis (truncated to keep PII out).
            log("lookup($key) response excerpt: ${xml.take(400).replace("\n", " ")}")
            // Don't cache error responses — let the user retry after fixing.
            return@withContext null
        }

        val image = parseTag(xml, "image")
        if (image.isNullOrEmpty()) {
            // No <image> tag — log a snippet so we can see what QRZ returned
            // (this is the most common failure mode if the callsign has no
            // profile photo or our parser is missing the tag).
            log("lookup($key) no image; response head: ${xml.take(600).replace("\n", " ")}")
            // Don't cache — try again later in case the callsign uploads a photo.
            return@withContext null
        }

        log("lookup($key) -> $image")
        val result = QrzLookup(imageUrl = image)
        cache.put(key, result)
        result
    }

    private suspend fun ensureSession(user: String, pass: String): String? {
        sessionMutex.withLock {
            sessionKey?.let { return it }
        }
        val query = "username=${urlEncode(user)};password=${urlEncode(pass)};agent=$AGENT"
        val xml = fetch(query) ?: run {
            log("auth: no response (network error)")
            return null
        }
        val key = parseTag(xml, "Key")
        if (key.isNullOrEmpty()) {
            val err = parseTag(xml, "Error") ?: "unknown auth error"
            lastError = err
            log("auth failed: $err")
            return null
        }
        sessionMutex.withLock { sessionKey = key }
        lastError = null
        log("auth ok (session acquired)")
        return key
    }

    private fun looksLikeInvalidSession(xml: String): Boolean {
        val err = parseTag(xml, "Error") ?: return false
        return err.contains("Invalid session", ignoreCase = true) ||
            err.contains("Session Timeout", ignoreCase = true)
    }

    /** GET https://xmldata.qrz.com/xml/current/?<query>. Returns body or null on transport failure. */
    private fun fetch(query: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$baseUrl?$query")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", AGENT)
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                log("http $code from QRZ")
                return null
            }
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            log("transport error: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    @VisibleForTesting
    internal fun parseTag(xml: String, tagName: String): String? {
        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                setInput(xml.reader())
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name.equals(tagName, ignoreCase = true)) {
                    val text = parser.nextText()
                    if (!text.isNullOrEmpty()) return text
                }
                event = parser.next()
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    @VisibleForTesting
    internal fun resetForTests() {
        sessionKey = null
        cache.clear()
        lastError = null
        baseUrl = DEFAULT_BASE_URL
    }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, StandardCharsets.UTF_8.name())

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            val ctx = GeneralVariables.getMainContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(File(dir, "debug.log"), true).use {
                it.append("$ts QrzXml: $msg\n")
            }
        } catch (_: Exception) {
        }
    }
}
