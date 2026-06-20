package com.k1af.ft8af.alert;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Needed-DX alerts: plays a sound + vibrates and posts a notification when a station
 * calling CQ is a NEW (unworked) one in a user-enabled category — a new DXCC entity
 * ({@link GeneralVariables#alertNewDxcc}) or a new US state
 * ({@link GeneralVariables#alertNewState}).
 *
 * <p>Driven from {@code MainViewModel.GetQTHRunnable.run()} after
 * {@code CallsignDatabase.getMessagesLocation} has populated the {@code from*} flags,
 * so this just reads {@link Ft8Message#fromDxcc} / {@link Ft8Message#fromNewState}.
 *
 * <p>Sound + vibration are handled by a high-importance notification channel, so they
 * respect Do-Not-Disturb and the user's per-channel system settings. Alerts are
 * de-duplicated per (category + identifier) for the lifetime of the process, so a
 * station calling CQ every cycle — or several stations from the same needed entity —
 * only alert once.
 */
public class DxAlertNotifier {
    private static final String CHANNEL_ID = "dx_alerts";
    public static final String EXTRA_CALLSIGN = "alert_callsign";
    public static final String EXTRA_BAND = "alert_band";

    private final Context appContext;
    // Already-alerted keys this session: "DXCC:<country>" / "STATE:<code>".
    private final Set<String> alerted = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public DxAlertNotifier(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        createChannel();
    }

    private void createChannel() {
        if (appContext == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager)
                appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Needed-DX alerts", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("New DXCC / US state stations calling CQ");
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 250, 150, 250});
        Uri sound = android.media.RingtoneManager
                .getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
        if (sound != null) {
            channel.setSound(sound, new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
        }
        nm.createNotificationChannel(channel);
    }

    /**
     * Inspect a freshly-decoded batch and fire alerts for newly-needed CQ stations.
     */
    public void processDecodes(List<Ft8Message> messages) {
        if (appContext == null || messages == null) return;
        if (!GeneralVariables.alertNewDxcc && !GeneralVariables.alertNewState) return;

        for (Ft8Message msg : messages) {
            if (msg == null || !msg.checkIsCQ()) continue;          // workable stations only
            if (GeneralVariables.checkIsBlockedMessage(msg)) continue;

            if (GeneralVariables.alertNewDxcc && msg.fromDxcc) {
                String country = (msg.fromWhere == null || msg.fromWhere.isEmpty())
                        ? msg.getCallsignFrom() : msg.fromWhere;
                fireOnce("DXCC:" + country, "New DXCC: " + country, msg);
            }
            if (GeneralVariables.alertNewState && msg.fromNewState && msg.fromState != null) {
                fireOnce("STATE:" + msg.fromState, "New state: " + msg.fromState, msg);
            }
        }
    }

    private void fireOnce(String dedupKey, String title, Ft8Message msg) {
        if (!alerted.add(dedupKey)) return;                         // already alerted this session

        StringBuilder body = new StringBuilder(msg.getCallsignFrom());
        if (msg.maidenGrid != null && !msg.maidenGrid.isEmpty()) {
            body.append("  ").append(msg.maidenGrid);
        }
        body.append("  ").append(msg.snr).append(" dB");

        GeneralVariables.fileLog("DX_ALERT fire key=[" + dedupKey + "] "
                + title + " call=" + msg.getCallsignFrom());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(appContext,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;                                                 // no permission → skip silently
        }

        int id = dedupKey.hashCode();

        Intent intent = new Intent(appContext,
                radio.ks3ckc.ft8af.ComposeMainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_CALLSIGN, msg.getCallsignFrom());
        intent.putExtra(EXTRA_BAND, msg.band);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(appContext, id, intent, piFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body.toString())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setContentIntent(pi);

        try {
            NotificationManagerCompat.from(appContext).notify(id, builder.build());
        } catch (SecurityException ignored) {
            // Permission revoked between the check and notify(); ignore.
        }
    }
}
