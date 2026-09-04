package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link FullDuplexMonitor}, which puts our own transmission's
 * decodes back on screen for satellite operating without letting them reach
 * anything that acts on a decode.
 *
 * <p>{@link Ft8Message}'s three-arg constructor takes (to, from, extra), so
 * {@code new Ft8Message("RA3XYZ", MY_CALL, "R-10")} is a message we sent — the
 * echo of our own downlink.
 */
@RunWith(RobolectricTestRunner.class)
public class FullDuplexMonitorTest {

    private static final String MY_CALL = "UB8CSJ";

    @Before
    public void setUp() {
        GeneralVariables.myCallsign = MY_CALL;
    }

    @After
    public void tearDown() {
        GeneralVariables.myCallsign = "";
    }

    private Ft8Message ownEcho(String toCall) {
        return new Ft8Message(toCall, MY_CALL, "R-10");
    }

    private Ft8Message thirdParty(String toCall, String fromCall) {
        return new Ft8Message(toCall, fromCall, "RR73");
    }

    @Test
    public void displayList_offReturnsTheKeptListUntouched() {
        ArrayList<Ft8Message> kept = new ArrayList<>();
        kept.add(thirdParty("CQ", "DL1ABC"));
        List<Ft8Message> echoes = Collections.singletonList(ownEcho("RA3XYZ"));

        // Same instance, not just an equal one: the feature-off path must not
        // allocate, and the caller hands this exact list to the rest of the
        // decode pipeline.
        assertThat(FullDuplexMonitor.displayList(kept, echoes, false)).isSameInstanceAs(kept);
    }

    @Test
    public void displayList_onAppendsOurOwnEchoes() {
        Ft8Message other = thirdParty("CQ", "DL1ABC");
        Ft8Message mine = ownEcho("RA3XYZ");
        ArrayList<Ft8Message> kept = new ArrayList<>(Collections.singletonList(other));

        List<Ft8Message> display = FullDuplexMonitor.displayList(
                kept, Collections.singletonList(mine), true);

        assertThat(display).containsExactly(other, mine).inOrder();
        // The caller keeps using `kept` for the sequencer and the databases, so
        // merging must never mutate it.
        assertThat(kept).containsExactly(other);
    }

    @Test
    public void displayList_onWithNoEchoesReturnsTheKeptList() {
        ArrayList<Ft8Message> kept = new ArrayList<>();
        kept.add(thirdParty("CQ", "DL1ABC"));
        assertThat(FullDuplexMonitor.displayList(kept, new ArrayList<>(), true))
                .isSameInstanceAs(kept);
        assertThat(FullDuplexMonitor.displayList(kept, null, true)).isSameInstanceAs(kept);
    }

    @Test
    public void displayList_onSurfacesAnEchoOnlySlot() {
        // The normal TX slot on a quiet transponder: nothing else decoded, but our
        // own signal came back. That is not a silent slot.
        Ft8Message mine = ownEcho("RA3XYZ");
        List<Ft8Message> display = FullDuplexMonitor.displayList(
                new ArrayList<>(), Collections.singletonList(mine), true);
        assertThat(display).containsExactly(mine);
    }

    @Test
    public void withoutOwnCallsign_dropsOnlyOurTransmissions() {
        Ft8Message other = thirdParty("CQ", "DL1ABC");
        Ft8Message toUs = new Ft8Message(MY_CALL, "DL1ABC", "-12");
        Ft8Message mine = ownEcho("RA3XYZ");

        assertThat(FullDuplexMonitor.withoutOwnCallsign(Arrays.asList(other, toUs, mine)))
                .containsExactly(other, toUs).inOrder();
    }

    @Test
    public void withoutOwnCallsign_toleratesEmptyAndNull() {
        assertThat(FullDuplexMonitor.withoutOwnCallsign(null)).isEmpty();
        assertThat(FullDuplexMonitor.withoutOwnCallsign(new ArrayList<>())).isEmpty();
        assertThat(FullDuplexMonitor.withoutOwnCallsign(Collections.singletonList(null))).isEmpty();
    }
}
