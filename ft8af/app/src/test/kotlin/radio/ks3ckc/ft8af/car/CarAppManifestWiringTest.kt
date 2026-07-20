package radio.ks3ckc.ft8af.car

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
        // ApplicationInfo.metaData is null when the manifest carries no
        // application-level meta-data at all; treat that as an empty Bundle so the
        // test asserts on absence of the AA keys rather than NPE-ing. (Today
        // io.sentry.auto-init keeps it non-null, but the guard must not depend on
        // that unrelated entry surviving.)
        val meta = appInfo.metaData ?: Bundle()
        assertThat(meta.containsKey("com.google.android.gms.car.application")).isFalse()
        assertThat(meta.containsKey("androidx.car.app.minCarApiLevel")).isFalse()
    }
}
