package radio.ks3ckc.ft8af

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Guards the manifest's `android:configChanges` on [ComposeMainActivity].
 *
 * Without these flags Android destroys and recreates the launcher activity on
 * every rotation, which replays the splash and tears down live state (CAT link,
 * audio loop, decode cycle). Declaring the flags makes Compose simply relay out
 * in place. This test fails loudly if a future manifest edit drops any of them
 * and silently reintroduces the rotate-restart bug.
 *
 * Robolectric reads the merged manifest, so the assertion reflects what actually
 * ships. ActivityInfo.configChanges is a bitmask of CONFIG_* flags.
 */
@RunWith(RobolectricTestRunner::class)
class ComposeMainActivityConfigChangesTest {

    private val required = mapOf(
        "orientation" to ActivityInfo.CONFIG_ORIENTATION,
        "screenSize" to ActivityInfo.CONFIG_SCREEN_SIZE,
        "smallestScreenSize" to ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE,
        "keyboardHidden" to ActivityInfo.CONFIG_KEYBOARD_HIDDEN,
        "screenLayout" to ActivityInfo.CONFIG_SCREEN_LAYOUT,
        "uiMode" to ActivityInfo.CONFIG_UI_MODE,
    )

    private fun activityInfo(): ActivityInfo {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context, ComposeMainActivity::class.java)
        return context.packageManager.getActivityInfo(component, PackageManager.GET_META_DATA)
    }

    @Test
    fun launcherActivity_handlesRotationWithoutRecreation() {
        val configChanges = activityInfo().configChanges
        for ((name, flag) in required) {
            assertWithMessage("configChanges must declare '$name'")
                .that(configChanges and flag).isEqualTo(flag)
        }
    }

    @Test
    fun launcherActivity_handlesOrientationAndScreenSizeTogether() {
        // Rotation changes orientation *and* screen size on modern target SDKs;
        // both must be claimed or the activity still recreates. This is the
        // single most important pair for the rotate-restart bug.
        val configChanges = activityInfo().configChanges
        val rotationPair = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE
        assertThat(configChanges and rotationPair).isEqualTo(rotationPair)
    }
}
