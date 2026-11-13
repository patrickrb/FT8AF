package com.k1af.ft8af.grid_tracker;

import android.animation.ValueAnimator;

/**
 * Owns the infinite "marching ants" {@link ValueAnimator} that animates the
 * direction highlight sweeping along a decoded {@code GridPolyLine}.
 *
 * <p>Extracted from {@code GridOsmMapView.GridPolyLine} so its lifecycle — in
 * particular that {@link #stop()} cancels the infinite animator when the line is
 * removed from the map — is unit-testable without an osmdroid {@code MapView}. The
 * per-frame drawing math (which slice of the line to paint) stays in the line via
 * the {@link OnProgress} callback; this class only owns the animator.
 *
 * <p><b>Why it exists.</b> The animator was previously a bare local: it was created
 * with {@link ValueAnimator#INFINITE} repeat, started, and then never referenced
 * again — so nothing ever cancelled it. The platform {@code AnimationHandler} keeps
 * a strong reference to every running animator, so each orphaned animator (a) could
 * never be garbage-collected, dragging its captured line and the whole
 * {@code MapView} with it, and (b) kept firing its update callback — which calls
 * {@code MapView.invalidate()} — roughly 60 times a second forever. The grid-tracker
 * map rebuilds its lines every decode cycle ({@code clearLines()} then
 * {@code drawLine()} per decode), so on a busy band the orphaned animators piled up
 * without bound: steadily rising CPU/redraw load and a monotonic memory leak that
 * ends in jank/ANR/OOM. Storing the animator here and cancelling it when the line
 * leaves the map fixes that.
 */
final class DirectionLineAnimator {

    /** Per-frame callback; {@code fraction} sweeps {@code 0f..1f} and repeats. */
    interface OnProgress {
        void onProgress(float fraction);
    }

    private final ValueAnimator animator;

    DirectionLineAnimator(long durationMs, final OnProgress onProgress) {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setDuration(durationMs);
        animator.setStartDelay(0);
        animator.addUpdateListener(a -> onProgress.onProgress((float) a.getAnimatedValue()));
    }

    /** Begin (or restart) the repeating animation. */
    void start() {
        animator.start();
    }

    /**
     * Cancel the animation and detach its update listener so the platform
     * {@code AnimationHandler} releases the animator (and everything the update
     * callback captured). Idempotent — safe to call more than once, and safe to
     * call on a line that never started.
     */
    void stop() {
        animator.cancel();
        animator.removeAllUpdateListeners();
    }

    /** Whether {@link #start()} has run and {@link #stop()} has not since. */
    boolean isRunning() {
        return animator.isStarted();
    }
}
