package com.k1af.ft8af.handoff;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.k1af.ft8af.GeneralVariables;

/**
 * Android side of the rig handoff: one process-lifetime broadcast receiver plus the
 * senders, wired to a {@link RigHandoffController}. All policy lives in the controller and
 * {@link RigHandoffProtocol}; this class only moves messages.
 *
 * <p>The receiver is registered on the application context and never unregistered: the
 * foreground service keeps the process — and its hold on the rig — alive after the
 * activity is gone, and that is exactly when a sibling needs to be able to ask for it.
 * It is context-registered rather than declared in the manifest on purpose, so a takeover
 * can never start an app that is not running (an app that is not running holds no rig).
 */
public final class RigHandoff {

    private RigHandoff() {}

    private static RigHandoffController controller;

    /** The installed controller, or null before {@link #install}. */
    public static synchronized RigHandoffController get() {
        return controller;
    }

    /**
     * Register the receiver and build the controller. Idempotent: a recreated activity
     * gets the existing controller back, and the receiver is registered once per process.
     */
    public static synchronized RigHandoffController install(
            Context context, RigHandoffController.Rig rig,
            RigHandoffController.Listener listener) {
        if (controller != null) return controller;
        final Context app = context.getApplicationContext();
        final String self = app.getPackageName();
        final Handler main = new Handler(Looper.getMainLooper());

        RigHandoffController.Transport transport = new RigHandoffController.Transport() {
            @Override
            public void sendTakeover() {
                for (String sibling : RigHandoffProtocol.siblingsOf(self)) {
                    send(app, self, RigHandoffProtocol.ACTION_TAKEOVER, sibling);
                }
            }

            @Override
            public void sendReleased(String toPackage) {
                send(app, self, RigHandoffProtocol.ACTION_RELEASED, toPackage);
            }
        };
        RigHandoffController.Scheduler scheduler = new RigHandoffController.Scheduler() {
            @Override
            public void postDelayed(Runnable task, long delayMs) {
                main.postDelayed(task, delayMs);
            }

            @Override
            public void cancel(Runnable task) {
                main.removeCallbacks(task);
            }
        };
        final RigHandoffController created =
                new RigHandoffController(self, rig, transport, scheduler, listener);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                String from = intent.getStringExtra(RigHandoffProtocol.EXTRA_FROM_PACKAGE);
                long sentAt = intent.getLongExtra(RigHandoffProtocol.EXTRA_SENT_AT_MS, 0L);
                // Android 14+ attests the sender when it opted in (see send()).
                String verified = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        ? getSentFromPackage() : null;
                long now = System.currentTimeMillis();
                boolean honoured;
                if (RigHandoffProtocol.ACTION_TAKEOVER.equals(action)) {
                    honoured = created.onTakeoverRequested(from, verified, sentAt, now);
                } else if (RigHandoffProtocol.ACTION_RELEASED.equals(action)) {
                    honoured = created.onReleased(from, verified, sentAt, now);
                } else {
                    return;
                }
                GeneralVariables.fileLog("RigHandoff: received "
                        + shortAction(action) + " from=" + from + " verified=" + verified
                        + " ageMs=" + (now - sentAt)
                        + (honoured ? " -> honoured" : " -> IGNORED"));
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(RigHandoffProtocol.ACTION_TAKEOVER);
        filter.addAction(RigHandoffProtocol.ACTION_RELEASED);
        // Exported: the senders are other apps. What may be obeyed is decided by
        // RigHandoffProtocol.shouldHonor, not by who can reach this receiver.
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED);

        controller = created;
        return created;
    }

    private static void send(Context app, String self, String action, String toPackage) {
        Intent intent = new Intent(action)
                .setPackage(toPackage)
                // Only a running sibling can be holding the rig; never start one.
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(RigHandoffProtocol.EXTRA_FROM_PACKAGE, self)
                .putExtra(RigHandoffProtocol.EXTRA_SENT_AT_MS, System.currentTimeMillis());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Let the receiver see who really sent this (getSentFromPackage).
                BroadcastOptions options = BroadcastOptions.makeBasic();
                options.setShareIdentityEnabled(true);
                app.sendBroadcast(intent, null, options.toBundle());
            } else {
                app.sendBroadcast(intent);
            }
            GeneralVariables.fileLog("RigHandoff: sent " + shortAction(action)
                    + " to=" + toPackage);
        } catch (RuntimeException e) {
            GeneralVariables.fileLog("RigHandoff: send " + shortAction(action)
                    + " to=" + toPackage + " failed: " + e);
        }
    }

    private static String shortAction(String action) {
        if (action == null) return "null";
        return action.substring(action.lastIndexOf('.') + 1);
    }
}
