package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Regression coverage for the logbook swipe callbacks acting on a stale row position.
 *
 * <p>Swipe callbacks fire asynchronously (the END-delete path waits on a confirmation
 * dialog), so by the time one runs the {@code ViewHolder} may have detached
 * ({@code getAdapterPosition() == NO_POSITION == -1}) or the backing list may have shrunk
 * below the swiped position. The original fix guarded only {@code NO_POSITION}; an
 * out-of-range position still reached {@code getRecord(position)} (IndexOutOfBounds) and
 * {@code notifyItem*(position)} (RecyclerView inconsistency). {@link
 * LogFragment#isSwipePositionActionable(int, int)} is the extracted decision so it can be
 * driven without a RecyclerView.
 */
public class LogFragmentSwipePositionTest {

    @Test
    public void noPositionSentinel_isNotActionable() {
        // RecyclerView.NO_POSITION == -1
        assertThat(LogFragment.isSwipePositionActionable(-1, 5)).isFalse();
    }

    @Test
    public void positionAtOrBeyondItemCount_isNotActionable() {
        // List shrank while the confirm dialog was open: position now == size (out of bounds).
        assertThat(LogFragment.isSwipePositionActionable(5, 5)).isFalse();
        assertThat(LogFragment.isSwipePositionActionable(9, 5)).isFalse();
    }

    @Test
    public void emptyList_isNeverActionable() {
        assertThat(LogFragment.isSwipePositionActionable(0, 0)).isFalse();
    }

    @Test
    public void inRangePositions_areActionable() {
        assertThat(LogFragment.isSwipePositionActionable(0, 5)).isTrue();
        assertThat(LogFragment.isSwipePositionActionable(4, 5)).isTrue();
    }
}
