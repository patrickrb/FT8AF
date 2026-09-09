package radio.ks3ckc.ft8af

import android.view.Window
import android.view.WindowManager

/**
 * Applies the "keep the screen awake" preference to a window.
 *
 * The flag used to be added unconditionally in [ComposeMainActivity.onCreate], so a
 * foreground session held the panel on at whatever brightness for its entire
 * duration. On a long portable run that is one of the two biggest heat sources on
 * the phone (the other being the deep-decode loop), and a hot phone browns out its
 * own OTG accessory rail — the 2026-07-23 field log shows the battery at 48.6C and
 * the USB bus re-enumerating twelve times, twice leaving the rig keyed.
 *
 * Receive keeps running with the screen off via `RxForegroundService`, so this is a
 * safe knob: turning it off costs nothing but having to wake the phone to look at
 * the waterfall.
 *
 * Split out of the activity so the add/clear decision is unit-testable — the
 * activity itself cannot be instantiated in a plain JVM test.
 */
object ScreenWake {

    /**
     * Add or clear [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] on [window].
     *
     * Idempotent: setting the same value twice is a no-op, so this can be called
     * from `onCreate`, again after config hydration (which may flip it), and again
     * whenever the user toggles the setting.
     *
     * @param keepOn the user's preference, i.e. `GeneralVariables.keepScreenOn`
     */
    @JvmStatic
    fun apply(window: Window, keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Whether [flags] currently holds the screen awake. Exposed for the activity's
     * own re-apply path and for tests.
     */
    @JvmStatic
    fun isHoldingScreenOn(flags: Int): Boolean =
        (flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
}
