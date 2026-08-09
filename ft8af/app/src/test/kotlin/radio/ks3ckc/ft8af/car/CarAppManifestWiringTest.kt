package radio.ks3ckc.ft8af.car

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the Android Auto manifest wiring: the host discovers the app through the
 * CarAppService intent filter plus the automotive_app_desc meta-data, and a
 * silently dropped entry would only show up as "app missing from the car
 * launcher" — a failure mode adb/unit tests can't otherwise see.
 *
 * It also pins the wiring to the exact shape Play approved in production
 * (versionCode 1327 / 2.0): IOT category, no NAVIGATION/POI category, and no
 * androidx.car.app template permissions. The NAVIGATION-category map variant
 * was rejected by Play review ("does not load map and user location") and had
 * to be pulled in PR #600 — these guards keep that shape from coming back.
 */
@RunWith(RobolectricTestRunner::class)
class CarAppManifestWiringTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun carAppService_isDeclaredExported_withIotCategory() {
        val intent = Intent("androidx.car.app.CarAppService").setPackage(context.packageName)
        val services = context.packageManager.queryIntentServices(
            intent,
            PackageManager.GET_RESOLVED_FILTER,
        )
        assertThat(services).hasSize(1)
        val resolved = services[0]
        assertThat(resolved.serviceInfo.name).isEqualTo("radio.ks3ckc.ft8af.car.FT8AFCarAppService")
        assertThat(resolved.serviceInfo.exported).isTrue()
        assertThat(resolved.filter.hasCategory("androidx.car.app.category.IOT")).isTrue()
    }

    @Test
    fun automotiveAppDescriptor_andMinCarApiLevel_areDeclared() {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        assertThat(appInfo.metaData).isNotNull()
        assertThat(appInfo.metaData.getInt("com.google.android.gms.car.application"))
            .isEqualTo(R.xml.automotive_app_desc)
        assertThat(appInfo.metaData.getInt("androidx.car.app.minCarApiLevel")).isEqualTo(1)
    }

    @Test
    fun rejectedNavigationCategory_isNotDeclared() {
        val intent = Intent("androidx.car.app.CarAppService").setPackage(context.packageName)
        val resolved = context.packageManager.queryIntentServices(
            intent,
            PackageManager.GET_RESOLVED_FILTER,
        )[0]
        assertThat(resolved.filter.hasCategory("androidx.car.app.category.NAVIGATION")).isFalse()
        assertThat(resolved.filter.hasCategory("androidx.car.app.category.POI")).isFalse()
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
