package com.bg7yoz.ft8cn;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link MainViewModel#callStationOrder} — the logic that picks
 * function order 1 (initiate with grid) vs -1 (auto-derive, reply with RPT)
 * when the user taps a decoded message.
 */
public class CallStationOrderTest {

    private String savedCallsign;

    @Before
    public void setUp() {
        savedCallsign = GeneralVariables.myCallsign;
        GeneralVariables.myCallsign = "W1AW";
    }

    @After
    public void tearDown() {
        GeneralVariables.myCallsign = savedCallsign;
    }

    @Test
    public void messageDirectedAtMe_autoDerives() {
        // Someone answered our CQ: "THEM W1AW EM73" — callsignTo is our call
        int order = MainViewModel.callStationOrder("W1AW", "EM73");
        assertThat(order).isEqualTo(-1);
    }

    @Test
    public void messageNotDirectedAtMe_startsAtOrder1() {
        // A CQ or message to someone else — we initiate with grid
        int order = MainViewModel.callStationOrder("K5ABC", "EM73");
        assertThat(order).isEqualTo(1);
    }

    @Test
    public void cqMessage_startsAtOrder1() {
        // CQ broadcast — callsignTo is empty after getCallsignTo() strips "CQ"
        int order = MainViewModel.callStationOrder("", "EM73");
        assertThat(order).isEqualTo(1);
    }

    @Test
    public void nullCallsignTo_startsAtOrder1() {
        int order = MainViewModel.callStationOrder(null, "EM73");
        assertThat(order).isEqualTo(1);
    }

    @Test
    public void compoundCallsign_matchesShortForm() {
        // Our callsign with a portable suffix — checkIsMyCallsign uses contains()
        GeneralVariables.myCallsign = "W1AW/P";
        int order = MainViewModel.callStationOrder("W1AW", "EM73");
        assertThat(order).isEqualTo(-1);
    }
}
