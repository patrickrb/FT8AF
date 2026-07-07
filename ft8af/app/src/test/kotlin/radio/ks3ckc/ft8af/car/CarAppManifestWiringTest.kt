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
        assertThat(appInfo.metaData.getInt("com.google.android.gms.car.application"))
            .isEqualTo(R.xml.automotive_app_desc)
        assertThat(appInfo.metaData.getInt("androidx.car.app.minCarApiLevel")).isEqualTo(1)
    }
}
