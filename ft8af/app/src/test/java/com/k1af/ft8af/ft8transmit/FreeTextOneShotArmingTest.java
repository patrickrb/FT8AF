package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.GeneralVariables;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;

/**
 * Coverage for issue #401: a one-shot free-text send must not stay armed when a
 * short/empty callsign blocks the transmission.
 *
 * <p>Before the fix, {@code sendFreeTextOnce} armed {@code transmitFreeText}/
 * {@code freeTextOneShot} and called {@code setActivated(true)}, which only checks
 * SWR lockout — so with a bad callsign the cleanup guard was skipped, while
 * {@code setTransmitting(true)} refused to key and {@code afterPlayAudio()} (the
 * normal disarm point via {@code shouldStopAfterOneShot}) never ran. The stale free
 * text then replaced the operator's next CQ/standard message once they fixed their
 * callsign.
 *
 * <p>Robolectric: the flows post toasts/LiveData through the Android runtime.
 */
@RunWith(RobolectricTestRunner.class)
public class FreeTextOneShotArmingTest {

    private FT8TransmitSignal signal;

    @Before
    public void setUp() {
        GeneralVariables.myCallsign = "";
        signal = new FT8TransmitSignal(null, null, null);
    }

    @After
    public void tearDown() {
        GeneralVariables.myCallsign = "";
    }

    private boolean freeTextOneShotFlag() throws Exception {
        Field f = FT8TransmitSignal.class.getDeclaredField("freeTextOneShot");
        f.setAccessible(true);
        return f.getBoolean(signal);
    }

    private void armOneShotFlags() throws Exception {
        Field oneShot = FT8TransmitSignal.class.getDeclaredField("freeTextOneShot");
        oneShot.setAccessible(true);
        oneShot.setBoolean(signal, true);
        Field freeText = FT8TransmitSignal.class.getDeclaredField("transmitFreeText");
        freeText.setAccessible(true);
        freeText.setBoolean(signal, true);
    }

    @Test
    public void sendFreeTextOnce_shortCallsign_doesNotArm() throws Exception {
        GeneralVariables.myCallsign = "K1";

        signal.sendFreeTextOnce("TEST MSG");

        // The issue's exact leak: these staying true is what injected the stale
        // free text into the next CQ over.
        assertThat(signal.isTransmitFreeText()).isFalse();
        assertThat(freeTextOneShotFlag()).isFalse();
        // And activation must not have been flipped on for a send that can't start.
        assertThat(signal.isActivated()).isFalse();
    }

    @Test
    public void sendFreeTextOnce_emptyCallsign_doesNotArm() throws Exception {
        GeneralVariables.myCallsign = "";

        signal.sendFreeTextOnce("TEST MSG");

        assertThat(signal.isTransmitFreeText()).isFalse();
        assertThat(freeTextOneShotFlag()).isFalse();
        assertThat(signal.isActivated()).isFalse();
    }

    @Test
    public void refusedTransmitStart_disarmsAnArmedOneShot() throws Exception {
        // Defense in depth: if the one-shot is already armed (e.g. the callsign was
        // cleared between arming and the slot boundary), a refused setTransmitting(true)
        // must disarm it rather than leave it for the next over.
        GeneralVariables.myCallsign = "K1";
        armOneShotFlags();

        signal.setTransmitting(true);

        assertThat(signal.isTransmitting()).isFalse();
        assertThat(signal.isTransmitFreeText()).isFalse();
        assertThat(freeTextOneShotFlag()).isFalse();
    }

    @Test
    public void refusedTransmitStop_isUnaffectedByGuard() {
        // setTransmitting(false) must keep working with any callsign (stop is always
        // allowed) and must not touch free-text state it didn't set.
        GeneralVariables.myCallsign = "";
        signal.setTransmitting(false);
        assertThat(signal.isTransmitting()).isFalse();
    }
}
