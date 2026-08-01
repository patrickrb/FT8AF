package com.k1af.ft8af.ft8listener;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.ft8listener.FastPassDisposition.Action;

import org.junit.Test;

/**
 * Unit tests for {@link FastPassDisposition} — what happens to a fast-pass decode that
 * arrives too late to act on this cycle.
 *
 * <p>The bug this closes: both late cases used to DROP the decode. Measured on the
 * 2026-07-31 activation, 34 of the 66 cycles where a station called us (52%) were
 * discarded — decoded correctly, sequencer never saw them, operator kept calling CQ at
 * people who were answering and had to pick callers by hand.
 *
 * <p>Pure JVM: no Android types.
 */
public class FastPassDispositionTest {

    private static final long BUDGET_MS = 2_000;

    @Test
    public void inTimeAndIdle_parses() {
        assertThat(FastPassDisposition.decide(false, 400, BUDGET_MS)).isEqualTo(Action.PARSE);
    }

    @Test
    public void exactlyAtBudget_stillParses() {
        // The budget is what it costs to still get a reply out; spending all of it is fine.
        assertThat(FastPassDisposition.decide(false, BUDGET_MS, BUDGET_MS)).isEqualTo(Action.PARSE);
    }

    @Test
    public void justOverBudget_stashesRatherThanDropping() {
        assertThat(FastPassDisposition.decide(false, BUDGET_MS + 1, BUDGET_MS))
                .isEqualTo(Action.STASH);
    }

    @Test
    public void alreadyKeyed_stashesRatherThanDropping() {
        // THE case. The fast decode is delivered ~earlyDecodeMillis + decode time into the
        // slot, so a ~2 s decode lands a few hundred ms past the boundary — and key-up
        // happens within the first half second. This is the reply to our CQ arriving
        // moments too late to act on, which is worth keeping, not discarding.
        assertThat(FastPassDisposition.decide(true, 200, BUDGET_MS)).isEqualTo(Action.STASH);
    }

    @Test
    public void alreadyKeyedBeatsAHealthyBudget() {
        // Ordering matters: once keyed, no decode however quick can change what is already
        // going out over the air, so the budget must not be able to override this.
        assertThat(FastPassDisposition.decide(true, 0, BUDGET_MS)).isEqualTo(Action.STASH);
    }

    @Test
    public void alreadyKeyedAndOverBudget_stashes() {
        assertThat(FastPassDisposition.decide(true, 9_000, BUDGET_MS)).isEqualTo(Action.STASH);
    }

    @Test
    public void nothingIsEverDropped() {
        // The whole point: every combination resolves to PARSE or STASH. There is no
        // third outcome, because a decoded reply is always worth acting on — this cycle
        // if we can, the next one if we can't.
        for (boolean tx : new boolean[] {false, true}) {
            for (long cost : new long[] {0, 400, BUDGET_MS, BUDGET_MS + 1, 30_000}) {
                Action a = FastPassDisposition.decide(tx, cost, BUDGET_MS);
                assertThat(a).isAnyOf(Action.PARSE, Action.STASH);
            }
        }
    }

    @Test
    public void aLargerLateStartToleranceWidensTheParseWindow() {
        // autoReplyBudgetMs is max(2000, lateStartTolerance), so raising the tolerance
        // should let a slower decode still reply in the same cycle.
        assertThat(FastPassDisposition.decide(false, 3_000, 2_000)).isEqualTo(Action.STASH);
        assertThat(FastPassDisposition.decide(false, 3_000, 4_000)).isEqualTo(Action.PARSE);
    }
}
