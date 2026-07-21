package com.k1af.ft8af.ui;

import static android.graphics.Bitmap.Config.ARGB_8888;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Guards {@link ColumnarView} — the Compose-hosted spectrum strip above the waterfall
 * ({@code WaterfallScreen} {@code ColumnarStrip}, an {@code AndroidView} laid out
 * {@code fillMaxWidth().height(96.dp)}) — against the two main-thread crashes its
 * identically hosted sibling {@link WaterfallView} already guards but ColumnarView did
 * not:
 *
 * <ol>
 *   <li>{@code onSizeChanged} called {@code Bitmap.createBitmap(w, h, ARGB_8888)} with no
 *       {@code w > 0 && h > 0} check → {@code IllegalArgumentException}
 *       ("width and height must be > 0") on a transient zero-dimension Compose
 *       measurement pass (WaterfallView:112 guards exactly this).</li>
 *   <li>{@code onDraw} dereferenced {@code _canvas}/{@code lastBitMap} (assigned only in
 *       {@code onSizeChanged}) with no null check → {@code NullPointerException} if a
 *       draw runs before a successful sizing, including right after guard (1) would have
 *       fired (WaterfallView:213 guards exactly this).</li>
 * </ol>
 *
 * <p>Both run on the UI-thread layout/draw traversal with no surrounding try/catch, so
 * either one is a whole-app crash. Robolectric (native graphics) reproduces the real
 * {@code Bitmap}/{@code Canvas} behaviour; the tests call the {@code protected}
 * {@code onSizeChanged}/{@code onDraw} directly (same package).
 */
@RunWith(RobolectricTestRunner.class)
public class ColumnarViewSizeGuardTest {

    private ColumnarView newView() {
        Context context = ApplicationProvider.getApplicationContext();
        return new ColumnarView(context);
    }

    private static Canvas scratchCanvas() {
        return new Canvas(Bitmap.createBitmap(64, 32, ARGB_8888));
    }

    @Test
    public void onSizeChanged_zeroWidth_doesNotThrow() {
        // Pre-fix: Bitmap.createBitmap(0, 32) throws IllegalArgumentException.
        newView().onSizeChanged(0, 32, 0, 0);
    }

    @Test
    public void onSizeChanged_zeroHeight_doesNotThrow() {
        // Pre-fix: Bitmap.createBitmap(64, 0) throws IllegalArgumentException.
        newView().onSizeChanged(64, 0, 0, 0);
    }

    @Test
    public void onSizeChanged_zeroBoth_doesNotThrow() {
        newView().onSizeChanged(0, 0, 0, 0);
    }

    @Test
    public void onDraw_beforeAnySizing_doesNotThrow() {
        // Pre-fix: _canvas is null (never sized) -> NPE on _canvas.drawColor(...).
        newView().onDraw(scratchCanvas());
    }

    @Test
    public void onDraw_afterZeroDimSizing_doesNotThrow() {
        // The zero-dim size pass leaves the view unsized; a following draw must not NPE.
        ColumnarView view = newView();
        view.onSizeChanged(0, 0, 0, 0);
        view.onDraw(scratchCanvas());
    }

    @Test
    public void onSizeChanged_thenOnDraw_positiveDims_drawsWithoutThrowing() {
        // Happy path is unchanged: a real size builds the bitmap and a draw succeeds.
        ColumnarView view = newView();
        view.layout(0, 0, 100, 50);
        view.onSizeChanged(100, 50, 0, 0);
        view.onDraw(scratchCanvas());
    }
}
