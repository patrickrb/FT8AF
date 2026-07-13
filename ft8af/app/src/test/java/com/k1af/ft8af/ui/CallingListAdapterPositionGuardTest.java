package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.FT8Common;
import com.k1af.ft8af.Ft8Message;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coverage for {@link CallingListAdapter}'s position-bounds and concurrency guards.
 *
 * <p>The legacy decode/call/grid-tracker list ({@code CallingListFragment},
 * {@code GridTrackerMainActivity}) hands the adapter the SAME live
 * {@code MainViewModel.ft8Messages} instance the decode thread mutates under
 * {@code synchronized (ft8Messages)} — it appends decodes, trims the front via
 * {@code GeneralVariables.deleteArrayListMore}, and clears the list every cycle.
 * RecyclerView reads {@code getItemCount()} and then indexes the list on the UI
 * thread from {@code onBindViewHolder} and the swipe/menu handlers, so a trim or
 * clear landing in that window turned a raw {@code get(position)} into an
 * {@link IndexOutOfBoundsException} on the main thread (whole-app crash). The
 * adapter now routes every indexed access through {@link CallingListAdapter#messageAtOrNull}
 * / {@link CallingListAdapter#isValidPosition}, fetching under the decode-thread lock
 * with a range check.
 *
 * <p>Robolectric is only needed to construct real {@link Ft8Message} rows; the guard
 * methods themselves touch no Android types (the ctor's Context/MainViewModel are
 * never dereferenced on these paths, so they are passed as {@code null}).
 */
@RunWith(RobolectricTestRunner.class)
public class CallingListAdapterPositionGuardTest {

    private static CallingListAdapter adapterWith(ArrayList<Ft8Message> list) {
        return new CallingListAdapter(null, null, list, CallingListAdapter.ShowMode.CALLING_LIST);
    }

    private static ArrayList<Ft8Message> messages(int n) {
        ArrayList<Ft8Message> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Ft8Message(FT8Common.FT8_MODE));
        }
        return list;
    }

    @Test
    public void isValidPosition_acceptsInRangeRejectsOutOfRange() {
        assertThat(CallingListAdapter.isValidPosition(0, 2)).isTrue();
        assertThat(CallingListAdapter.isValidPosition(1, 2)).isTrue();

        assertThat(CallingListAdapter.isValidPosition(-1, 2)).isFalse(); // RecyclerView.NO_POSITION
        assertThat(CallingListAdapter.isValidPosition(2, 2)).isFalse();  // == size
        assertThat(CallingListAdapter.isValidPosition(99, 2)).isFalse();
        assertThat(CallingListAdapter.isValidPosition(0, 0)).isFalse();  // empty list
    }

    @Test
    public void messageAtOrNull_returnsElementInRange() {
        ArrayList<Ft8Message> list = messages(3);
        CallingListAdapter adapter = adapterWith(list);
        assertThat(adapter.messageAtOrNull(0)).isSameInstanceAs(list.get(0));
        assertThat(adapter.messageAtOrNull(2)).isSameInstanceAs(list.get(2));
    }

    @Test
    public void messageAtOrNull_outOfRangeReturnsNull_notCrash() {
        CallingListAdapter adapter = adapterWith(messages(3));
        // Pre-fix onBindViewHolder / getMessageByViewHolder did a raw get() here and threw.
        assertThat(adapter.messageAtOrNull(-1)).isNull(); // NO_POSITION
        assertThat(adapter.messageAtOrNull(3)).isNull();  // == size, stale after a front trim
        assertThat(adapter.messageAtOrNull(99)).isNull();
    }

    @Test
    public void messageAtOrNull_emptyListReturnsNull() {
        CallingListAdapter adapter = adapterWith(new ArrayList<>());
        assertThat(adapter.messageAtOrNull(0)).isNull();
        assertThat(adapter.messageAtOrNull(-1)).isNull();
    }

    @Test
    public void getMessageByPosition_delegatesToSafeFetch() {
        ArrayList<Ft8Message> list = messages(2);
        CallingListAdapter adapter = adapterWith(list);
        assertThat(adapter.getMessageByPosition(1)).isSameInstanceAs(list.get(1));
        assertThat(adapter.getMessageByPosition(-1)).isNull();
        assertThat(adapter.getMessageByPosition(2)).isNull();
    }

    @Test
    public void rowTagFor_boundRowKeepsItsPosition() {
        Ft8Message message = new Ft8Message(FT8Common.FT8_MODE);
        // A row backed by a message tags its own index so the click / long-press handlers
        // resolve the right message.
        assertThat(CallingListAdapter.rowTagFor(message, 0)).isEqualTo(0);
        assertThat(CallingListAdapter.rowTagFor(message, 7)).isEqualTo(7);
    }

    @Test
    public void rowTagFor_noRowUsesNoPositionSentinel() {
        // A recycled holder whose position no longer has a message (list trimmed/cleared
        // between getItemCount() and the bind) must carry NO_POSITION so a tap can't fire
        // against whatever message now occupies the stale index. The grid-tracker click
        // listener guards `position == -1` and the context menu fetches messageAtOrNull(-1).
        assertThat(CallingListAdapter.rowTagFor(null, 5)).isEqualTo(-1);
        assertThat(CallingListAdapter.rowTagFor(null, -1)).isEqualTo(-1);
    }

    @Test
    public void deleteMessage_outOfRangeIsNoOp_notCrash() {
        CallingListAdapter adapter = adapterWith(messages(3));
        // Pre-fix deleteMessage only guarded position >= 0, so an END-swipe on a holder
        // whose getAdapterPosition() equals the (since-shrunk) size did remove(size) and
        // threw IndexOutOfBoundsException on the main thread.
        adapter.deleteMessage(-1);
        adapter.deleteMessage(3);   // == size
        adapter.deleteMessage(100);
        assertThat(adapter.getItemCount()).isEqualTo(3); // nothing removed

        adapter.deleteMessage(0);   // valid: front removal still works
        assertThat(adapter.getItemCount()).isEqualTo(2);
    }

    /**
     * Hammer {@link CallingListAdapter#messageAtOrNull} from the "UI thread" while a
     * background thread continuously clears and refills the shared list under the same
     * monitor the decode thread uses. Pre-fix an unsynchronized range-check-then-get
     * raced the clear/refill and threw {@link IndexOutOfBoundsException}; the fetch must
     * now either return a live element or {@code null}, never throw.
     */
    @Test
    public void messageAtOrNull_isThreadSafeAgainstConcurrentClearAndRefill() throws InterruptedException {
        final ArrayList<Ft8Message> list = messages(64);
        final CallingListAdapter adapter = adapterWith(list);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final int iterations = 20_000;

        Thread mutator = new Thread(() -> {
            try {
                for (int i = 0; i < iterations; i++) {
                    // Mirror the decode thread: clear + repopulate under the list monitor.
                    synchronized (list) {
                        list.clear();
                        for (int k = 0; k < (i % 64); k++) {
                            list.add(new Ft8Message(FT8Common.FT8_MODE));
                        }
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        mutator.start();
        try {
            for (int i = 0; i < iterations; i++) {
                // Read across the whole plausible index range, including indices that are
                // valid one instant and out of range the next.
                adapter.messageAtOrNull(i % 64);
                adapter.getItemCount();
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
        mutator.join();

        assertThat(failure.get()).isNull();
    }
}
