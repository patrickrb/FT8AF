package com.k1af.ft8af.connector;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limits {@code UsbManager.requestPermission()} dialogs per USB vendor.
 *
 * <p>The system permission dialog was being raised on every link flap: each auto-connect
 * builds a brand-new {@link CableSerialPort}, whose per-instance {@code usbPermission}
 * state starts at {@code Unknown}, so the "already asked?" guard reset every time. On a
 * marginal cable that re-enumerates every few seconds — or on the bounce of a deliberate
 * unplug — that meant a fresh dialog per bounce (measured 2026-08-04, both the CAT serial
 * and the C-Media audio device). The ask-state must therefore outlive any single port
 * instance; it lives here, process-wide, keyed by vendor id (the same key
 * {@code CableSerialPort.prepare()} matches devices by).
 *
 * <p>This throttles how often the QUESTION is asked; it never grants or denies anything.
 * A user who wants no dialogs at all should tick the system dialog's
 * "Always open FT8AF…" checkbox, which persists the grant across replugs — this class
 * just keeps the app from nagging every few seconds until they do.
 *
 * <p>Pure decision logic is separated from the static registry for unit-testing, matching
 * {@link CatReconnectPolicy}.
 */
public final class UsbPermissionThrottle {

    private UsbPermissionThrottle() {}

    /**
     * Minimum interval between permission dialogs for the same vendor. Long enough that
     * a flapping link nags at most twice a minute instead of once per bounce; short
     * enough that a genuinely new session (fresh cable plug-in after a while) asks
     * promptly.
     */
    public static final long REQUEST_COOLDOWN_MS = 30_000;

    private static final ConcurrentHashMap<Integer, Long> lastRequestAtByVendor =
            new ConcurrentHashMap<>();

    /**
     * Whether a permission request may be shown now, given when one was last shown.
     *
     * @param lastRequestAtMs when a dialog was last raised for this device, or 0 if never
     * @param nowMs           current wall clock
     */
    public static boolean shouldRequest(long lastRequestAtMs, long nowMs) {
        if (lastRequestAtMs <= 0) return true;
        long since = nowMs - lastRequestAtMs;
        // A backwards clock correction errs toward asking: one extra dialog beats a
        // lock-out that lasts until the clock catches back up.
        if (since < 0) return true;
        return since >= REQUEST_COOLDOWN_MS;
    }

    /** Registry form of {@link #shouldRequest(long, long)} for the given vendor. */
    public static boolean shouldRequestNow(int vendorId, long nowMs) {
        Long last = lastRequestAtByVendor.get(vendorId);
        return shouldRequest(last == null ? 0L : last, nowMs);
    }

    /**
     * Record that a permission dialog has just been raised for the given vendor.
     *
     * <p>Monotonic: two threads racing here (a bounce re-enumerates both serial ports
     * near-simultaneously) must not let the later stamp be overwritten by the earlier
     * one, which would shorten the cooldown and let an extra dialog through.
     */
    public static void markRequested(int vendorId, long nowMs) {
        lastRequestAtByVendor.merge(vendorId, nowMs, Math::max);
    }

    /** Test isolation only: forget every recorded request. */
    static void reset() {
        lastRequestAtByVendor.clear();
    }
}
