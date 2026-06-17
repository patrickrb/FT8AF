package radio.ks3ckc.ft8af.pota

import android.util.Log
import com.k1af.ft8af.GeneralVariables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import radio.ks3ckc.ft8af.pota.model.PotaSpot
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory cache of active POTA spots keyed by uppercased activator callsign.
 * Refreshed every [REFRESH_INTERVAL_MS] while [start] is active.
 *
 * Multiple subscribers can call [start] — internally we ref-count and only the
 * last [stop] cancels the polling job. The decode screen and the POTA hunter
 * tab both subscribe; either keeps the poller alive.
 */
object PotaSpotsRepository {
    private const val TAG = "PotaSpotsRepository"
    private const val REFRESH_INTERVAL_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var subscribers = 0

    private val _spotsByCall = MutableStateFlow<Map<String, PotaSpot>>(emptyMap())
    val spotsByCall: StateFlow<Map<String, PotaSpot>> = _spotsByCall.asStateFlow()

    @Synchronized
    fun start() {
        subscribers++
        if (job != null) return
        log("polling started (subscribers=$subscribers)")
        job = scope.launch {
            while (isActive) {
                val fresh = PotaClient.getActiveSpots(modeFilter = "FT8")
                if (fresh != null) {
                    _spotsByCall.value = fresh.associateBy { it.activator }
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (subscribers > 0) subscribers--
        if (subscribers > 0) return
        log("polling stopped")
        job?.cancel()
        job = null
    }

    /** Lookup helper used by QSO save path + decode-row enrichment. */
    @JvmStatic
    fun parkRefFor(callsign: String?): String? {
        val key = callsign?.uppercase() ?: return null
        return _spotsByCall.value[key]?.reference?.takeIf { it.isNotEmpty() }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            val ctx = GeneralVariables.getMainContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(File(dir, "debug.log"), true).use { it.append("$ts Pota: $msg\n") }
        } catch (_: Exception) {
        }
    }
}
