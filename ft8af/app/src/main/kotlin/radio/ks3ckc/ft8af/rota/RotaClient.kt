package radio.ks3ckc.ft8af.rota

import android.util.Log
import com.k1af.ft8af.GeneralVariables
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Non-2xx from roadsontheair.com, carrying the status so callers can tell retryable from fatal. */
class RotaHttpException(val httpCode: Int, val body: String) :
    Exception("HTTP $httpCode${if (body.isNotBlank()) ": ${body.take(200)}" else ""}") {
    /** The `error` field roadsontheair.com puts in every failure body, when present. */
    val serverMessage: String?
        get() =
            try {
                JSONObject(body).optString("error").takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
}

/**
 * True when [error] is worth retrying rather than surfacing: any connectivity
 * failure (the normal case on the road), a rate-limit, or a 5xx. A 4xx means the
 * request itself is wrong — a bad API key, a trip that isn't ours — and retrying
 * it just burns battery against the same rejection.
 */
fun isRetryableRotaFailure(error: Throwable?): Boolean =
    when (error) {
        is RotaHttpException -> error.httpCode == 429 || error.httpCode >= 500
        is IOException -> true
        else -> false
    }

/** What a failed `POST /api/trips/:id/start` means for the trip in hand. */
enum class PlanStartOutcome {
    /** 409 — the row is no longer `planned`, so it already *is* the running trip. */
    ALREADY_STARTED,

    /** 404 — the announcement was cancelled on the site; create a plain trip instead. */
    PLAN_GONE,

    /** Anything else, including every ordinary network failure. */
    RETRY,
}

/**
 * Classify a failure from starting an announced trip.
 *
 * Both special cases are states a *rover* reaches normally rather than errors: a
 * retry landing twice over a flaky link, and a plan cancelled on the site while
 * the phone had no signal. Treating either as a hard failure would strand a
 * drive that really happened behind a queue that never drains — so neither goes
 * through [isRetryableRotaFailure], which correctly calls a 4xx fatal.
 *
 * [planId] empty means there was no plan to fall back from (a plain create), so
 * the answer is always [PlanStartOutcome.RETRY] and the usual handling applies.
 */
fun classifyPlanStartFailure(
    error: Throwable?,
    planId: String,
): PlanStartOutcome {
    if (planId.isEmpty() || error !is RotaHttpException) return PlanStartOutcome.RETRY
    return when (error.httpCode) {
        409 -> PlanStartOutcome.ALREADY_STARTED
        404 -> PlanStartOutcome.PLAN_GONE
        else -> PlanStartOutcome.RETRY
    }
}

/**
 * Backoff before retry [attempt] (1-based): 30s, 60s, 2m, 4m, … capped at 15
 * minutes. Long by HTTP standards on purpose — the failure this handles is
 * usually "no cell coverage for the next 40 miles", and hammering the radio
 * costs battery on a device that is often navigating at the same time. A
 * regained network triggers an immediate flush anyway (see [RotaTripManager]),
 * so the backoff only governs the pessimistic case.
 */
fun rotaBackoffMs(attempt: Int): Long {
    val capped = attempt.coerceIn(1, 6)
    return minOf(30_000L shl (capped - 1), 15 * 60_000L)
}

/**
 * Talks to the ROTA API (roadsontheair.com, or a self-hosted origin — see
 * [RotaSettings.baseUrl]). Same house style as
 * [radio.ks3ckc.ft8af.pota.PotaClient]: HttpURLConnection only, suspend
 * functions on Dispatchers.IO, every call logged into debug.log.
 *
 * Endpoints used:
 *   POST /api/operators            -> register a callsign, receive an API key
 *   POST /api/trips                -> create a trip; `status: "planned"` announces
 *                                     one ahead of time instead of starting it
 *   POST /api/trips/:id/start      -> an announced trip departing (planned -> active)
 *   POST /api/trips/:id/live       -> append points + QSOs (idempotent for QSOs)
 *   POST /api/trips/:id/complete   -> finalize
 *
 * A trip is one row on the server across its whole life. Announcements used to
 * be a separate resource (`/api/activations`) that a trip was matched to by
 * comparing departure times; that guess is gone, and an announced trip is
 * started by id.
 */
object RotaClient {
    private const val TAG = "RotaClient"
    private const val USER_AGENT = "ft8af-rota/1.0"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * Register a new rover. The server refuses a callsign that already exists
     * (409) rather than echoing its key, so a returning user pastes the key from
     * their roadsontheair.com dashboard instead.
     */
    suspend fun registerOperator(
        baseUrl: String,
        callsign: String,
        name: String? = null,
        homeGrid: String? = null,
        email: String? = null,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val body =
                JSONObject().apply {
                    put("callsign", callsign.trim().uppercase(Locale.US))
                    name?.takeIf { it.isNotBlank() }?.let { put("name", it.trim()) }
                    homeGrid?.takeIf { it.isNotBlank() }?.let { put("homeGrid", it.trim()) }
                    email?.takeIf { it.isNotBlank() }?.let { put("email", it.trim()) }
                }.toString()
            request("POST", "$baseUrl/api/operators", null, body).mapCatching { resp ->
                JSONObject(resp).optString("apiKey").takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("Server returned no apiKey")
            }
        }

    /** Create a trip. Privacy omitted => the operator's default applies. */
    suspend fun createTrip(
        baseUrl: String,
        apiKey: String,
        name: String,
        startTimeMs: Long,
        notes: String? = null,
        privacy: String? = null,
    ): Result<RotaTripHandle> =
        withContext(Dispatchers.IO) {
            val body =
                JSONObject().apply {
                    put("name", name.trim().take(200))
                    put("startTime", isoUtc(startTimeMs))
                    notes?.takeIf { it.isNotBlank() }?.let { put("notes", it.trim().take(2000)) }
                    privacy?.takeIf { it.isNotBlank() }?.let { put("privacy", it) }
                }.toString()
            request("POST", "$baseUrl/api/trips", apiKey, body).mapCatching { resp ->
                val o = JSONObject(resp)
                val id =
                    o.optString("id").takeIf { it.isNotEmpty() }
                        ?: throw IllegalStateException("Server returned no trip id")
                RotaTripHandle(id, o.optString("shareToken").takeIf { it.isNotEmpty() })
            }
        }

    /**
     * Append a batch to a running trip. Safe to retry: QSOs dedupe on
     * `callsign + band + mode + UTC minute`, and breadcrumbs are idempotent on
     * (trip, timestamp) — so a response lost after the server committed costs a
     * repeated request, not a duplicated route. Callers still only drop a batch on
     * a confirmed 2xx: the queue is the sole copy until the server says otherwise.
     */
    suspend fun sendLive(
        baseUrl: String,
        apiKey: String,
        tripId: String,
        batch: RotaBatch,
    ): Result<RotaLiveAck> =
        withContext(Dispatchers.IO) {
            val body = buildLiveBody(batch.points, batch.qsos)
            request("POST", "$baseUrl/api/trips/$tripId/live", apiKey, body).map { resp ->
                parseLiveAck(resp) ?: RotaLiveAck(batch.points.size, batch.qsos.size, 0)
            }
        }

    /**
     * Ask what the server already holds for a trip — the resume handshake.
     *
     * Worth one request after a long silence (a dead zone measured in hours, a
     * process the OS killed, a phone swap): ingestion is idempotent, so the app
     * *could* always just re-send its whole backlog, but it has no way to know it
     * is only missing the last ten minutes. Asking first turns a multi-megabyte
     * re-send over a single bar of signal into a short one.
     */
    suspend fun fetchSyncState(
        baseUrl: String,
        apiKey: String,
        tripId: String,
    ): Result<RotaSyncState> =
        withContext(Dispatchers.IO) {
            request("GET", "$baseUrl/api/trips/$tripId/sync-state", apiKey, null).mapCatching { resp ->
                parseSyncState(resp) ?: throw IllegalStateException("Unparsable sync-state body")
            }
        }

    /**
     * The operator's own announced trips, soonest first.
     *
     * Read from `/api/me` rather than the public `GET /api/trips?status=planned`:
     * the public listing only carries what a stranger may see, and a plan the
     * operator marked `private` or `followers` — the ones most likely to be a
     * real upcoming trip — would be missing from exactly the list they are
     * trying to pick from.
     */
    suspend fun fetchMyPlannedTrips(
        baseUrl: String,
        apiKey: String,
    ): Result<List<RotaPlannedTrip>> =
        withContext(Dispatchers.IO) {
            request("GET", "$baseUrl/api/me", apiKey, null).map { parseMyPlannedTrips(it) }
        }

    suspend fun completeTrip(
        baseUrl: String,
        apiKey: String,
        tripId: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            request("POST", "$baseUrl/api/trips/$tripId/complete", apiKey, "{}").map { }
        }

    /**
     * Announce a trip so followers see it before departure — a `planned` trip,
     * carrying the privacy choices it will keep when it is started.
     */
    suspend fun announceTrip(
        baseUrl: String,
        apiKey: String,
        name: String,
        startTimeMs: Long,
        endTimeMs: Long? = null,
        detail: String? = null,
        bands: List<String> = emptyList(),
        modes: List<String> = emptyList(),
        privacy: String? = null,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val body =
                JSONObject().apply {
                    put("status", "planned")
                    put("name", name.trim().take(200))
                    put("startTime", isoUtc(startTimeMs))
                    endTimeMs?.let { put("endTime", isoUtc(it)) }
                    detail?.takeIf { it.isNotBlank() }?.let { put("detail", it.trim().take(2000)) }
                    if (bands.isNotEmpty()) put("bands", JSONArray(bands.take(20)))
                    if (modes.isNotEmpty()) put("modes", JSONArray(modes.take(20)))
                    privacy?.takeIf { it.isNotBlank() }?.let { put("privacy", it) }
                }.toString()
            request("POST", "$baseUrl/api/trips", apiKey, body).mapCatching { resp ->
                JSONObject(resp).optString("id").takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("Server returned no trip id")
            }
        }

    /**
     * Start an announced trip: `planned` -> `active`, stamping the real departure.
     *
     * This is how a picked plan becomes the trip being driven. Creating a trip
     * instead would leave the announcement sitting at `planned` forever with a
     * duplicate beside it, and would drop the privacy the wizard chose — the
     * plan's settings are already on the row this promotes.
     *
     * The server answers 409 when the trip is no longer `planned`, which for a
     * retry that actually landed is success in disguise; [RotaHttpException.code]
     * lets the caller tell that apart from a real failure.
     */
    suspend fun startPlannedTrip(
        baseUrl: String,
        apiKey: String,
        tripId: String,
        startTimeMs: Long,
    ): Result<RotaTripHandle> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply { put("startTime", isoUtc(startTimeMs)) }.toString()
            request("POST", "$baseUrl/api/trips/$tripId/start", apiKey, body).map { resp ->
                // The id is already known — it is the plan's. Only the share token
                // is news, and an older server that doesn't send one just leaves
                // the share link empty rather than failing the start.
                RotaTripHandle(tripId, JSONObject(resp).optString("shareToken").takeIf { it.isNotEmpty() })
            }
        }

    /**
     * One request. Returns the body on 2xx, a [RotaHttpException] otherwise, or
     * the underlying [IOException] when the network never got there.
     *
     * The catch is broad on purpose: every failure mode here — DNS, TLS, a proxy
     * rewriting the response, a malformed override URL the user typed — must come
     * back as a Result the flush loop can classify, never as a thrown exception
     * that would take out the upload coroutine mid-trip.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun request(
        method: String,
        url: String,
        apiKey: String?,
        body: String?,
    ): Result<String> {
        var conn: HttpURLConnection? = null
        return try {
            conn =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                    apiKey?.takeIf { it.isNotBlank() }?.let {
                        setRequestProperty("Authorization", "Bearer $it")
                    }
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                }
            body?.let { payload ->
                conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err =
                    conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)
                        ?.use { it.readText() }.orEmpty()
                log("$method $url -> http $code ${err.take(160)}")
                return Result.failure(RotaHttpException(code, err))
            }
            val resp =
                conn.inputStream?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }.orEmpty()
            log("$method $url -> $code (${resp.length}B)")
            Result.success(resp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$method $url failed: ${e.javaClass.simpleName}: ${e.message ?: "?"}")
            Result.failure(e)
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
            FileWriter(File(dir, "debug.log"), true).use { it.append("$ts Rota: $msg\n") }
        } catch (_: Exception) {
        }
    }
}
