package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;

/**
 * Tests {@link MainViewModel#messageAt} — the bounds-checked, lock-guarded read
 * behind {@link MainViewModel#getFt8MessageAtOrNull}.
 *
 * <p>The Grid Tracker calling-list item click used to do an unguarded
 * {@code size()}-check-then-{@code get(pos)} on the main thread against the live
 * {@code ft8Messages} list, which the decode thread mutates under
 * {@code synchronized (ft8Messages)} — appending decodes and trimming the front
 * with {@link GeneralVariables#deleteArrayListMore} ({@code remove(0)}), or
 * {@code clear()}ing it. A concurrent trim/clear between the check and the get
 * throws {@link IndexOutOfBoundsException} on the UI thread (whole-app crash).
 * {@code messageAt} makes the check-and-get atomic and returns {@code null} for a
 * position that is no longer valid.
 */
public class MainViewModelMessageAtTest {

    private static ArrayList<Ft8Message> listOf(int n) {
        ArrayList<Ft8Message> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Ft8Message("CQ", "K1AB" + i, "FN42"));
        }
        return list;
    }

    @Test
    public void inRange_returnsElement() {
        ArrayList<Ft8Message> list = listOf(3);
        assertThat(MainViewModel.messageAt(list, 0)).isSameInstanceAs(list.get(0));
        assertThat(MainViewModel.messageAt(list, 2)).isSameInstanceAs(list.get(2));
    }

    @Test
    public void negativePosition_returnsNull() {
        assertThat(MainViewModel.messageAt(listOf(3), -1)).isNull();
    }

    @Test
    public void positionEqualToSize_returnsNull() {
        assertThat(MainViewModel.messageAt(listOf(3), 3)).isNull();
    }

    @Test
    public void positionBeyondSize_returnsNull() {
        // Would have thrown IndexOutOfBoundsException with a raw get(position).
        assertThat(MainViewModel.messageAt(listOf(3), 9)).isNull();
    }

    @Test
    public void emptyList_returnsNull() {
        assertThat(MainViewModel.messageAt(new ArrayList<>(), 0)).isNull();
    }

    @Test
    public void stalePositionAfterFrontTrim_returnsNullInsteadOfThrowing() {
        // Reproduces the race outcome: the click handler captured position 4 while
        // the list held 5 messages; the decode thread then trimmed the front
        // (deleteArrayListMore -> remove(0)) down to 3. The old code's
        // ft8Messages.get(4) would throw; messageAt returns null.
        ArrayList<Ft8Message> list = listOf(5);
        int stalePosition = 4;
        while (list.size() > 3) {
            list.remove(0);
        }
        assertThat(MainViewModel.messageAt(list, stalePosition)).isNull();
    }
}
