package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link RigDialTarget} — the separation between the dial the app commands
 * and the dial it merely observes.
 *
 * <p>Reported as "it takes a loooong time for the radio to change frequencies". Measured:
 * the command itself was dispatched in 815 ms, but the rig rejected it ("?;", 29 times that
 * session), reported a frequency nobody had asked for, and the app then commanded that
 * value back — so a 30m selection took ~59 s and four taps to stick.
 */
public class RigDialTargetTest {

    private static final long M30 = 10_136_000L;
    private static final long M20 = 14_074_000L;
    /** The value the rig reported that nobody selected, from the 19:54 trace. */
    private static final long BOGUS = 14_239_985L;

    // ---- shouldAdoptAsTarget ------------------------------------------------

    @Test
    public void healthyStream_adoptsTheReportedDial() {
        // The normal case, and the reason this isn't simply "never trust the rig": this is
        // how the app follows the operator turning the VFO by hand.
        assertThat(RigDialTarget.shouldAdoptAsTarget(false, M20)).isTrue();
    }

    @Test
    public void desyncedStream_refusesTheReportedDial() {
        // The measured failure: a reading taken while the rig is rejecting our commands
        // must not become something we command back at it.
        assertThat(RigDialTarget.shouldAdoptAsTarget(true, BOGUS)).isFalse();
    }

    @Test
    public void desyncRefusesEvenAPlausibleLookingDial() {
        // The bogus value was in the same band as the real one, so plausibility is no
        // defence — only the stream state distinguishes them.
        assertThat(RigDialTarget.shouldAdoptAsTarget(true, M20)).isFalse();
    }

    @Test
    public void zeroOrNegativeIsNeverAdopted() {
        // rig.getFreq=0 shows up in the logs on a fresh connect, before any real read.
        assertThat(RigDialTarget.shouldAdoptAsTarget(false, 0)).isFalse();
        assertThat(RigDialTarget.shouldAdoptAsTarget(false, -1)).isFalse();
        assertThat(RigDialTarget.shouldAdoptAsTarget(true, 0)).isFalse();
    }

    // ---- dialToCommand ------------------------------------------------------

    @Test
    public void commandsTheChosenDialNotTheObservedOne() {
        // The 19:54:29 line that cost the operator the most: observed had been overwritten
        // with the bogus reading, chosen was still 30m. Chosen wins.
        assertThat(RigDialTarget.dialToCommand(M30, BOGUS)).isEqualTo(M30);
    }

    @Test
    public void fallsBackToObservedWhenNothingChosenYet() {
        // First run, or a config load predating commandedBandHz — behaviour must be
        // exactly as before until something is explicitly selected.
        assertThat(RigDialTarget.dialToCommand(0, M20)).isEqualTo(M20);
    }

    @Test
    public void agreementIsUnchanged() {
        assertThat(RigDialTarget.dialToCommand(M20, M20)).isEqualTo(M20);
    }

    // ---- the sequence that made a band change take a minute ------------------

    @Test
    public void theMeasuredBandChangeNowHolds() {
        // Operator taps 30m.
        long chosen = M30;
        // Rig rejects the set-frequency and reports something nobody asked for.
        boolean desynced = true;
        long observed = BOGUS;
        if (RigDialTarget.shouldAdoptAsTarget(desynced, observed)) {
            chosen = observed;// old behaviour: the bad reading became the target
        }
        // The reassert heartbeat fires.
        assertThat(RigDialTarget.dialToCommand(chosen, observed)).isEqualTo(M30);

        // Once the stream re-synchronises and the rig confirms 30m, nothing changes.
        if (RigDialTarget.shouldAdoptAsTarget(false, M30)) {
            chosen = M30;
        }
        assertThat(RigDialTarget.dialToCommand(chosen, M30)).isEqualTo(M30);
    }
}
