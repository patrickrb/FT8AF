package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for {@link MainViewModel#isOurOwnRow} — the tap-to-call guard
 * against arming a QSO with ourselves. With full-duplex monitoring our own
 * echoes are on the tappable list, so the guard has to recognise us the same
 * way {@link OwnTxEchoFilter} does: a compound call (K1AF/P) transmits and
 * echoes as the bare base call, which a raw {@code equalsIgnoreCase} against
 * the configured callsign would miss.
 */
@RunWith(RobolectricTestRunner.class)
public class OwnRowGuardTest {

    private String savedCallsign;

    @Before
    public void setUp() {
        savedCallsign = GeneralVariables.myCallsign;
    }

    @After
    public void tearDown() {
        GeneralVariables.myCallsign = savedCallsign;
    }

    @Test
    public void taggedEchoIsOurs() {
        GeneralVariables.myCallsign = "K1AF";
        Ft8Message echo = new Ft8Message("CQ", "K1AF", "FN42");
        echo.isOwnEcho = true;
        assertThat(MainViewModel.isOurOwnRow(echo)).isTrue();
    }

    @Test
    public void exactCallsignIsOurs() {
        GeneralVariables.myCallsign = "K1AF";
        assertThat(MainViewModel.isOurOwnRow(new Ft8Message("CQ", "K1AF", "FN42"))).isTrue();
    }

    @Test
    public void baseCallOfACompoundCallIsOurs() {
        // Configured as K1AF/P, on the air (and echoing) as K1AF.
        GeneralVariables.myCallsign = "K1AF/P";
        assertThat(MainViewModel.isOurOwnRow(new Ft8Message("CQ", "K1AF", "FN42"))).isTrue();
    }

    @Test
    public void anotherStationIsNotOurs() {
        GeneralVariables.myCallsign = "K1AF";
        assertThat(MainViewModel.isOurOwnRow(new Ft8Message("CQ", "DL1ABC", "JO31"))).isFalse();
    }
}
