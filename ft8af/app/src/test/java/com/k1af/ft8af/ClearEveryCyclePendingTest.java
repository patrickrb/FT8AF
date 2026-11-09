package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link MainViewModel#shouldApplyPendingClear} — the gate that keeps
 * "clear every cycle" wiping only on the first decode pass of the slot the clear was
 * armed for. A straggling late/deep pass from an earlier slot must not consume the
 * next slot's clear (#976), or that slot's real decodes get appended onto the previous
 * slot's list (two slots mixed on screen).
 */
public class ClearEveryCyclePendingTest {

    // Representative monotonic slot utcs (ms), 15 s apart.
    private static final long SLOT_N = 1_700_000_000_000L;
    private static final long SLOT_N1 = SLOT_N + 15_000L;
    private static final long SLOT_N2 = SLOT_N + 30_000L;

    @Test
    public void firstPassOfArmedSlot_applies() {
        // beforeListen armed the clear for slot N; slot N's first pass applies it.
        assertThat(MainViewModel.shouldApplyPendingClear(SLOT_N, SLOT_N)).isTrue();
    }

    @Test
    public void stragglerFromPreviousSlot_doesNotConsumeNextSlotsClear() {
        // Clear armed for N+1 (beforeListen already ran for the new cycle); a straggling
        // late/deep pass from slot N arrives. It must NOT apply/consume the N+1 clear.
        assertThat(MainViewModel.shouldApplyPendingClear(SLOT_N1, SLOT_N)).isFalse();
    }

    @Test
    public void newerPassThanArmed_applies() {
        // Defensive: a pass from a slot newer than the armed one still clears.
        assertThat(MainViewModel.shouldApplyPendingClear(SLOT_N1, SLOT_N2)).isTrue();
    }

    @Test
    public void noClearPending_doesNotApply() {
        // Pending == 0 means no clear is armed (already consumed, or feature off this
        // cycle); a straggling deep pass must not clear.
        assertThat(MainViewModel.shouldApplyPendingClear(0L, SLOT_N)).isFalse();
    }

    @Test
    public void scenario_stragglerThenNextSlot_onlyNextSlotClears() {
        // Full #976 sequence: armed for N+1, straggler from N arrives first, then N+1's
        // real first pass. Only the latter consumes the clear.
        long pending = SLOT_N1;
        assertThat(MainViewModel.shouldApplyPendingClear(pending, SLOT_N)).isFalse(); // straggler
        assertThat(MainViewModel.shouldApplyPendingClear(pending, SLOT_N1)).isTrue(); // real pass
    }
}
