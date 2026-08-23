package com.k1af.ft8af.ft8listener;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.ft8listener.FastPassDisposition.Action;

import org.junit.Test;

/**
 * Unit tests for {@link FastPassDisposition}: every fast-pass delivery is either this
 * cycle's authoritative parse or an immediate evidence-only parse — nothing is dropped,
 * and nothing is deferred to a later delivery.
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
    public void justOverBudget_isEvidenceRatherThanDropped() {
        assertThat(FastPassDisposition.decide(false, BUDGET_MS + 1, BUDGET_MS))
                .isEqualTo(Action.EVIDENCE_ONLY);
    }

    @Test
    public void alreadyKeyed_isEvidenceRatherThanDropped() {
        // THE case. The fast decode is delivered ~earlyDecodeMillis + decode time into the
        // slot, so a ~2 s decode lands a few hundred ms past the boundary — and key-up
        // happens within the first half second. This is the partner's R-report arriving
        // moments after we keyed up with the old message: parsed now, it advances the QSO
        // while the old over is still on the air, and the mid-cycle swap can replace it.
        assertThat(FastPassDisposition.decide(true, 200, BUDGET_MS)).isEqualTo(Action.EVIDENCE_ONLY);
    }

    @Test
    public void alreadyKeyedBeatsAHealthyBudget() {
        // Ordering matters: once keyed, this cycle's decision has been made; the budget must
        // not be able to promote a late decode to an authoritative pass.
        assertThat(FastPassDisposition.decide(true, 0, BUDGET_MS)).isEqualTo(Action.EVIDENCE_ONLY);
    }

    @Test
    public void alreadyKeyedAndOverBudget_isEvidence() {
        assertThat(FastPassDisposition.decide(true, 9_000, BUDGET_MS)).isEqualTo(Action.EVIDENCE_ONLY);
    }

    @Test
    public void nothingIsEverDroppedOrDeferred() {
        // The whole point: every combination resolves to one of the two immediate parses.
        // There is no "later" outcome — a decoded reply is always acted on the moment it
        // lands, either as this cycle's decision or as evidence amending it.
        for (boolean tx : new boolean[] {false, true}) {
            for (long cost : new long[] {0, 400, BUDGET_MS, BUDGET_MS + 1, 30_000}) {
                Action a = FastPassDisposition.decide(tx, cost, BUDGET_MS);
                assertThat(a).isAnyOf(Action.PARSE, Action.EVIDENCE_ONLY);
            }
        }
    }

    @Test
    public void aLargerLateStartToleranceWidensTheParseWindow() {
        // autoReplyBudgetMs is max(2000, lateStartTolerance), so raising the tolerance
        // should let a slower decode still reply in the same cycle.
        assertThat(FastPassDisposition.decide(false, 3_000, 2_000)).isEqualTo(Action.EVIDENCE_ONLY);
        assertThat(FastPassDisposition.decide(false, 3_000, 4_000)).isEqualTo(Action.PARSE);
    }
}
