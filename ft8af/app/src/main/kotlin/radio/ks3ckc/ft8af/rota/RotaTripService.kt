package radio.ks3ckc.ft8af.rota

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R
import java.util.Locale

/**
 * Keeps GPS running for the length of a trip.
 *
 * Android only allows sustained location access from the background to a
 * foreground service that declares the `location` type, so trip tracking cannot
 * live in the activity: the phone is usually in a cradle with the screen off (or
 * showing a map app) while the rover drives. The service exists purely to hold
 * that permission window open, own the [RotaLocationTracker] subscription, and
 * show the ongoing notification the OS requires — the queueing and uploading all
 * stay in [RotaTripManager], which outlives it.
 *
 * The notification doubles as the trip's dashboard: miles, contacts, and how much
 * is still waiting to upload, plus a one-tap End Trip.
 */
class RotaTripService : Service() {
    private var tracker: RotaLocationTracker? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    // Broad catch below: a foreground-start refusal has several unrelated causes
    // (missing permission, background-start policy, an OEM limit) and none of them
    // is worth crashing a trip over.
    @Suppress("TooGenericExceptionCaught")
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_END_TRIP) {
            GeneralVariables.fileLog("RotaTripService: End Trip tapped")
            RotaTripManager.endTrip()
            return START_NOT_STICKY
        }

        val notification = buildNotification(this, RotaTripManager.state.value)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // Location permission missing, or a background-start restriction on
            // Android 12+. Trip mode degrades to "QSOs only" rather than crashing.
            GeneralVariables.fileLog("RotaTripService start failed: $e")
            stopSelf()
            return START_NOT_STICKY
        }

        val t = tracker ?: RotaLocationTracker(this).also { tracker = it }
        if (!t.start()) {
            Log.d(TAG, "tracker did not start (no permission / no provider)")
        }
        running = true
        // STICKY: if the OS reclaims the process mid-trip, restart tracking as
        // soon as memory allows — the trip itself is restored from RotaSettings.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        tracker?.stop()
        tracker = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.rota_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.rota_service_channel_desc)
                setShowBadge(false)
            }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RotaTripService"
        private const val CHANNEL_ID = "rota_trip"
        private const val NOTIF_ID = 0x52544F54 // "RTOT"
        const val ACTION_END_TRIP = "radio.ks3ckc.ft8af.rota.END_TRIP"

        @Volatile
        private var running = false

        @Suppress("TooGenericExceptionCaught")
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RotaTripService::class.java),
                )
            } catch (e: Exception) {
                GeneralVariables.fileLog("RotaTripService.start failed: $e")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, RotaTripService::class.java))
            } catch (_: Exception) {
            }
        }

        /** Refresh the ongoing notification's counters. No-op when not running. */
        fun updateNotification(
            context: Context,
            state: RotaTripState,
        ) {
            if (!running) return
            try {
                val nm =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        ?: return
                nm.notify(NOTIF_ID, buildNotification(context, state))
            } catch (_: Exception) {
            }
        }

        /**
         * Notification text, e.g. `142.3 mi · 37 QSOs · 12 waiting`. The "waiting"
         * clause only appears when there is a backlog, so on a good connection the
         * line stays quiet.
         */
        internal fun notificationText(state: RotaTripState): String {
            val parts =
                mutableListOf(
                    String.format(Locale.US, "%.1f mi", state.miles),
                    "${state.sentQsos + state.pendingQsos} QSOs",
                )
            val waiting = state.pendingPoints + state.pendingQsos
            if (waiting > 0) parts.add("$waiting waiting")
            // Parked is worth saying out loud: the trail deliberately goes quiet,
            // and without this the notification looks like tracking has broken.
            if (state.parked) parts.add("parked")
            if (state.pendingCreate) parts.add("offline start")
            state.lastError?.let { parts.add(it.take(40)) }
            return parts.joinToString(" · ")
        }

        private fun buildNotification(
            context: Context,
            state: RotaTripState,
        ): Notification {
            var piFlags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                piFlags = piFlags or PendingIntent.FLAG_IMMUTABLE
            }
            val openIntent =
                Intent(context, radio.ks3ckc.ft8af.ComposeMainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val openPi = PendingIntent.getActivity(context, 0, openIntent, piFlags)
            val endPi =
                PendingIntent.getService(
                    context,
                    2,
                    Intent(context, RotaTripService::class.java).setAction(ACTION_END_TRIP),
                    piFlags,
                )
            val title = state.tripName.ifBlank { context.getString(R.string.rota_service_title) }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(notificationText(state))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(openPi)
                .addAction(
                    R.drawable.ic_baseline_close_32,
                    context.getString(R.string.rota_action_end_trip),
                    endPi,
                )
                .build()
        }
    }
}
