package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The regression behind issue #782, exercised on the real views: {@code setTouch_x}
 * must make {@code getFreq_hz()} answer for the <em>new</em> touch immediately,
 * without waiting for the next {@code onDraw()}. The tap handlers read the
 * frequency right after the setter (and commit it on ACTION_UP), so a view that
 * still derived it in {@code onDraw()} handed them the previous frame's value —
 * the "blue line not in the middle of the red ones" report. Both views are laid
 * out for real here so {@code getWidth()} is the number the math uses; no draw
 * pass ever runs in these tests, which is the point.
 */
@RunWith(RobolectricTestRunner.class)
public class SpectrumViewTouchTest {

    private static final int WIDTH_PX = 1000;
    private static final int HEIGHT_PX = 200;
    private static final int SPECTRUM_HZ = 3500;

    private static <V extends View> V laidOut(V view) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, WIDTH_PX, HEIGHT_PX);
        assertThat(view.getWidth()).isEqualTo(WIDTH_PX);
        return view;
    }

    private static ColumnarView columnar() {
        Context context = ApplicationProvider.getApplicationContext();
        ColumnarView v = laidOut(new ColumnarView(context));
        v.setSpectrumWidth(SPECTRUM_HZ);
        return v;
    }

    private static WaterfallView waterfall() {
        Context context = ApplicationProvider.getApplicationContext();
        WaterfallView v = laidOut(new WaterfallView(context));
        v.setSpectrumWidth(SPECTRUM_HZ);
        return v;
    }

    // -- ColumnarView ---------------------------------------------------------

    @Test
    public void columnar_setTouchX_updatesFreqHzImmediately() {
        ColumnarView v = columnar();
        v.setTouch_x(500);
        assertThat(v.getFreq_hz()).isEqualTo(1750);
        // A second touch, still with no draw in between, must not return the
        // first one's value.
        v.setTouch_x(250);
        assertThat(v.getFreq_hz()).isEqualTo(875);
    }

    @Test
    public void columnar_leftEdgeTouch_clampsToMinTxAudio() {
        ColumnarView v = columnar();
        v.setTouch_x(0);
        assertThat(v.getFreq_hz()).isEqualTo(SpectrumTouchMath.MIN_TX_AUDIO_HZ);
    }

    @Test
    public void columnar_clearAndOffView_reportMinusOne() {
        ColumnarView v = columnar();
        v.setTouch_x(500);
        v.setTouch_x(-1); // the timeout's clear
        assertThat(v.getFreq_hz()).isEqualTo(-1);
        v.setTouch_x(WIDTH_PX + 1); // drag ran off the right edge
        assertThat(v.getFreq_hz()).isEqualTo(-1);
    }

    @Test
    public void columnar_beforeLayout_reportsMinusOne() {
        Context context = ApplicationProvider.getApplicationContext();
        ColumnarView v = new ColumnarView(context);
        v.setSpectrumWidth(SPECTRUM_HZ);
        v.setTouch_x(500);
        assertThat(v.getFreq_hz()).isEqualTo(-1);
    }

    // -- WaterfallView --------------------------------------------------------

    @Test
    public void waterfall_setTouchX_updatesFreqHzImmediately() {
        WaterfallView v = waterfall();
        v.setTouch_x(500);
        assertThat(v.getFreq_hz()).isEqualTo(1750);
        v.setTouch_x(250);
        assertThat(v.getFreq_hz()).isEqualTo(875);
    }

    @Test
    public void waterfall_leftEdgeTouch_clampsToMinTxAudio() {
        WaterfallView v = waterfall();
        v.setTouch_x(0);
        assertThat(v.getFreq_hz()).isEqualTo(SpectrumTouchMath.MIN_TX_AUDIO_HZ);
    }

    @Test
    public void waterfall_clearAndOffView_reportMinusOne() {
        WaterfallView v = waterfall();
        v.setTouch_x(500);
        v.setTouch_x(-1);
        assertThat(v.getFreq_hz()).isEqualTo(-1);
        v.setTouch_x(WIDTH_PX + 1);
        assertThat(v.getFreq_hz()).isEqualTo(-1);
    }

    @Test
    public void waterfall_beforeLayout_reportsMinusOne() {
        Context context = ApplicationProvider.getApplicationContext();
        WaterfallView v = new WaterfallView(context);
        v.setSpectrumWidth(SPECTRUM_HZ);
        v.setTouch_x(500);
        assertThat(v.getFreq_hz()).isEqualTo(-1);
    }
}
