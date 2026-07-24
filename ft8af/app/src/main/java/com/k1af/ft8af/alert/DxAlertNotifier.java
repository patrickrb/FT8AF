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
import com.k1af.ft8af.log.QSLRecord;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sound + vibrate + notification alerts. Two independent channels:
 *
 * <ul>
 *   <li>{@code dx_alerts} — a station calling CQ that is a NEW (unworked) entity in a
 *       user-enabled category: a new DXCC ({@link GeneralVariables#alertNewDxcc}) or US
 *       state ({@link GeneralVariables#alertNewState}); and any decode from a station on
 *       the user's callsign watchlist ({@link GeneralVariables#checkIsWatchedCallsign},
 *       not CQ-gated). Driven from
 *       {@code MainViewModel.GetQTHRunnable.run()} once the {@code from*} flags are set.</li>
 *   <li>{@code qso_alerts} — someone calling YOU
 *       ({@link GeneralVariables#alertOnCqReply}, also driven from {@code processDecodes})
 *       and a completed QSO ({@link GeneralVariables#alertOnQsoComplete}, fired from
 *       {@code MainViewModel.doAfterTransmit} via {@link #notifyQsoComplete}).</li>
 * </ul>
 *
 * <p>Each channel is high-importance so sound + vibration respect Do-Not-Disturb and the
 * user's per-channel system settings. Alerts are de-duplicated per key (see
 * {@link AlertDecisions}) for the lifetime of the process so a station calling every cycle
 * only alerts once.
 */
public class DxAlertNotifier {
    private static final String CHANNEL_ID = "dx_alerts";
    private static final String QSO_CHANNEL_ID = "qso_alerts";
    public static final String EXTRA_CALLSIGN = "alert_callsign";
    public static final String EXTRA_BAND = "alert_band";

    private final Context appContext;
    // Already-alerted keys this session (namespaced: "DXCC:"/"STATE:"/"CQREPLY:"/"QSO:").
    private final Set<String> alerted = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public DxAlertNotifier(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        createChannels();
    }

    private void createChannels() {
        if (appContext == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager)
                appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        createHighChannel(nm, CHANNEL_ID, "Needed-DX alerts",
                "New DXCC / US state stations calling CQ, and watchlist callsigns");
        createHighChannel(nm, QSO_CHANNEL_ID, "QSO & CQ alerts",
                "Someone calling you, and completed-QSO alerts");
    }

    private void createHighChannel(NotificationManager nm, String id, String name, String desc) {
        if (nm.getNotificationChannel(id) != null) return;
        NotificationChannel channel = new NotificationChannel(
                id, name, NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(desc);
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
     * Inspect a freshly-decoded batch and fire alerts: newly-needed CQ stations
     * (DXCC/state) and — when enabled — any message addressed to the user's callsign.
     */
    public void processDecodes(List<Ft8Message> messages) {
        if (appContext == null || messages == null) return;
        if (!GeneralVariables.alertNewDxcc && !GeneralVariables.alertNewState
                && !GeneralVariables.alertOnCqReply && !GeneralVariables.hasWatchCallsigns()) {
            return;
        }

        for (Ft8Message msg : messages) {
            if (msg == null) continue;
            if (GeneralVariables.checkIsBlockedMessage(msg)) continue;

            // Watchlist: any decode from a station on the user's watchlist — a rare
            // DXpedition, a needed prefix, a friend. NOT CQ-gated (checked before the
            // CQ-only continue below) so an in-QSO watched station still alerts the
            // instant it's on the air. Blocked messages already `continue`d above.
            boolean watched = GeneralVariables.checkIsWatchedCallsign(msg.getCallsignFrom());
            if (AlertDecisions.shouldAlertWatch(
                    GeneralVariables.hasWatchCallsigns(), watched, false)) {
                fire(AlertDecisions.watchDedupKey(msg.getCallsignFrom()),
                        CHANNEL_ID,
                        appContext.getString(R.string.alert_watch_title, msg.getCallsignFrom()),
                        defaultBody(msg), msg.getCallsignFrom(), msg.band);
            }

            // CQ reply: any decoded message addressed to my callsign. The batch is already
            // own-TX-echo filtered upstream, so a message to my call is another station
            // calling me. Checked before the CQ-only gate below since a reply isn't a CQ.
            boolean addressedToMe = GeneralVariables.checkIsMyCallsign(msg.getCallsignTo());
            if (AlertDecisions.shouldAlertCqReply(
                    GeneralVariables.alertOnCqReply, addressedToMe, false)) {
                fire(AlertDecisions.cqReplyDedupKey(msg.getCallsignFrom(), msg.getMessageText()),
                        QSO_CHANNEL_ID,
                        appContext.getString(R.string.alert_cq_reply_title, msg.getCallsignFrom()),
                        cqReplyBody(msg), msg.getCallsignFrom(), msg.band);
            }

            // Needed-DX alerts apply to CQ broadcasts only.
            if (!msg.checkIsCQ()) continue;

            if (GeneralVariables.alertNewDxcc && msg.fromDxcc) {
                String country = (msg.fromWhere == null || msg.fromWhere.isEmpty())
                        ? msg.getCallsignFrom() : msg.fromWhere;
                fire("DXCC:" + country, CHANNEL_ID, "New DXCC: " + country,
                        defaultBody(msg), msg.getCallsignFrom(), msg.band);
            }
            if (GeneralVariables.alertNewState && msg.fromNewState && msg.fromState != null) {
                fire("STATE:" + msg.fromState, CHANNEL_ID, "New state: " + msg.fromState,
                        defaultBody(msg), msg.getCallsignFrom(), msg.band);
            }
        }
    }

    /** Fire a notification when a QSO has just been logged, if the user enabled it. */
    public void notifyQsoComplete(QSLRecord qslRecord) {
        if (appContext == null || qslRecord == null) return;
        if (!GeneralVariables.alertOnQsoComplete) return;

        String call = qslRecord.getToCallsign();
        StringBuilder body = new StringBuilder(call == null ? "" : call);
        if (qslRecord.getToMaidenGrid() != null && !qslRecord.getToMaidenGrid().isEmpty()) {
            body.append("  ").append(qslRecord.getToMaidenGrid());
        }
        if (qslRecord.getMode() != null && !qslRecord.getMode().isEmpty()) {
            body.append("  ").append(qslRecord.getMode());
        }
        body.append("  R↑").append(qslRecord.getSendReport())
            .append(" R↓").append(qslRecord.getReceivedReport());

        fire(AlertDecisions.qsoCompleteDedupKey(call, qslRecord.getEndTime()),
                QSO_CHANNEL_ID,
                appContext.getString(R.string.alert_qso_complete_title),
                body.toString(), call, qslRecord.getBandFreq());
    }

    static String defaultBody(Ft8Message msg) {
        StringBuilder body = new StringBuilder(msg.getCallsignFrom());
        if (msg.maidenGrid != null && !msg.maidenGrid.isEmpty()) {
            body.append("  ").append(msg.maidenGrid);
        }
        // The decoder can emit a valid message with no SNR (FT8SignalListener logs
        // "SNR not set by decoder" and still adds it to the decode list), so snr may be
        // the SNR_UNKNOWN sentinel (Integer.MIN_VALUE). Appending it unconditionally
        // rendered "-2147483648 dB" in the notification body — mirror cqReplyBody and
        // drop the SNR line when it is unknown.
        if (msg.snr != Ft8Message.SNR_UNKNOWN) {
            body.append("  ").append(msg.snr).append(" dB");
        }
        return body.toString();
    }

    static String cqReplyBody(Ft8Message msg) {
        StringBuilder body = new StringBuilder(msg.getMessageText());
        if (msg.snr != Ft8Message.SNR_UNKNOWN) {
            body.append("  ").append(msg.snr).append(" dB");
        }
        return body.toString();
    }

    /**
     * Post a notification once per dedup key. {@code callsignExtra}/{@code bandExtra} ride on
     * the tap intent so the Decode screen can pre-select that station.
     */
    private void fire(String dedupKey, String channelId, String title, String body,
                      String callsignExtra, long bandExtra) {
        // Gate on notification permission BEFORE consuming the dedup key. If we burned the
        // key here while notifications are denied, the per-session `alerted` set (never
        // cleared) would suppress this station forever — even after the user later grants
        // POST_NOTIFICATIONS. claimAlert() checks `canPost` first, then dedups.
        boolean canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(appContext,
                        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (!AlertDecisions.claimAlert(alerted, dedupKey, canPost)) return;

        GeneralVariables.fileLog("ALERT fire key=[" + dedupKey + "] " + title);

        int id = dedupKey.hashCode();

        Intent intent = new Intent(appContext,
                radio.ks3ckc.ft8af.ComposeMainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (callsignExtra != null) intent.putExtra(EXTRA_CALLSIGN, callsignExtra);
        intent.putExtra(EXTRA_BAND, bandExtra);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(appContext, id, intent, piFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setContentIntent(pi);

        try {
            NotificationManagerCompat.from(appContext).notify(id, builder.build());
        } catch (SecurityException ignored) {
            // Permission revoked between the check and notify(): nothing was posted, so
            // release the key to let a later decode of the same station retry rather than
            // stay suppressed for the session.
            alerted.remove(dedupKey);
        }
    }
}
