package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.timer.UtcTimer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;

/**
 * Regression coverage for the WSJT-X UDP inbound Free-Text crash.
 *
 * <p>An inbound Free-Text request (a companion app driving us with "accept requests"
 * on) can be the very first TX action of a session — before any CQ or decode tap has
 * set a target. {@code transmitNow()} unconditionally dereferences {@code toCallsign}
 * (its "adjust call target" status toast), which stays {@code null} until the first
 * {@code setTransmit}/{@code resetToCQ}. The Free-Text handler therefore must route
 * through {@code sendFreeTextOnce}, which seeds a CQ baseline (issue #401), rather than
 * open-coding {@code setActivated(true) + transmitNow()} and hitting a main-thread NPE.
 *
 * <p>The first test reproduces the exact null state the buggy path left; the second
 * proves the seed the fixed path performs removes that null-deref precondition. Both
 * are native-free and deterministic (the NPE fires before the slot/doTransmit logic,
 * and {@code resetToCQ} only builds the message list).
 */
@RunWith(RobolectricTestRunner.class)
public class FreeTextTransmitTargetSeedTest {

    private FT8TransmitSignal signal;

    @Before
    public void setUp() {
        // A valid callsign (length >= 3) so transmitNow() passes the readiness gate and
        // reaches the toCallsign dereference — the crash the buggy Free-Text path hit.
        GeneralVariables.myCallsign = "K1ABC";
        signal = new FT8TransmitSignal(null, null, null);
    }

    @After
    public void tearDown() {
        GeneralVariables.myCallsign = "";
        UtcTimer.delay = 0;// undo any clock pinning so other tests see the real clock
    }

    /**
     * Pin the wall clock (via {@link UtcTimer#delay}) so {@code getNowSequential()}
     * lands on {@code targetParity}, in the MIDDLE of that slot. Landing mid-slot keeps
     * the parity stable across {@code transmitNow()}'s single {@code getNowSequential()}
     * read (no slot-boundary race), which lets a test force a deterministic slot
     * mismatch: with the signal's post-seed sequential != {@code targetParity},
     * {@code transmitNow()} returns after the toast/flag updates instead of entering
     * {@code doTransmit()} — so the native encoder never runs on a background thread.
     */
    private static void pinNowSequential(int targetParity) {
        int slot = GeneralVariables.currentMode().slotMillis;
        long now = System.currentTimeMillis();
        // Shift so we sit at slot/2 (far from both slot boundaries).
        long adjust = (slot / 2L) - (now % slot);
        // If that lands on the wrong parity, shift one whole slot; still mid-slot.
        if (((now + adjust) / slot) % 2 != targetParity) {
            adjust += slot;
        }
        UtcTimer.delay = (int) adjust;
    }

    private Object toCallsign() throws Exception {
        Field f = FT8TransmitSignal.class.getDeclaredField("toCallsign");
        f.setAccessible(true);
        return f.get(signal);
    }

    private boolean freeTextOneShotFlag() throws Exception {
        Field f = FT8TransmitSignal.class.getDeclaredField("freeTextOneShot");
        f.setAccessible(true);
        return f.getBoolean(signal);
    }

    @Test
    public void transmitNow_withNoTargetSeeded_throwsNpe() throws Exception {
        // Fresh signal: no setTransmit/resetToCQ has run, so there is no TX target.
        assertThat(toCallsign()).isNull();
        // Exactly what the open-coded Free-Text path did: fire transmitNow() with no
        // target. The status-toast argument dereferences the null toCallsign -> NPE.
        assertThrows(NullPointerException.class, () -> signal.transmitNow());
    }

    @Test
    public void resetToCQ_seedsTarget_removingTheNullDeref() throws Exception {
        // sendFreeTextOnce (the entry point the fixed handler now uses) seeds a CQ
        // baseline via resetToCQ when no target has been set this session.
        assertThat(toCallsign()).isNull();
        signal.resetToCQ();
        // Target now present, so transmitNow()'s toCallsign deref is safe on a
        // first-action Free-Text send.
        assertThat(toCallsign()).isNotNull();
    }

    @Test
    public void sendFreeTextOnce_firstActionOfSession_doesNotCrashAndArmsOneShot() throws Exception {
        // The behavior the fixed handler routes to: an inbound Free-Text send that is the
        // first TX action of the session (no target set). This must NOT throw (the CQ
        // baseline is seeded) and must arm the one-shot so the text goes out once rather
        // than repeating every cycle.
        // Force a deterministic slot mismatch. sendFreeTextOnce seeds a CQ baseline
        // (resetToCQ), which sets the signal's sequential to (0 + 1) % 2 == 1 on a fresh
        // signal. Pinning getNowSequential() to 0 makes transmitNow()'s slot check
        // (getNowSequential() == sequential) fail, so it returns right after updating the
        // toast/flags — it never enters doTransmit()/the native encoder. Without this the
        // test is time-dependent: ~half of runs the live slot matches and, if early in the
        // cycle, a background native encode fires (flaky, UnsatisfiedLinkError noise).
        pinNowSequential(0);
        assertThat(toCallsign()).isNull();
        signal.sendFreeTextOnce("TEST DE K1ABC");
        assertThat(signal.isTransmitFreeText()).isTrue();
        assertThat(freeTextOneShotFlag()).isTrue();
        assertThat(toCallsign()).isNotNull();
    }
}
