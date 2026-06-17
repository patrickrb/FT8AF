package radio.ks3ckc.ft8af.qrz

import android.util.Log
import com.k1af.ft8af.GeneralVariables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * Scrapes the public QRZ profile page (https://www.qrz.com/db/CALLSIGN)
 * for a callsign's profile photo URL. Works for any QRZ account tier,
 * including free, since the profile page is public.
 *
 * The page is HTML; we look for the OpenGraph `og:image` meta tag first
 * (QRZ sets this consistently for sharing), and fall back to the
 * `<img id="mypic">` pattern used in the page body.
 *
 * Results are cached in-memory (LRU, 200 entries). Failures (callsign
 * not found, no photo, network error) return null but aren't cached so
 * a retry can succeed if QRZ later adds a photo.
 */
object QrzWebClient {
    private const val TAG = "QrzWebClient"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) ft8af/1.0"
    private const val CACHE_MAX = 200

    private val cacheMutex = Mutex()
    private val cache = LinkedHashMap<String, String?>(16, 0.75f, true)

    private val ogImageRegex = Regex(
        """<meta\s+property=["']og:image["']\s+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val mypicRegex = Regex(
        """<img[^>]*id=["']mypic["'][^>]*src=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val mypicRegexReversed = Regex(
        """<img[^>]*src=["']([^"']+)["'][^>]*id=["']mypic["']""",
        RegexOption.IGNORE_CASE,
    )

    fun clearCache() {
        cache.clear()
    }

    /** Returns the profile image URL for [callsign], or null on any failure. */
    suspend fun fetchProfileImage(callsign: String): String? = withContext(Dispatchers.IO) {
        val key = callsign.trim().uppercase()
        if (key.isEmpty()) return@withContext null

        cacheMutex.withLock { cache[key] }?.let { return@withContext it }

        val url = "https://www.qrz.com/db/${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}"
        val html = fetch(url) ?: return@withContext null

        val image = ogImageRegex.find(html)?.groupValues?.getOrNull(1)
            ?: mypicRegex.find(html)?.groupValues?.getOrNull(1)
            ?: mypicRegexReversed.find(html)?.groupValues?.getOrNull(1)

        if (image.isNullOrEmpty()) {
            log("$key: no profile image found in HTML (page may be empty or callsign unlisted)")
            return@withContext null
        }

        // QRZ also serves a default placeholder when no photo is set —
        // detect and skip it so we fall through to the initials avatar.
        val isPlaceholder = image.contains("noimage", ignoreCase = true) ||
            image.contains("default_avatar", ignoreCase = true) ||
            image.endsWith("/qrz_com.svgz", ignoreCase = true)
        if (isPlaceholder) {
            log("$key: og:image is a QRZ placeholder, skipping")
            return@withContext null
        }

        log("$key -> $image")
        cacheMutex.withLock {
            cache[key] = image
            while (cache.size > CACHE_MAX) {
                val oldest = cache.entries.iterator().next()
                cache.remove(oldest.key)
            }
        }
        image
    }

    private fun fetch(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                log("http $code from QRZ for $url")
                return null
            }
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            log("transport error fetching $url: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            val ctx = GeneralVariables.getMainContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(File(dir, "debug.log"), true).use {
                it.append("$ts QrzWeb: $msg\n")
            }
        } catch (_: Exception) {
        }
    }
}
