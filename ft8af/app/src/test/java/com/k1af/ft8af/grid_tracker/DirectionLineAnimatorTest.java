package com.k1af.ft8af.grid_tracker;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Lifecycle tests for {@link DirectionLineAnimator}, the animator extracted from
 * {@code GridOsmMapView.GridPolyLine} so its cancellation can be verified without an
 * osmdroid MapView.
 *
 * <p>The bug these pin: the direction animator used to be started and never
 * cancelled, so every removed line left a running INFINITE animator behind (a leak +
 * a ~60 Hz repaint that never stops). {@link DirectionLineAnimator#stop()} must turn
 * a running animator off — that is what {@code clearLines()} now calls for each line
 * it drops. {@code android.animation.ValueAnimator} is an Android type, hence
 * Robolectric.
 */
@RunWith(RobolectricTestRunner.class)
public class DirectionLineAnimatorTest {

    @Test
    public void newAnimator_isNotRunningUntilStarted() {
        DirectionLineAnimator anim = new DirectionLineAnimator(1000, fraction -> { });
        // Construction alone must not start the animation.
        assertThat(anim.isRunning()).isFalse();
    }

    @Test
    public void start_thenStop_leavesAnimatorNotRunning() {
        DirectionLineAnimator anim = new DirectionLineAnimator(1000, fraction -> { });
        anim.start();
        assertThat(anim.isRunning()).isTrue();
        // The fix: stopping the line's animator must actually cancel the infinite
        // animation (before this it ran forever after the line was removed).
        anim.stop();
        assertThat(anim.isRunning()).isFalse();
    }

    @Test
    public void stop_isIdempotent() {
        DirectionLineAnimator anim = new DirectionLineAnimator(1000, fraction -> { });
        anim.start();
        anim.stop();
        // clearLines() may reach a line whose animation is already stopped; a second
        // stop() must be a harmless no-op, never an exception.
        anim.stop();
        assertThat(anim.isRunning()).isFalse();
    }

    @Test
    public void stop_beforeStart_isSafe() {
        DirectionLineAnimator anim = new DirectionLineAnimator(1000, fraction -> { });
        // A line removed before its animation ever ran (or a QSL line routed here in
        // future) must tolerate stop() without starting.
        anim.stop();
        assertThat(anim.isRunning()).isFalse();
    }
}
