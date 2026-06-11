package com.bg7yoz.ft8cn.ui;

import static com.google.common.truth.Truth.assertThat;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

/**
 * Unit tests for {@link WaterfallLabelMessages#afterPass}, the per-pass rule for
 * the waterfall label overlay. The fix for "decodes repeat on the waterfall and
 * never disappear" (worst on FT4/FT2's short slots): a silent normal pass must
 * clear the overlay so the previous slot's labels aren't re-stamped, while an
 * empty deep pass must not wipe what the normal pass found.
 */
@RunWith(RobolectricTestRunner.class)
public class WaterfallLabelMessagesTest {

    private ArrayList<Ft8Message> listOf(int n) {
        ArrayList<Ft8Message> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Ft8Message(FT8Common.FT8_MODE));
        }
        return list;
    }

    @Test
    public void normalPass_empty_clearsTheOverlay() {
        ArrayList<Ft8Message> previous = listOf(3);
        ArrayList<Ft8Message> result =
                WaterfallLabelMessages.afterPass(previous, new ArrayList<>(), false);
        assertThat(result).isEmpty();
    }

    @Test
    public void normalPass_nonEmpty_becomesTheOverlay() {
        ArrayList<Ft8Message> previous = listOf(3);
        ArrayList<Ft8Message> kept = listOf(2);
        ArrayList<Ft8Message> result =
                WaterfallLabelMessages.afterPass(previous, kept, false);
        assertThat(result).isSameInstanceAs(kept);
        assertThat(result).hasSize(2);
    }

    @Test
    public void deepPass_empty_keepsThePreviousOverlay() {
        // A deep decode re-runs the same slot's audio; an empty result must not
        // erase the labels the normal pass already stamped.
        ArrayList<Ft8Message> previous = listOf(3);
        ArrayList<Ft8Message> result =
                WaterfallLabelMessages.afterPass(previous, new ArrayList<>(), true);
        assertThat(result).isSameInstanceAs(previous);
    }

    @Test
    public void deepPass_nonEmpty_mergesOntoThePreviousOverlay() {
        // The listener dedups each deep pass against the earlier passes
        // (addMsgToList), so `kept` is only the deep pass's *new* decodes. They
        // must augment the normal pass's labels, not replace them.
        ArrayList<Ft8Message> previous = listOf(1);
        ArrayList<Ft8Message> kept = listOf(4);
        ArrayList<Ft8Message> result =
                WaterfallLabelMessages.afterPass(previous, kept, true);
        assertThat(result).hasSize(5);
        assertThat(result).containsAtLeastElementsIn(previous);
        assertThat(result).containsAtLeastElementsIn(kept);
    }

    @Test
    public void deepPass_nonEmpty_withNullPrevious_isJustTheDeepResult() {
        // A deep pass that fires before any overlay exists (null previous) must
        // not NPE; it simply becomes the deep result.
        ArrayList<Ft8Message> kept = listOf(2);
        ArrayList<Ft8Message> result =
                WaterfallLabelMessages.afterPass(null, kept, true);
        assertThat(result).containsExactlyElementsIn(kept);
    }

    @Test
    public void normalPass_empty_withNullPrevious_isEmptyNotNull() {
        ArrayList<Ft8Message> result =
                WaterfallLabelMessages.afterPass(null, new ArrayList<>(), false);
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    public void deepPass_empty_withNullPrevious_staysNull() {
        ArrayList<Ft8Message> result =
                WaterfallLabelMessages.afterPass(null, new ArrayList<>(), true);
        assertThat(result).isNull();
    }
}
