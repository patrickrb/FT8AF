package radio.ks3ckc.ft8af

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Immersive system-bar configuration for the edge-to-edge migration.
 *
 * Android 15 (targetSdk 35) forces edge-to-edge and deprecates the old
 * FLAG_FULLSCREEN flag plus window.statusBarColor / navigationBarColor setters.
 * The app's original look is full-screen with the system bars hidden, so instead
 * of those deprecated APIs we hide the bars via the insets controller and let
 * them reappear transiently on a swipe (then auto-hide again).
 *
 * Extracted from the Activity so the configuration is unit-testable — the
 * Activity itself can't be instantiated cheaply in tests.
 */
internal object ImmersiveBars {
    /** Bars stay hidden; a swipe reveals them transiently, then they re-hide. */
    const val BEHAVIOR = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    /** Hide the status + navigation bars and arm transient-by-swipe reveal. */
    fun apply(controller: WindowInsetsControllerCompat) {
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = BEHAVIOR
    }
}
