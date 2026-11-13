package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link ToastMessage}, covering the crash fixed in this PR: a
 * {@code null} debug message (e.g. from {@code ToastMessage.show(e.getMessage())}
 * where {@link Throwable#getMessage()} returns {@code null}) used to be stored in
 * the internal list, and the delayed cleanup runnable then NPE'd calling
 * {@code debugList.get(i).equals(info)} on the null element (Sentry FT8AF-3).
 */
@RunWith(RobolectricTestRunner.class)
public class ToastMessageTest {

    @Test
    public void removeFirstMatch_removesMatchingEntry() {
        List<String> list = new ArrayList<>();
        list.add("a");
        list.add("b");
        list.add("c");

        assertThat(ToastMessage.removeFirstMatch(list, "b")).isTrue();
        assertThat(list).containsExactly("a", "c").inOrder();
    }

    @Test
    public void removeFirstMatch_removesOnlyFirstMatch() {
        List<String> list = new ArrayList<>();
        list.add("dup");
        list.add("dup");

        assertThat(ToastMessage.removeFirstMatch(list, "dup")).isTrue();
        assertThat(list).containsExactly("dup");
    }

    @Test
    public void removeFirstMatch_noMatchReturnsFalse() {
        List<String> list = new ArrayList<>();
        list.add("a");

        assertThat(ToastMessage.removeFirstMatch(list, "zzz")).isFalse();
        assertThat(list).containsExactly("a");
    }

    /**
     * The regression: a null element in the list must not crash the match/remove
     * scan. Before the fix, {@code list.get(i).equals(info)} threw NPE here.
     */
    @Test
    public void removeFirstMatch_nullElementInListDoesNotCrash() {
        List<String> list = new ArrayList<>();
        list.add(null);
        list.add("real");

        // Scanning past the null element to find "real" must not NPE.
        assertThat(ToastMessage.removeFirstMatch(list, "real")).isTrue();
        assertThat(list).containsExactly((Object) null);
    }

    @Test
    public void removeFirstMatch_nullInfoRemovesNullElement() {
        List<String> list = new ArrayList<>();
        list.add("real");
        list.add(null);

        assertThat(ToastMessage.removeFirstMatch(list, null)).isTrue();
        assertThat(list).containsExactly("real");
    }

    /**
     * End-to-end regression guard: before the fix, {@code show(null)} stored a
     * null in debugList and scheduled a +5 s cleanup runnable that NPE'd on
     * {@code debugList.get(i).equals(info)}. Driving the main looper past the
     * cleanup delay must not throw.
     */
    @Test
    public void show_nullMessageCleanupDoesNotCrash() {
        ToastMessage.show(null);
        ToastMessage.show("visible");

        // Run every delayed cleanup runnable (5 s each) on the paused main looper.
        shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS);

        // Reaching here without an exception is the assertion.
    }
}
