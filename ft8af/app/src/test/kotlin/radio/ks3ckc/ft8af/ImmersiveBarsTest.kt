package radio.ks3ckc.ft8af

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Guards the edge-to-edge immersive system-bar configuration.
 *
 * The app migrated off the deprecated FLAG_FULLSCREEN / window.statusBarColor
 * APIs (no-ops on Android 15 / targetSdk 35) to enableEdgeToEdge plus an insets
 * controller that hides the bars. [ImmersiveBars] must request the
 * transient-by-swipe behavior so a swipe still reveals the bars and they then
 * re-hide — losing that constant would either pin the bars open or make them
 * unrecoverable.
 */
@RunWith(RobolectricTestRunner::class)
class ImmersiveBarsTest {

    @Test
    fun behavior_isTransientBarsBySwipe() {
        assertThat(ImmersiveBars.BEHAVIOR)
            .isEqualTo(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
    }

    @Test
    fun apply_setsTransientSwipeBehaviorOnController() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val controller = WindowCompat.getInsetsController(
            activity.window, activity.window.decorView,
        )

        ImmersiveBars.apply(controller)

        assertThat(controller.systemBarsBehavior).isEqualTo(ImmersiveBars.BEHAVIOR)
    }
}
