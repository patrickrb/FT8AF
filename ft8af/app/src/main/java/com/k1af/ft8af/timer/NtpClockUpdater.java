package com.k1af.ft8af.timer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.location.GpsClockUpdater;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Disciplines the app clock from a network NTP server while
 * {@link GeneralVariables#disciplineClockFromNtp} is enabled — the NTP counterpart to
 * {@link GpsClockUpdater}. Same target ({@link UtcTimer#delay}), same periodic-discipline
 * shape (capture the pre-discipline baseline, apply a fresh offset on every sync, restore
 * the baseline on stop), but no {@code LocationManager}/runtime permission — only
 * {@code INTERNET}, already declared and install-time granted — so this self-schedules on a
 * {@link Handler} instead of subscribing through
 * {@link com.k1af.ft8af.location.LocationSubscriber}.
 *
 * <p>Mutually exclusive with GPS discipline: {@code TimeSyncSettings} enforces that only one
 * is ever running, disabling the other (synchronously, via its own {@code refresh()}) before
 * enabling this one, so each updater's captured baseline is the true pre-discipline offset
 * rather than the other's still-applied one.
 *
 * <p>Resolves the configured server for both A and AAAA records
 * ({@link InetAddress#getAllByName}) and tries each with a fresh {@link NTPUDPClient} and a
 * timeout, so a network with no route for one address family still gets a reply from the
 * other rather than waiting out a dead address's full timeout — ported from FT8CN's
 * {@code UtcTimer.syncTimeByNtp}. The offset applies the FULL server/device delta (no
 * cycle-modulo truncation), matching how GPS discipline and the manual correction both write
 * {@link UtcTimer#delay}.
 *
 * <p>Singleton — call {@link #refresh(Context)} whenever the toggle/interval/server changes
 * or the activity starts.
 */
public class NtpClockUpdater {
    private static final String TAG = "NtpClockUpdater";

    /**
     * A sync implying a correction larger than this is treated as bad data and ignored (see
     * {@link #isOffsetSane}). Matches {@link GpsClockUpdater#MAX_SANE_OFFSET_MS}: the offset
     * is written straight into {@link UtcTimer#delay}, which moves the whole FT8 cycle grid,
     * so an hour-scale "correction" can only be a corrupt/spoofed reply or a badly
     * misconfigured server.
     */
    static final long MAX_SANE_OFFSET_MS = 60L * 60L * 1000L;

    /** Per-address NTP request timeout (ms), matching FT8CN's reference implementation. */
    static final int NTP_TIMEOUT_MS = 5000;

    /** Configurable update-interval bounds (minutes), matching GPS discipline. */
    static final int MIN_INTERVAL_MINUTES = 1;
    static final int MAX_INTERVAL_MINUTES = 30;
    /** Default interval (minutes) when the persisted value is absent or unparseable. */
    static final int DEFAULT_INTERVAL_MINUTES = 5;

    /** Sentinel for {@link #savedDelayBeforeNtp}: no pre-NTP offset captured. */
    private static final int NO_SAVED_DELAY = Integer.MIN_VALUE;

    private static NtpClockUpdater instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private long subscribedIntervalMs = -1;
    // The offset UtcTimer.delay held *before* NTP discipline took it over (GPS, a manual
    // correction, or 0). Captured on the first start() and restored by stop() so disabling
    // NTP returns the clock to its pre-NTP state instead of clobbering it with
    // manualTimeCorrectionMs, which NTP itself never writes. NO_SAVED_DELAY means "not
    // disciplining".
    private int savedDelayBeforeNtp = NO_SAVED_DELAY;

    // Single reusable Runnable so removeCallbacks(periodicSyncTask) on stop()/retune reliably
    // cancels a pending tick — a fresh Runnable per call would not match (Handler.removeCallbacks
    // matches on the exact Runnable instance).
    private final Runnable periodicSyncTask = new Runnable() {
        @Override
        public void run() {
            kickBackgroundSync();
            synchronized (NtpClockUpdater.this) {
                if (running) mainHandler.postDelayed(this, subscribedIntervalMs);
            }
        }
    };

    private NtpClockUpdater(Context context) {
        // context kept implicitly unused beyond construction: nothing here needs it (no
        // LocationManager/permission checks), but the ctor mirrors GpsClockUpdater's shape.
    }

    public static synchronized NtpClockUpdater getInstance(Context context) {
        if (instance == null) {
            instance = new NtpClockUpdater(context);
        }
        return instance;
    }

    /**
     * Start, stop, or re-tune the updater to match the current toggle and interval. Safe to
     * call repeatedly.
     */
    public static synchronized void refresh(Context context) {
        getInstance(context).refreshSubscription();
    }

    private synchronized void refreshSubscription() {
        if (GeneralVariables.disciplineClockFromNtp) {
            start();
        } else {
            stop();
        }
    }

    private synchronized void start() {
        long intervalMs = clampIntervalMinutes(GeneralVariables.ntpClockIntervalMinutes) * 60_000L;

        // Already running at the requested cadence — nothing to do.
        if (!shouldReschedule(running, subscribedIntervalMs, intervalMs)) return;

        // Drop any pending tick from a prior cadence before rescheduling at the new one.
        mainHandler.removeCallbacks(periodicSyncTask);

        // Capture the pre-NTP offset once, before any sync overwrites UtcTimer.delay, so
        // stop() can hand the clock back exactly as it was. Only on the first start() — a
        // re-tune reaches here with delay already holding an NTP offset, which must not be
        // captured.
        if (savedDelayBeforeNtp == NO_SAVED_DELAY) {
            savedDelayBeforeNtp = UtcTimer.delay;
        }
        subscribedIntervalMs = intervalMs;
        running = true;

        // First sync fires on the next looper iteration, not inline, so refresh() returns
        // immediately and a slow/hung server can't block the caller (the Compose toggle
        // handler).
        mainHandler.post(periodicSyncTask);
        Log.d(TAG, "Started NTP clock discipline, interval " + intervalMs + "ms, server "
                + GeneralVariables.getNtpServer());
    }

    /** Disable discipline: cancel the pending tick and restore the clock to its pre-NTP offset. */
    private synchronized void stop() {
        if (!running && savedDelayBeforeNtp == NO_SAVED_DELAY) return;
        mainHandler.removeCallbacks(periodicSyncTask);
        running = false;
        subscribedIntervalMs = -1;

        if (savedDelayBeforeNtp != NO_SAVED_DELAY) {
            UtcTimer.delay = savedDelayBeforeNtp;
            GeneralVariables.fileLog("NtpClock: stopped discipline; restored pre-NTP offset "
                    + savedDelayBeforeNtp + "ms");
            savedDelayBeforeNtp = NO_SAVED_DELAY;
        }
    }

    /** Whether discipline is currently running. Package-private, for tests. */
    boolean isRunning() {
        return running;
    }

    // Fire-and-forget: does NOT block periodicSyncTask.run(), so the next tick is scheduled
    // subscribedIntervalMs after this one STARTED, not after the network round trip finished
    // — a hung/slow server can't stall the reschedule chain.
    private void kickBackgroundSync() {
        new Thread(this::performDualStackSync).start();
    }

    /**
     * Resolve the configured server for both A and AAAA records and try each with a fresh
     * client until one replies. Ported from FT8CN's {@code UtcTimer.syncTimeByNtp}: a fresh
     * {@link NTPUDPClient} per address avoids a late reply from a prior timed-out attempt
     * being misattributed to the next attempt. Only a network-level failure (timeout, no
     * route, {@link IOException}) advances to the next address — a received-but-implausible
     * reply is rejected by {@link #applySyncResult} and the loop stops there, since a bad
     * reply is a data problem, not a connectivity one.
     */
    private void performDualStackSync() {
        String server = GeneralVariables.getNtpServer();
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(server);
        } catch (IOException e) {
            Log.d(TAG, "NTP resolve failed for " + server + ": " + e.getMessage());
            GeneralVariables.fileLog("NtpClock: resolve failed for " + server + ": " + e.getMessage());
            GeneralVariables.mutableNtpClockSyncFailed.postValue(true);
            return;
        }

        for (InetAddress address : addresses) {
            NTPUDPClient timeClient = new NTPUDPClient();
            try {
                timeClient.setDefaultTimeout(NTP_TIMEOUT_MS);
                TimeInfo timeInfo = timeClient.getTime(address);
                long serverTransmitMs = timeInfo.getMessage().getTransmitTimeStamp().getTime();
                applySyncResult(serverTransmitMs);
                return; // got a reply (accepted or rejected) — connectivity succeeded
            } catch (IOException e) {
                Log.d(TAG, "NTP attempt failed for " + address + ": " + e.getMessage());
            } finally {
                timeClient.close();
            }
        }

        GeneralVariables.fileLog("NtpClock: all addresses for " + server + " unreachable");
        GeneralVariables.mutableNtpClockSyncFailed.postValue(true);
    }

    /** Compute, sanity-check, and apply the offset from a single NTP reply. Package-private, for tests. */
    void applySyncResult(long serverTransmitMs) {
        long nowSystemMs = System.currentTimeMillis();
        // Sample once and feed the same reading to the evaluation and the log, so a logged
        // "REJECTED" offset can't disagree with the number actually judged against the bound.
        int rawOffsetMs = ntpClockOffsetMs(serverTransmitMs, nowSystemMs);
        Integer offsetMs = computeAppliedOffset(isRunning(), serverTransmitMs, nowSystemMs);

        if (offsetMs == null) {
            Log.d(TAG, "Ignoring NTP reply (not running or implausible): serverTransmitMs=" + serverTransmitMs);
            if (isRunning()) {
                GeneralVariables.fileLog("NtpClock: REJECTED sync offset=" + rawOffsetMs
                        + "ms (absMax=" + MAX_SANE_OFFSET_MS + "ms) — clock left at " + UtcTimer.delay + "ms");
                GeneralVariables.mutableNtpClockSyncFailed.postValue(true);
            }
            return;
        }

        GeneralVariables.fileLog("NtpClock: applied offset " + offsetMs + "ms (was " + UtcTimer.delay + "ms)");
        UtcTimer.delay = offsetMs;
        GeneralVariables.ntpClockOffsetMs = offsetMs;
        GeneralVariables.mutableNtpClockSyncFailed.postValue(false);
        // The UI renders this as "... UTC", so post the disciplined time, not the raw
        // (possibly-wrong) system clock we just computed a correction for.
        GeneralVariables.mutableNtpClockSync.postValue(disciplinedUtcMs(nowSystemMs, offsetMs));
        Log.d(TAG, "NTP clock discipline applied offset " + offsetMs + "ms");
    }

    // =====================================================================
    // Pure, testable decision logic (no network needed).
    // =====================================================================

    /**
     * Offset to add to {@link System#currentTimeMillis()} so the app clock matches the NTP
     * server's transmit timestamp. Positive means the device clock is slow (behind the
     * server) and needs shifting forward. The full delta is used, not truncated to one FT8
     * cycle — {@code UtcTimer.isCycleBoundary} only needs the offset modulo the slot length
     * for RX/TX alignment, but truncating here would leave the on-screen clock, QSO
     * timestamps, and PSKReporter spot times off by a whole multiple of 15s whenever the
     * device clock is more than one cycle out.
     */
    static int ntpClockOffsetMs(long serverTransmitMs, long nowSystemMs) {
        long offset = serverTransmitMs - nowSystemMs;
        if (offset > Integer.MAX_VALUE) offset = Integer.MAX_VALUE;
        if (offset < Integer.MIN_VALUE) offset = Integer.MIN_VALUE;
        return (int) offset;
    }

    /**
     * Whether a reply should be trusted to discipline the clock: the server must have sent a
     * real transmit timestamp ({@code > 0}) and the implied correction must be within
     * {@link #MAX_SANE_OFFSET_MS}.
     */
    static boolean isOffsetSane(long serverTransmitMs, int offsetMs) {
        if (serverTransmitMs <= 0) return false;
        return Math.abs((long) offsetMs) <= MAX_SANE_OFFSET_MS;
    }

    /** Coerce a configured update interval into the allowed range (minutes). */
    public static int clampIntervalMinutes(int minutes) {
        if (minutes < MIN_INTERVAL_MINUTES) return MIN_INTERVAL_MINUTES;
        return Math.min(minutes, MAX_INTERVAL_MINUTES);
    }

    /**
     * Parse a persisted interval string into a valid interval (minutes): the default when it
     * is null/blank/non-numeric, otherwise the parsed value clamped into range. Shared with
     * {@code DatabaseOpr}'s config load so the fallback and clamp are covered by one test.
     */
    public static int parseIntervalMinutes(String raw) {
        if (raw == null) return DEFAULT_INTERVAL_MINUTES;
        int minutes;
        try {
            minutes = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_INTERVAL_MINUTES;
        }
        return clampIntervalMinutes(minutes);
    }

    /**
     * The corrected UTC instant for a system-clock reading: what the disciplined clock says
     * "now" is. Used for the last-sync timestamp shown (as UTC) in settings.
     */
    static long disciplinedUtcMs(long nowSystemMs, int offsetMs) {
        return nowSystemMs + offsetMs;
    }

    /**
     * Whether {@code start()} needs to (re)schedule: yes when not currently running, or when
     * running at a cadence different from the one requested. A no-op when already scheduled at
     * the requested interval, so repeated refreshes don't churn the periodic task.
     */
    static boolean shouldReschedule(boolean running, long subscribedIntervalMs, long requestedIntervalMs) {
        return !running || subscribedIntervalMs != requestedIntervalMs;
    }

    /**
     * The offset to apply for a reply, or {@code null} to ignore it. Ignored when discipline is
     * no longer running (a reply that raced past a disable) or when the implied correction is
     * implausible ({@link #isOffsetSane}). Kept pure so both guard branches are unit-tested
     * without a network round trip.
     */
    static Integer computeAppliedOffset(boolean running, long serverTransmitMs, long nowSystemMs) {
        if (!running) return null;
        int offsetMs = ntpClockOffsetMs(serverTransmitMs, nowSystemMs);
        if (!isOffsetSane(serverTransmitMs, offsetMs)) return null;
        return offsetMs;
    }
}
