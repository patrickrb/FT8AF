package radio.ks3ckc.ft8af.pskreporter

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import kotlinx.coroutines.Dispatchers
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
 * Client for the PSK Reporter retrieval API (issue #33).
 *
 * Endpoint: https://retrieve.pskreporter.info/query — returns XML of
 * <receptionReport ... /> elements describing receivers that have decoded a
 * transmitter. Per PSK Reporter's docs: do not query more than once per 5
 * minutes for the same parameter set. The map screen polls every 5 min while
 * the overlay is enabled; this client adds defense-in-depth via:
 *   1. A 4m50s client-side cooldown that short-circuits redundant calls.
 *   2. A 15-minute back-off on HTTP 429/503 (rate-limit signals).
 *
 * Returns null on transport/parse failure or while in cooldown — caller keeps
 * any stale list; empty list means a successful fetch returned zero spots.
 */
data class PskReporterSpot(
    val senderCallsign: String,
    val receiverCallsign: String,
    val receiverGrid: String,
    val receiverLat: Double,
    val receiverLon: Double,
    val frequencyHz: Long,
    val snr: Int,
    val mode: String,
    val flowStartSeconds: Long,
)

object PskReporterClient {
    private const val TAG = "PskReporterClient"
    private const val DEFAULT_BASE_URL = "https://retrieve.pskreporter.info/query"
    private const val AGENT = "ft8af-1.0"
    private const val APP_CONTACT = "ft8af@example.org"
    private const val COOLDOWN_MS = 290_000L
    private const val RATE_LIMIT_COOLDOWN_MS = 15L * 60_000L
    private const val MAX_SPOTS = 500
    private const val IO_TIMEOUT_MS = 8000

    @VisibleForTesting
    @JvmField
    internal var baseUrl: String = DEFAULT_BASE_URL

    @VisibleForTesting
    @JvmField
    internal var clock: () -> Long = { System.currentTimeMillis() }

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var lastFetchEpochMs: Long = 0L
    @Volatile
    private var rateLimitedUntilEpochMs: Long = 0L

    /**
     * Fetch reception reports for [call] over the last [secondsBack] seconds.
     *
     * [force] bypasses only the client-side 5-min cooldown — used for the first
     * fetch when the map overlay is (re)entered so a returning user isn't shown
     * an empty overlay just because a prior visit's fetch was recent. The 429/503
     * rate-limit back-off is always honoured, so a forced call never hammers a
     * server that has asked us to wait.
     */
    suspend fun fetchSpotsForMe(call: String, secondsBack: Int, force: Boolean = false): List<PskReporterSpot>? = withContext(Dispatchers.IO) {
        val now = clock()
        if (now < rateLimitedUntilEpochMs) {
            log("skipped (rate-limit back-off ${(rateLimitedUntilEpochMs - now) / 1000}s remaining)")
            return@withContext null
        }
        if (!force && lastFetchEpochMs != 0L && now - lastFetchEpochMs < COOLDOWN_MS) {
            log("skipped (client cooldown ${(COOLDOWN_MS - (now - lastFetchEpochMs)) / 1000}s remaining)")
            // A cooldown skip is benign (not a failed attempt), so clear any stale
            // error — otherwise a caller surfacing lastError (the map overlay) would
            // keep showing a prior transport/HTTP error during normal throttling.
            // The rate-limit back-off skip above intentionally keeps lastError set.
            lastError = null
            return@withContext null
        }
        lastFetchEpochMs = now

        val callUpper = call.uppercase()
        val url = "$baseUrl?senderCallsign=${urlEncode(callUpper)}" +
            "&flowStartSeconds=-$secondsBack" +
            "&rronly=1" +
            "&mode=${GeneralVariables.currentMode().displayName}" +
            "&appcontact=${urlEncode(APP_CONTACT)}"
        log("fetch start call=$callUpper secondsBack=$secondsBack")

        val body = fetch(url) ?: return@withContext null
        val spots = parseSpots(body)
        lastError = null
        log("fetch ok N=${spots.size}")
        spots
    }

    private fun fetch(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = IO_TIMEOUT_MS
                readTimeout = IO_TIMEOUT_MS
                setRequestProperty("User-Agent", AGENT)
            }
            val code = conn.responseCode
            if (code == 429 || code == 503) {
                rateLimitedUntilEpochMs = clock() + RATE_LIMIT_COOLDOWN_MS
                log("rate-limited (http $code) — backing off ${RATE_LIMIT_COOLDOWN_MS / 60_000}min")
                lastError = "rate-limited ($code)"
                return null
            }
            if (code != HttpURLConnection.HTTP_OK) {
                log("http $code from PSK Reporter")
                lastError = "http $code"
                return null
            }
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            log("transport error: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            lastError = e.message
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseSpots(xml: String): List<PskReporterSpot> {
        val out = mutableListOf<PskReporterSpot>()
        var skippedNoGrid = 0
        var skippedBadGrid = 0
        var skippedBadAttr = 0
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                setInput(xml.reader())
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "receptionReport") {
                    val sender = parser.getAttributeValue(null, "senderCallsign") ?: ""
                    val receiver = parser.getAttributeValue(null, "receiverCallsign") ?: ""
                    val grid = parser.getAttributeValue(null, "receiverLocator") ?: ""
                    val freqStr = parser.getAttributeValue(null, "frequency")
                    val snrStr = parser.getAttributeValue(null, "sNR")
                    val flowStr = parser.getAttributeValue(null, "flowStartSeconds")
                    val mode = parser.getAttributeValue(null, "mode") ?: "FT8"

                    if (sender.isEmpty() || receiver.isEmpty()) {
                        skippedBadAttr++
                    } else if (grid.isEmpty()) {
                        skippedNoGrid++
                    } else {
                        // MaidenheadGrid.gridToLatLng handles 2/4/6-char grids; wrap in try in
                        // case of malformed values that slip past PSK Reporter's validation.
                        val latLng = try { MaidenheadGrid.gridToLatLng(grid) } catch (_: Exception) { null }
                        val freq = freqStr?.toLongOrNull()
                        val snr = snrStr?.toIntOrNull()
                        val flow = flowStr?.toLongOrNull()
                        if (latLng == null || freq == null || snr == null || flow == null) {
                            skippedBadGrid++
                        } else {
                            out.add(
                                PskReporterSpot(
                                    senderCallsign = sender,
                                    receiverCallsign = receiver,
                                    receiverGrid = grid,
                                    receiverLat = latLng.latitude,
                                    receiverLon = latLng.longitude,
                                    frequencyHz = freq,
                                    snr = snr,
                                    mode = mode,
                                    flowStartSeconds = flow,
                                )
                            )
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            log("parse error: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            return emptyList()
        }
        if (skippedNoGrid + skippedBadGrid + skippedBadAttr > 0) {
            log("parse: skipped noGrid=$skippedNoGrid badGrid=$skippedBadGrid badAttr=$skippedBadAttr")
        }
        return if (out.size > MAX_SPOTS) {
            log("parse: capped from ${out.size} to $MAX_SPOTS (keeping most recent)")
            // PSK Reporter returns chronological order — most-recent reports are at the tail.
            out.takeLast(MAX_SPOTS)
        } else {
            out
        }
    }

    @VisibleForTesting
    internal fun resetForTests() {
        lastError = null
        lastFetchEpochMs = 0L
        rateLimitedUntilEpochMs = 0L
        baseUrl = DEFAULT_BASE_URL
        clock = { System.currentTimeMillis() }
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
                it.append("$ts PskReporter: $msg\n")
            }
        } catch (_: Exception) {
        }
    }
}
