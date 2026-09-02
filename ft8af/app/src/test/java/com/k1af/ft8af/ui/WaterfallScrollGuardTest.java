package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Guards {@link WaterfallView} against the one-block-tall scroll crash.
 *
 * <p>Each new spectrum row scrolls the accumulated waterfall down by {@code blockHeight}
 * and repaints the freed top strip. The scroll blit copies the region
 * {@code Bitmap.createBitmap(lastBitMap, 0, 0, drawWidth, drawHeight - blockHeight)}. When
 * the view is measured at {@code h == 1}, {@code onSizeChanged} clamps {@code blockHeight}
 * to 1 (it would otherwise be {@code 1 / (symbols * cycle) == 0}), so
 * {@code drawHeight - blockHeight == 0} and {@code createBitmap} throws
 * {@code IllegalArgumentException} ("height must be > 0"). {@code setWaveData} runs from the
 * audio LiveData observer with no surrounding try/catch, so this is a whole-app crash.
 *
 * <p>{@link WaterfallView#scrolledRegionHeight(int, int)} clamps the region height to a
 * non-negative value and the caller skips the blit when it is 0. The pure cases below pin
 * the geometry; the Robolectric case drives the real {@code onSizeChanged}/{@code
 * setWaveData} path (Robolectric's {@code Bitmap} enforces the same preconditions the device
 * does) and reproduces the crash before the fix.
 */
@RunWith(RobolectricTestRunner.class)
public class WaterfallScrollGuardTest {

    @Test
    public void scrolledRegionHeight_oneBlockTall_isZero() {
        // h == 1 -> blockHeight clamps to 1 -> nothing to scroll.
        assertThat(WaterfallView.scrolledRegionHeight(1, 1)).isEqualTo(0);
    }

    @Test
    public void scrolledRegionHeight_shorterThanBlock_clampsToZero() {
        // Defensive: never returns negative even if blockHeight somehow exceeds drawHeight.
        assertThat(WaterfallView.scrolledRegionHeight(1, 2)).isEqualTo(0);
    }

    @Test
    public void scrolledRegionHeight_normalHeight_leavesRoomToScroll() {
        // A normal-sized view: one block scrolls, the rest of the column is copied.
        assertThat(WaterfallView.scrolledRegionHeight(400, 2)).isEqualTo(398);
    }

    @Test
    public void setWaveData_oneRowTallView_doesNotThrow() {
        // Pre-fix: onSizeChanged(w, 1) leaves drawHeight == blockHeight == 1, and the first
        // setWaveData scroll blit calls createBitmap(..., height=0) -> IllegalArgumentException.
        Context context = ApplicationProvider.getApplicationContext();
        WaterfallView view = new WaterfallView(context);
        view.onSizeChanged(200, 1, 0, 0);

        int[] data = new int[200];
        for (int i = 0; i < data.length; i++) {
            data[i] = i % 256;
        }
        view.setWaveData(data, null);
    }

    @Test
    public void setWaveData_normalView_stillScrollsWithoutThrowing() {
        // Happy path is unchanged: a real size still copies the scrolled region.
        Context context = ApplicationProvider.getApplicationContext();
        WaterfallView view = new WaterfallView(context);
        view.onSizeChanged(200, 400, 0, 0);

        int[] data = new int[200];
        for (int i = 0; i < data.length; i++) {
            data[i] = i % 256;
        }
        view.setWaveData(data, null);
    }
}
