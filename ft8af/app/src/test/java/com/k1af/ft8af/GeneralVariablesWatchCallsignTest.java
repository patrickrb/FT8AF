package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the callsign watchlist matcher used by the "Watchlist" alert
 * ({@link com.k1af.ft8af.alert.DxAlertNotifier}). Entries match by callsign
 * PREFIX so a DXpedition prefix (e.g. "3Y0") catches every variant it decodes
 * and a full call ("W1AW") still matches a portable suffix ("W1AW/P").
 */
@RunWith(RobolectricTestRunner.class)
public class GeneralVariablesWatchCallsignTest {

    @Before
    public void setUp() {
        GeneralVariables.addWatchCallsigns("");
    }

    @After
    public void tearDown() {
        GeneralVariables.addWatchCallsigns("");
    }

    @Test
    public void emptyWatchlist_matchesNothing_andReportsEmpty() {
        assertThat(GeneralVariables.hasWatchCallsigns()).isFalse();
        assertThat(GeneralVariables.checkIsWatchedCallsign("3Y0J")).isFalse();
    }

    @Test
    public void addAndGet_roundTripsCanonicalCsv() {
        GeneralVariables.addWatchCallsigns("3y0, w1aw");
        assertThat(GeneralVariables.hasWatchCallsigns()).isTrue();
        assertThat(GeneralVariables.getWatchCallsigns()).isEqualTo("3Y0,W1AW");
    }

    @Test
    public void prefixMatch_catchesDxpeditionVariants() {
        GeneralVariables.addWatchCallsigns("3Y0");
        assertThat(GeneralVariables.checkIsWatchedCallsign("3Y0J")).isTrue();
        assertThat(GeneralVariables.checkIsWatchedCallsign("3Y0J/MM")).isTrue();
        // Same prefix, different station still counts — that's the point of a prefix watch.
        assertThat(GeneralVariables.checkIsWatchedCallsign("3Y0K")).isTrue();
        // A different entity must not match.
        assertThat(GeneralVariables.checkIsWatchedCallsign("W1AW")).isFalse();
    }

    @Test
    public void fullCall_stillMatchesPortableSuffix() {
        GeneralVariables.addWatchCallsigns("W1AW");
        assertThat(GeneralVariables.checkIsWatchedCallsign("W1AW")).isTrue();
        assertThat(GeneralVariables.checkIsWatchedCallsign("W1AW/P")).isTrue();
        // Must not match a different call that merely shares a leading substring in
        // the middle — the match is anchored at the start.
        assertThat(GeneralVariables.checkIsWatchedCallsign("KW1AW")).isFalse();
    }

    @Test
    public void matcher_isCaseInsensitive() {
        GeneralVariables.addWatchCallsigns("tx7");
        assertThat(GeneralVariables.checkIsWatchedCallsign("TX7L")).isTrue();
    }

    @Test
    public void nullCallsign_isSafe() {
        GeneralVariables.addWatchCallsigns("W1AW");
        assertThat(GeneralVariables.checkIsWatchedCallsign(null)).isFalse();
    }
}
