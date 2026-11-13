package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit coverage for {@link LogFragment#isContextPositionInRange(int, int)}, the
 * bounds guard that keeps the QSO-logbook context menu from indexing the adapter
 * with a stale position.
 *
 * <p>The context menu stores the row's adapter position in the menu item's tag
 * when it is built (long-press). By the time the user taps a menu action the row
 * may have detached ({@code getAdapterPosition() == RecyclerView.NO_POSITION ==
 * -1}) or the backing list may have shrunk under a refresh (web import /
 * {@code notifyDataSetChanged}); using that position unguarded threw
 * {@code IndexOutOfBoundsException} on the main thread. Pure JVM — the helper is
 * a plain integer range check with no Android types involved.
 */
public class LogFragmentContextPositionTest {

    @Test
    public void positionWithinRange_isValid() {
        assertThat(LogFragment.isContextPositionInRange(0, 5)).isTrue();
        assertThat(LogFragment.isContextPositionInRange(4, 5)).isTrue();
    }

    @Test
    public void noPositionSentinel_isRejected() {
        // RecyclerView.NO_POSITION for a detached / mid-animation holder.
        assertThat(LogFragment.isContextPositionInRange(-1, 5)).isFalse();
    }

    @Test
    public void negativePosition_isRejected() {
        assertThat(LogFragment.isContextPositionInRange(-7, 5)).isFalse();
    }

    @Test
    public void positionPastEndAfterListShrank_isRejected() {
        // Captured at index 4, list later refreshed down to 3 rows.
        assertThat(LogFragment.isContextPositionInRange(4, 3)).isFalse();
        // Exactly one past the end.
        assertThat(LogFragment.isContextPositionInRange(5, 5)).isFalse();
    }

    @Test
    public void anyPositionAgainstEmptyList_isRejected() {
        assertThat(LogFragment.isContextPositionInRange(0, 0)).isFalse();
        assertThat(LogFragment.isContextPositionInRange(-1, 0)).isFalse();
    }
}
