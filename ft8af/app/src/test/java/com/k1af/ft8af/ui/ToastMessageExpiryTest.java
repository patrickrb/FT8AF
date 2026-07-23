package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Coverage for {@link ToastMessage#expire}, the 5s cleanup pass.
 *
 * <p>Field crash (2026-07-23, app backgrounded): {@code FATAL EXCEPTION: main}
 * — NPE on {@code String.equals} at ToastMessage line 55, from
 * {@code debugList.get(i).equals(info)} finding a null slot. The expiry now
 * compares from the remembered message so a null element cannot crash the main
 * thread, and null messages are rejected on the way in.
 *
 * <p>Robolectric: posting to {@code GeneralVariables.mutableDebugMessage}
 * touches LiveData.
 */
@RunWith(RobolectricTestRunner.class)
public class ToastMessageExpiryTest {

    @Before
    public void setUp() {
        ToastMessage.clearAll();
    }

    @After
    public void tearDown() {
        ToastMessage.clearAll();
    }

    @Test
    public void expireRemovesTheMatchingEntry() {
        ToastMessage.show("alpha");
        ToastMessage.show("beta");
        ToastMessage.expire("alpha");
        assertThat(ToastMessage.snapshot()).containsExactly("beta");
    }

    @Test
    public void expireRemovesOnlyOneDuplicate() {
        // Two transmissions can raise the same text; each has its own 5s timer,
        // so one expiry must retire one entry.
        ToastMessage.show("dup");
        ToastMessage.show("dup");
        ToastMessage.expire("dup");
        assertThat(ToastMessage.snapshot()).containsExactly("dup");
    }

    @Test
    public void expireOfAnAbsentEntryIsHarmless() {
        ToastMessage.show("alpha");
        ToastMessage.expire("gone");
        assertThat(ToastMessage.snapshot()).containsExactly("alpha");
    }

    @Test
    public void expireOnEmptyListIsHarmless() {
        ToastMessage.expire("anything");
        assertThat(ToastMessage.snapshot()).isEmpty();
    }

    @Test
    public void nullMessageIsNotStored() {
        // The crash's root: a null reached the list and was dereferenced 5s later.
        ToastMessage.show(null);
        assertThat(ToastMessage.snapshot()).isEmpty();
    }

    @Test
    public void expireDoesNotCrashOnANullElement() throws Exception {
        // Defence in depth: even if a null slot exists (an older build stored
        // nulls), walking past it must not throw on the main thread. Force the
        // exact crash scenario by injecting a null ahead of a real entry, then
        // expire the real entry: the walk must pass the null slot and remove the
        // match. Comparing from the remembered message (info.equals(element))
        // rather than element.equals(info) is what makes this safe.
        injectNullSlot();
        ToastMessage.show("alpha");
        assertThat(ToastMessage.snapshot()).containsExactly(null, "alpha").inOrder();

        ToastMessage.expire("alpha");

        assertThat(ToastMessage.snapshot()).containsExactly((String) null);
    }

    @Test
    public void expireWithNullMessageIsHarmless() {
        // expire() is package-visible and can be called directly; a null argument
        // must no-op instead of NPEing on info.equals(...).
        ToastMessage.show("alpha");
        ToastMessage.expire(null);
        assertThat(ToastMessage.snapshot()).containsExactly("alpha");
    }

    /** Reflectively drop a null into the private static debugList to model an
     *  older build that stored nulls, so expire() truly walks a null slot. */
    @SuppressWarnings("unchecked")
    private static void injectNullSlot() throws Exception {
        Field f = ToastMessage.class.getDeclaredField("debugList");
        f.setAccessible(true);
        ((ArrayList<String>) f.get(null)).add(null);
    }

    @Test
    public void listIsTrimmedAboveSixEntries() {
        for (int i = 0; i < 9; i++) {
            ToastMessage.show("msg" + i);
        }
        // Overflow drops from the head, so the newest survive.
        assertThat(ToastMessage.snapshot()).hasSize(6);
        assertThat(ToastMessage.snapshot()).contains("msg8");
    }
}
