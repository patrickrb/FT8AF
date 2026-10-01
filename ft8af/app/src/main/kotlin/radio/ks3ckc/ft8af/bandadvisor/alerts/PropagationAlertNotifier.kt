package radio.ks3ckc.ft8af.bandadvisor.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.k1af.ft8af.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Posts propagation-alert notifications on their own channel (so users can
 * silence them independently of DX/QSO alerts). Tap opens the app and asks it
 * to show the Band Advisor detail for the opening. Mirrors DxAlertNotifier's
 * permission handling: check POST_NOTIFICATIONS first, catch the revocation
 * race around notify().
 */
object PropagationAlertNotifier {
    const val CHANNEL_ID = "propagation_alerts"

    /** Intent extra asking ComposeMainActivity to open the advisor detail. */
    const val EXTRA_OPEN_ADVISOR = "open_band_advisor"

    /**
     * Posted (with a timestamp) when a propagation-alert notification is
     * tapped; FT8AFApp observes it and opens the Band Advisor detail sheet.
     */
    val openAdvisorRequest = androidx.lifecycle.MutableLiveData<Long>()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.prop_alerts_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.prop_alerts_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Post one alert. Returns true when the notification was actually shown. */
    fun post(context: Context, decision: AlertDecision, grid: String): Boolean {
        if (!canPost(context)) return false
        ensureChannel(context)

        val title = context.getString(R.string.prop_alerts_notif_opening_title, decision.band)
        val regionText = decision.region ?: context.getString(R.string.band_advisor_goal_dx)
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(decision.conditionsAtMs))
        val body = context.getString(
            R.string.prop_alerts_notif_opening_text,
            grid,
            regionText,
            decision.band,
        ) + " ($time UTC)"

        val intent = Intent(context, radio.ks3ckc.ft8af.ComposeMainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            putExtra(EXTRA_OPEN_ADVISOR, true)
        }
        var piFlags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            piFlags = piFlags or PendingIntent.FLAG_IMMUTABLE
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            decision.identity.hashCode(),
            intent,
            piFlags,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context)
                .notify(decision.identity.hashCode(), notification)
            true
        } catch (_: SecurityException) {
            // Permission revoked between check and notify — nothing was posted.
            false
        }
    }
}
