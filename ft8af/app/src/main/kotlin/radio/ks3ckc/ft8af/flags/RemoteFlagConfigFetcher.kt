package radio.ks3ckc.ft8af.flags

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Re-attempt spacing while a fresh cache exists (or after a failed attempt). */
internal const val REMOTE_FLAG_MIN_ATTEMPT_INTERVAL_MS = 6L * 60 * 60 * 1000

/**
 * Whether a fetch attempt is worthwhile now. Pure so tests can drive it:
 * fetch when there is no valid cache or the cache has expired — but never
 * more often than [REMOTE_FLAG_MIN_ATTEMPT_INTERVAL_MS] between attempts, so
 * an unreachable/broken endpoint is not hammered on every app start.
 */
internal fun shouldFetchRemoteFlagConfig(
    cached: RemoteFlagConfig?,
    lastAttemptMs: Long,
    nowMs: Long,
): Boolean {
    if (lastAttemptMs != 0L && nowMs - lastAttemptMs < REMOTE_FLAG_MIN_ATTEMPT_INTERVAL_MS) {
        return false
    }
    return cached == null || !cached.isFresh(nowMs)
}

/**
 * Fetches the versioned feature-configuration document from the ft8af.app
 * backend and caches it when valid. Fire-and-forget: every failure mode
 * (offline, HTTP error, invalid payload) leaves the existing cache untouched
 * and simply records the attempt. Nothing here may ever block startup or
 * surface an error into FT8 operation — callers launch it on a background
 * coroutine and ignore the result.
 */
class RemoteFlagConfigFetcher(
    private val store: RemoteFlagConfigStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val baseUrl: String = DEFAULT_URL,
) {
    suspend fun refreshIfDue() = withContext(Dispatchers.IO) {
        val now = clock()
        if (!shouldFetchRemoteFlagConfig(store.cached(), store.lastAttemptMs(), now)) return@withContext
        store.recordAttempt(now)
        val body = fetch() ?: return@withContext
        if (parseRemoteFlagConfig(body) == null) {
            Log.w(TAG, "rejected invalid feature-config payload")
            return@withContext
        }
        store.save(body, now)
        Log.d(TAG, "cached remote feature config")
    }

    private fun fetch(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = IO_TIMEOUT_MS
                readTimeout = IO_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "feature-config http ${conn.responseCode}")
                null
            } else {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
        } catch (e: Exception) {
            Log.d(TAG, "feature-config fetch failed: ${e.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "RemoteFlagConfig"
        private const val IO_TIMEOUT_MS = 8000

        @VisibleForTesting
        internal const val DEFAULT_URL = "https://ft8af.app/api/feature-config"
    }
}
