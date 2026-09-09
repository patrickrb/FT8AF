package com.k1af.ft8af.connector;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link UsbPermissionThrottle} — the process-wide rate limit on the
 * system USB-permission dialog.
 *
 * <p>Reported as "the app keeps constantly asking for USB permission… and it's doing it
 * when I disconnect the USB cable". Every auto-connect builds a fresh
 * {@code CableSerialPort} whose per-instance ask-state starts at {@code Unknown}, so on a
 * flapping link (or the plug bounce of an unplug) each bounce raised a fresh dialog. The
 * ask-state has to outlive the port instance; this registry is that state.
 */
public class UsbPermissionThrottleTest {

    private static final long T0 = 1_700_000_000_000L;
    /** CP2105 dual UART (the CAT serial bridge from the 2026-08-04 log). */
    private static final int VENDOR_SERIAL = 0x10c4;
    /** C-Media USB audio (same log). */
    private static final int VENDOR_AUDIO = 0x0d8c;

    @Before
    public void resetRegistry() {
        UsbPermissionThrottle.reset();
    }

    // ---- shouldRequest (pure) ----------------------------------------------

    @Test
    public void neverAsked_asksImmediately() {
        assertThat(UsbPermissionThrottle.shouldRequest(0L, T0)).isTrue();
    }

    @Test
    public void justAsked_staysQuiet() {
        // The flapping-link case: a bounce milliseconds after the last dialog must
        // not raise another one.
        assertThat(UsbPermissionThrottle.shouldRequest(T0, T0 + 1)).isFalse();
    }

    @Test
    public void asksAgainOnceTheCooldownExpires() {
        long after = T0 + UsbPermissionThrottle.REQUEST_COOLDOWN_MS;
        assertThat(UsbPermissionThrottle.shouldRequest(T0, after)).isTrue();
        assertThat(UsbPermissionThrottle.shouldRequest(T0, after - 1)).isFalse();
    }

    @Test
    public void backwardsClockErrsTowardAsking() {
        // The clock is disciplined by GPS/decode sync and can step backwards. One
        // extra dialog beats a lock-out that lasts until the clock catches back up.
        assertThat(UsbPermissionThrottle.shouldRequest(T0, T0 - 60_000)).isTrue();
    }

    @Test
    public void cooldownIsLongEnoughToTameAFlappingLink() {
        // The measured link flapped every few seconds; a cooldown shorter than that
        // would not reduce the dialog rate at all.
        assertThat(UsbPermissionThrottle.REQUEST_COOLDOWN_MS).isAtLeast(10_000L);
    }

    // ---- registry (per-vendor) ---------------------------------------------

    @Test
    public void registry_unknownVendorAsksImmediately() {
        assertThat(UsbPermissionThrottle.shouldRequestNow(VENDOR_SERIAL, T0)).isTrue();
    }

    @Test
    public void registry_markThenAskWithinCooldownIsThrottled() {
        UsbPermissionThrottle.markRequested(VENDOR_SERIAL, T0);
        assertThat(UsbPermissionThrottle.shouldRequestNow(VENDOR_SERIAL, T0 + 5_000)).isFalse();
        assertThat(UsbPermissionThrottle.shouldRequestNow(VENDOR_SERIAL,
                T0 + UsbPermissionThrottle.REQUEST_COOLDOWN_MS)).isTrue();
    }

    @Test
    public void registry_vendorsAreIndependent() {
        // Throttling the serial bridge must not silence the audio device's one
        // legitimate ask (they arrive near-simultaneously on attach).
        UsbPermissionThrottle.markRequested(VENDOR_SERIAL, T0);
        assertThat(UsbPermissionThrottle.shouldRequestNow(VENDOR_AUDIO, T0 + 1)).isTrue();
    }

    @Test
    public void registry_outOfOrderMarksCannotShortenTheCooldown() {
        // Two threads racing markRequested (a bounce re-enumerates both serial
        // ports near-simultaneously) can land out of order. The earlier stamp must
        // not overwrite the later one — that would end the cooldown early and let
        // an extra dialog through.
        UsbPermissionThrottle.markRequested(VENDOR_SERIAL, T0);
        UsbPermissionThrottle.markRequested(VENDOR_SERIAL, T0 - 25_000);
        assertThat(UsbPermissionThrottle.shouldRequestNow(VENDOR_SERIAL, T0 + 10_000)).isFalse();
        assertThat(UsbPermissionThrottle.shouldRequestNow(VENDOR_SERIAL,
                T0 + UsbPermissionThrottle.REQUEST_COOLDOWN_MS)).isTrue();
    }

    @Test
    public void registry_outlivesAPortInstance() {
        // The whole point: the state is static, so a "new CableSerialPort" (modelled
        // here by nothing at all — there is no per-instance state to reset) still sees
        // the earlier ask.
        UsbPermissionThrottle.markRequested(VENDOR_SERIAL, T0);
        assertThat(UsbPermissionThrottle.shouldRequestNow(VENDOR_SERIAL, T0 + 1_000)).isFalse();
    }
}
