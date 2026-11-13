package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link LogQSLAdapter}'s position-bounds guards.
 *
 * <p>The QSO logbook's swipe gestures (see {@code LogFragment.onSwiped}) call the
 * adapter's mutators with {@code viewHolder.getAdapterPosition()}, which RecyclerView
 * returns as {@link androidx.recyclerview.widget.RecyclerView#NO_POSITION} (-1) when the
 * holder has detached or the list refreshed under a swipe / open confirm-dialog. Before
 * the guards, {@code deleteRecord(-1)} / {@code setRecordIsQSL(-1, ...)} indexed
 * {@code qslRecords.get(-1)} and threw {@link IndexOutOfBoundsException} on the main
 * thread → whole-app crash. The sibling {@code CallingListAdapter} already guards -1;
 * this test pins the equivalent guard for {@code LogQSLAdapter}.
 *
 * <p>Pure JUnit: the adapter's ctor only stores its args and the guard paths early-return
 * before touching the (here null) MainViewModel/Context, and records are added directly to
 * the exposed backing list so no {@code notifyDataSetChanged()} is needed.
 */
public class LogQSLAdapterPositionGuardTest {

    private static LogQSLAdapter adapterWith(int rows) {
        LogQSLAdapter adapter = new LogQSLAdapter(null, null);
        for (int i = 0; i < rows; i++) {
            QSLRecordStr r = new QSLRecordStr();
            r.id = i;
            adapter.getRecords().add(r);
        }
        return adapter;
    }

    @Test
    public void isValidPosition_acceptsInRangeRejectsOutOfRange() {
        LogQSLAdapter adapter = adapterWith(2);
        assertThat(adapter.isValidPosition(0)).isTrue();
        assertThat(adapter.isValidPosition(1)).isTrue();

        assertThat(adapter.isValidPosition(-1)).isFalse(); // RecyclerView.NO_POSITION
        assertThat(adapter.isValidPosition(2)).isFalse();  // == size
        assertThat(adapter.isValidPosition(99)).isFalse();
    }

    @Test
    public void isValidPosition_emptyListRejectsEverything() {
        LogQSLAdapter adapter = adapterWith(0);
        assertThat(adapter.isValidPosition(0)).isFalse();
        assertThat(adapter.isValidPosition(-1)).isFalse();
    }

    @Test
    public void deleteRecord_noPositionIsNoOp_notCrash() {
        LogQSLAdapter adapter = adapterWith(3);
        // Pre-fix this did qslRecords.get(-1) and threw IndexOutOfBoundsException.
        adapter.deleteRecord(-1);
        adapter.deleteRecord(3);   // == size, also out of range
        adapter.deleteRecord(100);
        assertThat(adapter.getItemCount()).isEqualTo(3); // nothing removed
    }

    @Test
    public void setRecordIsQSL_noPositionIsNoOp_notCrash() {
        LogQSLAdapter adapter = adapterWith(2);
        // Pre-fix this indexed qslRecords.get(-1) (and would then dereference a null
        // MainViewModel); the guard returns before either.
        adapter.setRecordIsQSL(-1, true);
        adapter.setRecordIsQSL(2, true);
        // Neither existing record was flipped.
        assertThat(adapter.getRecords().get(0).isQSL).isFalse();
        assertThat(adapter.getRecords().get(1).isQSL).isFalse();
    }
}
