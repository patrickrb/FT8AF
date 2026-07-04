package com.k1af.ft8af.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;

/**
 * Foreground "keeper" service that lets RX decode + audio capture continue when the app is
 * backgrounded or the screen is off — without which Android 14 mutes background microphone
 * capture and the OS may kill the process. It does NOT own the RX pipeline: {@code
 * MainViewModel} still owns the recorder, {@code UtcTimer}, and decode threads. This service
 * only (1) declares the {@code microphone} foreground-service type so background mic capture
 * is permitted, (2) holds a partial wakelock so decode threads get CPU during Doze, and
 * (3) shows the required ongoing notification.
 *
 * <p>Started from {@code ComposeMainActivity} once RECORD_AUDIO is granted, stopped on app
 * exit. Policy lives in {@link RxServiceController} (unit-tested).
 */
public class RxForegroundService extends Service {
    private static final String CHANNEL_ID = "rx_running";
    private static final int NOTIF_ID = 0x46543852; // "FT8R"
    private static final String WAKELOCK_TAG = "ft8af:rx";

    private PowerManager.WakeLock wakeLock;

    /**
     * Cleanup hook run when the notification's Exit button is tapped. The activity registers
     * its {@code closeApp()} here (and clears it on destroy) because the full shutdown —
     * disconnect rig, stop listener/recorder/timer — needs the activity-scoped ViewModel,
     * which this service can't reach.
     */
    private static volatile Runnable exitHandler;

    public static void setExitHandler(Runnable handler) {
        exitHandler = handler;
    }

    public static void start(Context context) {
        ContextCompat.startForegroundService(
                context, new Intent(context, RxForegroundService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, RxForegroundService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // not a bound service
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (RxServiceController.commandForAction(action) == RxServiceController.Command.EXIT) {
            GeneralVariables.fileLog("RxForegroundService: notification Exit tapped");
            Runnable handler = exitHandler;
            if (handler != null) {
                // closeApp(): stops TX/RX, disconnects the rig, stops this service, then
                // System.exit(0) — does not return.
                handler.run();
            } else {
                // Activity already destroyed; nothing left to clean up beyond the process.
                stopSelf();
                System.exit(0);
            }
            return START_NOT_STICKY;
        }
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Exception e) {
            // e.g. RECORD_AUDIO not granted (SecurityException) or a background-start block
            // on Android 12+. Don't crash the app over the keeper service — log and bail.
            GeneralVariables.fileLog("RxForegroundService start failed: " + e);
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireWakeLock();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.rx_service_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.rx_service_channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, radio.ks3ckc.ft8af.ComposeMainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, piFlags);
        Intent exitIntent = new Intent(this, RxForegroundService.class)
                .setAction(RxServiceController.ACTION_EXIT);
        PendingIntent exitPi = PendingIntent.getService(this, 1, exitIntent, piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.rx_service_title))
                .setContentText(getString(R.string.rx_service_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pi)
                .addAction(0, getString(R.string.action_exit), exitPi)
                .build();
    }
}
