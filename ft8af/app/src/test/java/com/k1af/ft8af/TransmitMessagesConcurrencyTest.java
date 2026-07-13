package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coverage for the thread-safety of {@link GeneralVariables#transmitMessages}, the
 * calling-UI "followed entries" list.
 *
 * <p>That list is mutated with no external lock from three threads at once: the decode
 * thread ({@code MainViewModel.findIncludedCallsigns} appends matches then trims the
 * front via {@link GeneralVariables#deleteArrayListMore} {@code remove(0)}, and
 * {@code clearTransmittingMessage} clears it), the TX-sequencer thread
 * ({@code FT8TransmitSignal.doComplete} reverse-scans it for the QSO signal reports,
 * {@code onBeforeTransmit} appends), and the UI thread ({@code GridTrackerMainActivity}
 * / {@code GridMarkerInfoWindow} append). When it was a plain {@code ArrayList} that
 * contention corrupted its backing array and the reverse {@code size()}-then-{@code get(i)}
 * scan threw {@link IndexOutOfBoundsException} on the TX thread. It is now a
 * {@link CopyOnWriteArrayList} (atomic add/remove/clear) and the scan snapshots the list
 * before indexing it.
 *
 * <p>Robolectric is only needed to construct real {@link Ft8Message} rows (their ctor
 * touches {@code android.util.Log}); the list operations under test touch no Android types.
 */
@RunWith(RobolectricTestRunner.class)
public class TransmitMessagesConcurrencyTest {

    private static List<Ft8Message> messages(int n) {
        List<Ft8Message> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Ft8Message(FT8Common.FT8_MODE));
        }
        return list;
    }

    /** Reproduce the production reverse scan from {@code FT8TransmitSignal.doComplete}. */
    private static void reverseScanLikeDoComplete(List<Ft8Message> shared) {
        List<Ft8Message> snapshot = new ArrayList<>(shared);
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Ft8Message message = snapshot.get(i);
            // touch a field, exactly as doComplete dereferences message.extraInfo etc.
            if (message == null) {
                throw new AssertionError("snapshot yielded a null element");
            }
        }
    }

    @Before
    public void resetList() {
        GeneralVariables.transmitMessages.clear();
    }

    @After
    public void clearList() {
        GeneralVariables.transmitMessages.clear();
    }

    @Test
    public void transmitMessages_isCopyOnWriteList() {
        // A plain ArrayList cannot be shared across the decode / TX / UI threads without
        // external locking; the field must be a thread-safe list.
        assertThat(GeneralVariables.transmitMessages).isInstanceOf(CopyOnWriteArrayList.class);
    }

    @Test
    public void reverseScanSnapshot_survivesConcurrentDecodeMutation() throws InterruptedException {
        GeneralVariables.transmitMessages.addAll(messages(64));
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final int iterations = 20_000;

        // Mirror the decode / UI writers: append, trim the front, and occasionally clear.
        Thread mutator = new Thread(() -> {
            try {
                for (int i = 0; i < iterations; i++) {
                    GeneralVariables.transmitMessages.add(new Ft8Message(FT8Common.FT8_MODE));
                    GeneralVariables.deleteArrayListMore(GeneralVariables.transmitMessages);
                    if (i % 128 == 0) {
                        GeneralVariables.transmitMessages.clear();
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        mutator.start();
        try {
            // Mirror the TX-sequencer thread reading the same shared list.
            for (int i = 0; i < iterations; i++) {
                reverseScanLikeDoComplete(GeneralVariables.transmitMessages);
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
        mutator.join();

        assertThat(failure.get()).isNull();
    }

    @Test
    public void directSizeThenGet_onShrunkList_throws_butSnapshotIsImmune() {
        // The pre-fix hazard, made deterministic: the reverse scan captured size() and then
        // indexed the live list; a concurrent trim/clear between the two shrank it, so the
        // stale top index was out of range.
        List<Ft8Message> live = new ArrayList<>(messages(3));
        int staleSize = live.size();            // TX thread saw size == 3
        live.clear();                           // decode thread cleared the list in the window
        assertThrows(IndexOutOfBoundsException.class, () -> live.get(staleSize - 1));

        // The fix snapshots the list first, decoupling the scan from later mutation.
        List<Ft8Message> live2 = new ArrayList<>(messages(3));
        List<Ft8Message> snapshot = new ArrayList<>(live2);
        live2.clear();                          // mutate the shared list after the snapshot
        assertThat(snapshot).hasSize(3);
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            assertThat(snapshot.get(i)).isNotNull();   // never throws
        }
    }
}
