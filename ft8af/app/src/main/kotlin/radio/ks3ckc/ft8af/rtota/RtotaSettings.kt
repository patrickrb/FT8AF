package radio.ks3ckc.ft8af.rtota

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.k1af.ft8af.GeneralVariables

/**
 * Persistent configuration for RTOTA trip mode.
 *
 * Kept in its own Keystore-backed [EncryptedSharedPreferences] file rather than
 * the app's `config` table because the API key is a standing upload credential
 * for the operator's account (same reasoning as [radio.ks3ckc.ft8af.pota.PotaAuth]'s
 * refresh token) — and because the settings-backup export copies the config
 * table verbatim, which would put the key in a shareable file.
 *
 * The in-flight trip lives here too, not just in memory: a phone that reboots or
 * gets its process killed 300 miles into a trip must come back up still attached
 * to the same trip id, with its queued breadcrumbs intact.
 */
object RtotaSettings {
    private const val PREFS = "rtota_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_BASE_URL = "baseUrl"
    private const val KEY_API_KEY = "apiKey"
    private const val KEY_CALLSIGN = "callsign"
    private const val KEY_DEFAULT_PRIVACY = "defaultPrivacy"
    private const val KEY_TRIP_ID = "tripId"
    private const val KEY_TRIP_NAME = "tripName"
    private const val KEY_TRIP_STARTED_MS = "tripStartedMs"
    private const val KEY_TRIP_SHARE_TOKEN = "tripShareToken"
    private const val KEY_TRIP_PRIVACY = "tripPrivacy"
    private const val KEY_TRIP_PENDING_CREATE = "tripPendingCreate"
    private const val KEY_TRIP_PENDING_COMPLETE = "tripPendingComplete"

    /**
     * The hosted service; overridable for self-hosting or a dev box.
     *
     * The `www.` host is deliberate and load-bearing. The apex `rtota.app` answers
     * every request with a 308 to `www.`, and a 308 is precisely the redirect no
     * HTTP client may follow for a POST without being told to (RFC 9110: the
     * method and body must be preserved, so clients decline rather than guess).
     * Every write this app makes is a POST, so pointing at the apex turns trip
     * creation into a permanent, non-retryable failure. See [normalizeRtotaBaseUrl],
     * which repairs an apex URL typed into the server-override field for the same
     * reason.
     */
    const val DEFAULT_BASE_URL = "https://www.rtota.app"

