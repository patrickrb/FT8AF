package radio.ks3ckc.ft8af.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.database.DatabaseOpr
import com.k1af.ft8af.log.ThirdPartyService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Decides whether an auto-sync run may start. Pure logic (no Android types) so it is
 * unit-testable in isolation — the [QsoAutoSync] wrapper holds the Android wiring.
 *
 * Two guards:
 *  - single-flight: while a run is in flight, no second run may start (avoids two
 *    concurrent [ThirdPartyService.syncAllQSOs] passes hammering the same rows).
 *  - debounce: a run may not start within [minIntervalMs] of the last *started* run,
 *    so a flapping connection (rapid onAvailable/onLost) can't spam the services.
 *
 * Callers pass `now` explicitly (tests inject a fake clock). Methods are synchronized
 * because triggers arrive from both the ConnectivityManager callback thread and the
 * app-start caller.
 */
internal class QsoSyncGate(private val minIntervalMs: Long = 15_000L) {
    private var inFlight = false
    private var lastStartedMs = Long.MIN_VALUE

    /** True when a run is allowed to start right now. Does not mutate state. */
    @Synchronized
    fun shouldRun(now: Long, anyServiceEnabled: Boolean): Boolean {
        if (!anyServiceEnabled) return false
        if (inFlight) return false
        // Long.MIN_VALUE first-run sentinel: `now - MIN_VALUE` overflows, so special-case it.
        if (lastStartedMs != Long.MIN_VALUE && now - lastStartedMs < minIntervalMs) return false
        return true
    }

    /** Mark a run as begun; subsequent [shouldRun] calls are gated until [markFinished]. */
    @Synchronized
    fun markStarted(now: Long) {
        inFlight = true
        lastStartedMs = now
    }

    /** Mark the in-flight run complete, re-opening the single-flight guard. */
    @Synchronized
    fun markFinished() {
        inFlight = false
    }
}

/**
 * Auto-uploads QSOs that failed to upload while offline. When a QSO is logged with no
 * internet, the immediate post-QSO upload fails and the row's `synced_*` flags stay 0;
 * this re-runs the existing catch-up uploader ([ThirdPartyService.syncAllQSOs]) the
 * moment connectivity returns, plus once on app start — so the user never has to press
 * the manual Sync button.
 *
 * Thin Android wrapper: all the start/skip decisions live in the testable [QsoSyncGate].
 * Scoped to QRZ + Cloudlog (the services with per-QSO sync flags). POTA is excluded.
 */
class QsoAutoSync(private val appContext: Context) {

    private val gate = QsoSyncGate()
    private val cm =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            requestSync("network-available")
        }
    }

    private var registered = false

    /** Begin listening for connectivity so a returning network triggers a flush. */
    fun register() {
        val manager = cm ?: return
        if (registered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: Exception) {
            log("register failed: ${e.javaClass.simpleName}")
        }
    }

    /** Stop listening. Safe to call when not registered. */
    fun unregister() {
        val manager = cm ?: return
        if (!registered) return
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Already unregistered / never succeeded — ignore.
        }
        registered = false
    }

    /** Public entry for the app-start flush (and any other explicit trigger). */
    fun syncNow(reason: String) = requestSync(reason)

    /**
     * Run a catch-up upload if the gate allows it and there is actually something
     * pending. Off-loads the blocking upload to a background thread (mirrors the plain
     * Thread used by the immediate post-QSO path in MainViewModel).
     */
    private fun requestSync(reason: String) {
        val now = System.currentTimeMillis()
        val anyEnabled = GeneralVariables.enableCloudlog || GeneralVariables.enableQRZ
        if (!gate.shouldRun(now, anyEnabled)) {
            log("skip ($reason): enabled=$anyEnabled")
            return
        }
        gate.markStarted(now)
        Thread {
            try {
                val db = DatabaseOpr.getInstance(null, null)?.db
                if (db == null) {
                    log("skip ($reason): no db")
                    return@Thread
                }
                val pending = ThirdPartyService.countUnsyncedQSOs(db)
                if (pending <= 0) {
                    log("nothing pending ($reason)")
                    return@Thread
                }
                log("start ($reason): $pending pending")
                val result = ThirdPartyService.syncAllQSOs(db, null)
                log("done ($reason): cloudlog=${result.cloudlogOk} qrz=${result.qrzOk} of ${result.total}")
            } catch (e: Exception) {
                log("error ($reason): ${e.javaClass.simpleName} ${e.message}")
            } finally {
                gate.markFinished()
            }
        }.start()
    }

    /** Append a line to the shared debug.log (same file ComposeMainActivity writes). */
    private fun log(msg: String) {
        try {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val dir = appContext.getExternalFilesDir(null) ?: return
            File(dir, "debug.log").appendText("$ts QsoAutoSync: $msg\n")
        } catch (_: Exception) {
        }
    }
}
