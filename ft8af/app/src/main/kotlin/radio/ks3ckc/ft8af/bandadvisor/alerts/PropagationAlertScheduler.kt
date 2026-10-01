package radio.ks3ckc.ft8af.bandadvisor.alerts

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8af.bandadvisor.model.normalizeAdvisorGrid
import radio.ks3ckc.ft8af.flags.FeatureFlag
import radio.ks3ckc.ft8af.flags.FeatureFlags
import radio.ks3ckc.ft8af.ui.bandadvisor.BandAdvisorTelemetry
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the periodic propagation-alert check. The single
 * decision — should work exist right now? — is the pure [shouldSchedule];
 * [sync] applies it. Called from app start and from every settings/flag
 * change, so disabling the flag or the preference always cancels pending work.
 */
object PropagationAlertScheduler {
    internal const val WORK_NAME = "band_advisor_prop_alerts"

    /** WorkManager's floor for periodic work is 15 min; 30 keeps wakeups modest. */
    internal const val INTERVAL_MINUTES = 30L

    /** Pure: alerts run only when the flag AND the user preference agree. */
    fun shouldSchedule(flagEnabled: Boolean, prefs: AlertPrefs): Boolean =
        flagEnabled && prefs.enabled && prefs.enabledTypes.isNotEmpty()

    /** Reconcile scheduled work with the current flag + preferences. */
    fun sync(context: Context) {
        val enabled = shouldSchedule(
            FeatureFlags.isEnabled(FeatureFlag.PROPAGATION_ALERTS),
            AlertPreferencesStore.load(context),
        )
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<PropagationAlertWorker>(
            INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        // KEEP: re-syncing must not reset the period timer on every app start.
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }
}

/**
 * The periodic check: re-verify the gates (they may have flipped since
 * scheduling), fetch pre-aggregated conditions from the backend, run the pure
 * alert policy, notify, and record deliveries for dedup. Always returns
 * success — a failed round is just a skipped round; alerts must never
 * escalate into retry storms or interfere with the app.
 */
class PropagationAlertWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        // Gates may have changed since scheduling; a disabled state also
        // un-schedules so this worker stops running entirely.
        val prefs = AlertPreferencesStore.load(context)
        if (!PropagationAlertScheduler.shouldSchedule(
                FeatureFlags.isEnabled(FeatureFlag.PROPAGATION_ALERTS), prefs,
            )
        ) {
            PropagationAlertScheduler.sync(context)
            return Result.success()
        }
        if (!PropagationAlertNotifier.canPost(context)) return Result.success()

        val grid =
            withContext(Dispatchers.IO) { loadAlertGrid(context) } ?: return Result.success()

        val nowMs = System.currentTimeMillis()
        val conditions = ConditionsClient.fetch(grid, nowMs = nowMs) ?: return Result.success()

        val decisions = evaluateAlerts(
            grid = grid,
            conditions = conditions,
            prefs = prefs,
            deliveredAt = AlertHistoryStore.deliveredAt(context),
            nowMs = nowMs,
            localHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        )
        for (decision in decisions) {
            if (PropagationAlertNotifier.post(context, decision, grid)) {
                AlertHistoryStore.recordDelivery(context, decision.identity, nowMs)
                BandAdvisorTelemetry.event(
                    "alert_delivered",
                    "${decision.type} ${decision.band} ${decision.region ?: ""}",
                )
            }
        }
        return Result.success()
    }
}

/** Read persisted configuration without starting the Activity or FT8 engine. */
internal fun loadAlertGrid(context: Context): String? =
    runCatching {
        val path = context.getDatabasePath("data.db")
        if (!path.isFile) return@runCatching null
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT Value FROM config WHERE KeyName = ? COLLATE NOCASE", arrayOf("grid")).use { cursor ->
                if (cursor.moveToFirst()) normalizeAdvisorGrid(cursor.getString(0)) else null
            }
        }
    }.getOrNull()
