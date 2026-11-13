package radio.ks3ckc.ft8af

import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Regression tests for [createUsbPermissionIntent].
 *
 * The primary goal is to prevent a repeat of the crash on Android 14+
 * (API 34) caused by creating a mutable PendingIntent backed by an
 * implicit intent (no package set). That combination throws
 * `IllegalArgumentException` at runtime.
 *
 * Robolectric is pinned to API 35 via src/test/resources/robolectric.properties
 * (the app targets a newer API; see that file for the JDK-version reason).
 * The utility function branches on `Build.VERSION.SDK_INT` at runtime,
 * so these tests verify the structural invariants that hold at *every*
 * API level: the intent is always explicit, and the action is propagated.
 * The FLAG_MUTABLE / no-flag branching is tested indirectly because the
 * default SDK >= 31.
 */
@RunWith(RobolectricTestRunner::class)
class UsbPermissionIntentsTest {

    private val action = "com.k1af.ft8af.TEST_USB_PERMISSION"

    @Test
    fun doesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pi = createUsbPermissionIntent(context, action)
        assertThat(pi).isNotNull()
    }

    @Test
    fun intentIsExplicit_packageIsSet() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pi = createUsbPermissionIntent(context, action)
        val intent = Shadows.shadowOf(pi).savedIntent
        assertThat(intent.`package`).isEqualTo(context.packageName)
    }

    @Test
    fun usesMutableFlag_onApi31Plus() {
        // Default Robolectric SDK is >= 31, so FLAG_MUTABLE should be set.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pi = createUsbPermissionIntent(context, action)
        val shadow = Shadows.shadowOf(pi)
        assertThat(shadow.flags and PendingIntent.FLAG_MUTABLE).isNotEqualTo(0)
    }

    @Test
    fun intentCarriesCorrectAction() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pi = createUsbPermissionIntent(context, action)
        val intent = Shadows.shadowOf(pi).savedIntent
        assertThat(intent.action).isEqualTo(action)
    }
}
