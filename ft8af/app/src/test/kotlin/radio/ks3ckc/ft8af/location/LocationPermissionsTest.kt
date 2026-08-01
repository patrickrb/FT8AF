package radio.ks3ckc.ft8af.location

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The permission predicate trip mode gates on.
 *
 * The case that matters is the middle one: since Android 12 the system dialog
 * lets the user answer "Approximate", granting COARSE while denying FINE. A
 * check that insists on FINE reads that as a refusal — which is what stopped
 * Start trip working for a permission the user had already granted, while the
 * tracker underneath was perfectly willing to run on it.
 *
 * Robolectric so real permission grants can be simulated.
 */
@RunWith(RobolectricTestRunner::class)
class LocationPermissionsTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun grant(vararg permissions: String) {
        shadowOf(app).grantPermissions(*permissions)
    }

    private fun denyAll() {
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    @Test
    fun `precise location is enough`() {
        denyAll()
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        assertThat(hasLocationPermission(app)).isTrue()
    }

    @Test
    fun `approximate location alone is enough`() {
        // The regression: an "Approximate" answer to the Android 12+ dialog.
        denyAll()
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertThat(hasLocationPermission(app)).isTrue()
    }

    @Test
    fun `both granted is enough`() {
        denyAll()
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertThat(hasLocationPermission(app)).isTrue()
    }

    @Test
    fun `neither granted is a refusal`() {
        denyAll()
        assertThat(hasLocationPermission(app)).isFalse()
        assertThat(
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        ).isEqualTo(PackageManager.PERMISSION_DENIED)
    }
}
