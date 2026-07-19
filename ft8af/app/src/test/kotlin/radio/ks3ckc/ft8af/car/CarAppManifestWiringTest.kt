package radio.ks3ckc.ft8af.car

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins Android Auto as *unwired* in the manifest. The car-app screens
 * (FT8AFCarAppService/QsoStatusScreen) still exist in the tree, but the manifest
 * entries the Android Auto host discovers them through were removed: Play rejected
 * the app for not behaving as a NAVIGATION-category car app ("does not load map and
 * user location in Android Auto Environment"), and re-adding either entry would
 * re-flag the app as Android-Auto-enabled and re-trigger that review failure.
 *
 * This runs against the debug variant's merged manifest, which still overlays the
 * debug-only CarAppActivity used for on-emulator development — that entry is not a
 * release Android Auto descriptor and never ships, so it doesn't count here.
 */
@RunWith(RobolectricTestRunner::class)
class CarAppManifestWiringTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun noCarAppService_isDeclared() {
        val intent = Intent("androidx.car.app.CarAppService").setPackage(context.packageName)
        val services = context.packageManager.queryIntentServices(
            intent,
            PackageManager.GET_RESOLVED_FILTER,
        )
        assertThat(services).isEmpty()
    }

    @Test
    fun androidAutoDescriptorMetaData_isAbsent() {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        // Other application-level meta-data (e.g. io.sentry.auto-init) keeps this
        // bundle non-null; what must be gone is the Android Auto descriptor and the
        // car-app API-level floor that together mark the app as an AA app.
        val meta = appInfo.metaData
        assertThat(meta.containsKey("com.google.android.gms.car.application")).isFalse()
        assertThat(meta.containsKey("androidx.car.app.minCarApiLevel")).isFalse()
    }
}
