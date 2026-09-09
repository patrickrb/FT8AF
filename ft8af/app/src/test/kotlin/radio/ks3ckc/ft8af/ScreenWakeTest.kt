package radio.ks3ckc.ft8af

import android.app.Activity
import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for [ScreenWake], which applies the "keep screen on" preference.
 *
 * The flag used to be added unconditionally in `ComposeMainActivity.onCreate`, so
 * a foreground session held the panel awake for its whole duration with no way to
 * turn it off. That is one of the two biggest heat sources on the phone during a
 * long portable run, and a hot phone browns out its own OTG accessory rail —
 * field log 2026-07-23 shows 48.6C battery and twelve USB re-enumerations, two of
 * which left the rig keyed.
 *
 * Robolectric: needs a real [android.view.Window] to add and clear flags on.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenWakeTest {

    private fun window() = Robolectric.buildActivity(Activity::class.java).setup().get().window

    private fun holdsScreenOn(activityWindow: android.view.Window) =
        ScreenWake.isHoldingScreenOn(activityWindow.attributes.flags)

    @Test
    fun `apply true holds the screen awake`() {
        val w = window()
        ScreenWake.apply(w, true)
        assertThat(holdsScreenOn(w)).isTrue()
    }

    @Test
    fun `apply false releases the screen`() {
        val w = window()
        ScreenWake.apply(w, true)
        ScreenWake.apply(w, false)
        assertThat(holdsScreenOn(w)).isFalse()
    }

    @Test
    fun `apply false on a window that never held it is a no-op`() {
        // onCreate applies the default before config hydration; hydration may then
        // apply false to a window that never had the flag.
        val w = window()
        ScreenWake.apply(w, false)
        assertThat(holdsScreenOn(w)).isFalse()
    }

    @Test
    fun `repeated applies are idempotent`() {
        // Called from onCreate, again from onResume, and again on every toggle.
        val w = window()
        ScreenWake.apply(w, true)
        ScreenWake.apply(w, true)
        assertThat(holdsScreenOn(w)).isTrue()
        ScreenWake.apply(w, false)
        ScreenWake.apply(w, false)
        assertThat(holdsScreenOn(w)).isFalse()
    }

    @Test
    fun `toggling back on after off re-holds the screen`() {
        val w = window()
        ScreenWake.apply(w, false)
        ScreenWake.apply(w, true)
        assertThat(holdsScreenOn(w)).isTrue()
    }

    @Test
    fun `isHoldingScreenOn reads the flag bit`() {
        assertThat(ScreenWake.isHoldingScreenOn(0)).isFalse()
        assertThat(
            ScreenWake.isHoldingScreenOn(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON),
        ).isTrue()
        // Must not be confused by other flags sharing the mask word.
        assertThat(
            ScreenWake.isHoldingScreenOn(WindowManager.LayoutParams.FLAG_FULLSCREEN),
        ).isFalse()
        assertThat(
            ScreenWake.isHoldingScreenOn(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            ),
        ).isTrue()
    }
}