    val PRIVACY_LEVELS = listOf("public", "delayed", "followers", "private")

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: build(ctx.applicationContext).also { cached = it }
        }
    }

    private fun build(ctx: Context): SharedPreferences =
        try {
            createEncrypted(ctx)
        } catch (_: Exception) {
            // A corrupted keyset (app restored onto a device with no matching key)
            // makes create() throw forever. Drop the file and rebuild once — the
            // user re-enters their API key, which beats a permanently broken screen.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ctx.deleteSharedPreferences(PREFS)
            } else {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            }
            createEncrypted(ctx)
        }

    private fun createEncrypted(ctx: Context): SharedPreferences {
        val masterKey =
            MasterKey.Builder(ctx)
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

    /**
     * Every accessor resolves the context lazily through GeneralVariables so the
     * object stays callable from the QSO save path, which has no Context of its
     * own. Before the app finishes starting this returns null and reads fall back
     * to defaults — deliberately, so nothing here can crash a cold start.
     */
    private fun prefsOrNull(): SharedPreferences? = GeneralVariables.getMainContext()?.let { prefs(it) }

    private fun readString(
        key: String,
        fallback: String,
    ): String = prefsOrNull()?.getString(key, fallback) ?: fallback

    private fun writeString(
        key: String,
        value: String?,
    ) {
        prefsOrNull()?.edit()?.apply {
            if (value.isNullOrEmpty()) remove(key) else putString(key, value)
        }?.apply()
    }

    // -- Account ------------------------------------------------------------

    /** Master switch: with this off, trip mode never touches GPS or the network. */
    var enabled: Boolean
        get() = prefsOrNull()?.getBoolean(KEY_ENABLED, false) ?: false
        set(value) {
            prefsOrNull()?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        }

    /**
     * Service origin, normalized so callers can append paths. Normalizing on
     * *read* rather than only on write is what rescues the value an earlier build
     * already persisted — an install that stored the apex `https://rtota.app`
     * keeps working after an update instead of 308-ing forever.
     */
    var baseUrl: String
        get() =
            normalizeRtotaBaseUrl(readString(KEY_BASE_URL, DEFAULT_BASE_URL))
                .ifEmpty { DEFAULT_BASE_URL }
        set(value) = writeString(KEY_BASE_URL, normalizeRtotaBaseUrl(value))

    var apiKey: String
        get() = readString(KEY_API_KEY, "")
        set(value) = writeString(KEY_API_KEY, value.trim())

    /** The registered rover callsign; defaults to the operator's own callsign. */
    var callsign: String
        get() = readString(KEY_CALLSIGN, "").ifEmpty { GeneralVariables.myCallsign.orEmpty() }
        set(value) = writeString(KEY_CALLSIGN, value.trim().uppercase())

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /** Privacy preselected in the start-trip sheet; blank = let the server decide. */
    var defaultPrivacy: String
        get() = readString(KEY_DEFAULT_PRIVACY, "")
        set(value) = writeString(KEY_DEFAULT_PRIVACY, value)

    // -- In-flight trip -----------------------------------------------------

    /** Server trip id, or empty when no trip is running / it hasn't been created yet. */
    var tripId: String
        get() = readString(KEY_TRIP_ID, "")
        set(value) = writeString(KEY_TRIP_ID, value)

    var tripName: String
        get() = readString(KEY_TRIP_NAME, "")
        set(value) = writeString(KEY_TRIP_NAME, value)

    var tripShareToken: String
        get() = readString(KEY_TRIP_SHARE_TOKEN, "")
        set(value) = writeString(KEY_TRIP_SHARE_TOKEN, value)

    var tripPrivacy: String
        get() = readString(KEY_TRIP_PRIVACY, "")
        set(value) = writeString(KEY_TRIP_PRIVACY, value)

    var tripStartedMs: Long
        get() = prefsOrNull()?.getLong(KEY_TRIP_STARTED_MS, 0L) ?: 0L
        set(value) {
            prefsOrNull()?.edit()?.putLong(KEY_TRIP_STARTED_MS, value)?.apply()
        }

    /**
     * True when the user started a trip out of coverage: tracking runs and the
     * queue fills locally, and the flush loop keeps retrying `POST /api/trips`
     * until it lands. Without this, starting a trip in a dead zone (which is
     * where roving starts more often than not) would simply fail.
     */
    var tripPendingCreate: Boolean
        get() = prefsOrNull()?.getBoolean(KEY_TRIP_PENDING_CREATE, false) ?: false
        set(value) {
            prefsOrNull()?.edit()?.putBoolean(KEY_TRIP_PENDING_CREATE, value)?.apply()
        }

    /** Mirror of the above for the end of the trip: complete once we're back online. */
    var tripPendingComplete: Boolean
        get() = prefsOrNull()?.getBoolean(KEY_TRIP_PENDING_COMPLETE, false) ?: false
        set(value) {
            prefsOrNull()?.edit()?.putBoolean(KEY_TRIP_PENDING_COMPLETE, value)?.apply()
        }

    /** True while a trip is running — created on the server or still pending. */
    val hasActiveTrip: Boolean get() = tripId.isNotEmpty() || tripPendingCreate

    fun clearTrip() {
        prefsOrNull()?.edit()
            ?.remove(KEY_TRIP_ID)
            ?.remove(KEY_TRIP_NAME)
            ?.remove(KEY_TRIP_SHARE_TOKEN)
            ?.remove(KEY_TRIP_PRIVACY)
            ?.remove(KEY_TRIP_STARTED_MS)
            ?.remove(KEY_TRIP_PENDING_CREATE)
            ?.remove(KEY_TRIP_PENDING_COMPLETE)
            ?.apply()
    }
}
