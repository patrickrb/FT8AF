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
 * ([FT8AFCarAppService], [QsoStatusScreen]) still exist in the tree and still
 * compile, but the manifest entries the Android Auto host discovers them
 * through are gone, so the app is not flagged as Android-Auto-enabled and Play
 * does not put it through Auto app-quality review.
 *
 * Two Auto rejections stand behind this, and re-adding either manifest entry
 * puts the app back in front of both:
 *
 *  - versionCode 1327 (2026-07-19), NAVIGATION category: "does not load map and
 *    user location in Android Auto Environment" — a QSO monitor can't meet
 *    navigation quality bars, and no approved Auto category fits the app.
 *  - versionCode 2100 (2026-09-09), IOT category: "Visual info on phone — your
 *    app does not disable features requiring phone interaction while in driving
 *    mode", against the idle screen's old "open FT8AF on your phone" message.
 *
 * The screens themselves were made compliant with the second finding (see
 * `engineIdleTemplate` and CarIdleTemplateTest) so a future revival starts from
 * a clean base — but the wiring stays out until someone decides to take Auto
 * review on again.
 *
 * This runs against the debug variant's merged manifest, which still overlays
 * the debug-only CarAppActivity used for on-emulator development. That is an
 * Activity, not a CarAppService or an Auto descriptor, and never ships in
 * release, so it doesn't count here.
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

    @Test
    fun carTemplatePermissions_areNotRequested() {
        val requested = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toList()
        assertThat(requested).doesNotContain("androidx.car.app.MAP_TEMPLATES")
        assertThat(requested).doesNotContain("androidx.car.app.NAVIGATION_TEMPLATES")
        assertThat(requested).doesNotContain("androidx.car.app.ACCESS_SURFACE")
    }
}
