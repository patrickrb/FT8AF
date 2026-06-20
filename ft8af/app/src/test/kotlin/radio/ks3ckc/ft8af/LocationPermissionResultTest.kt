package radio.ks3ckc.ft8af

import android.Manifest
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers [locationGrantedIn] — the gate that decides whether a permission
 * result should (re)start the GPS grid updater (issue #59). The first time a
 * user enables the toggle, the permission dialog resolves *after* the initial
 * refresh() call, so this is the path that actually starts location updates in
 * the same session.
 *
 * Manifest permission strings and PERMISSION_GRANTED/DENIED are compile-time
 * constants, so a bare JUnit runner suffices — no Android runtime needed.
 */
class LocationPermissionResultTest {

    @Test
    fun granted_whenFineLocationGranted() {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val grants = intArrayOf(PackageManager.PERMISSION_GRANTED)
        assertThat(locationGrantedIn(perms, grants)).isTrue()
    }

    @Test
    fun granted_whenCoarseLocationGranted() {
        val perms = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        val grants = intArrayOf(PackageManager.PERMISSION_GRANTED)
        assertThat(locationGrantedIn(perms, grants)).isTrue()
    }

    @Test
    fun notGranted_whenLocationDenied() {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val grants = intArrayOf(PackageManager.PERMISSION_DENIED)
        assertThat(locationGrantedIn(perms, grants)).isFalse()
    }

    @Test
    fun notGranted_whenNoLocationPermissionInResult() {
        // A result for an unrelated permission (e.g. notifications) must not
        // trigger a GPS restart.
        val perms = arrayOf(Manifest.permission.RECORD_AUDIO)
        val grants = intArrayOf(PackageManager.PERMISSION_GRANTED)
        assertThat(locationGrantedIn(perms, grants)).isFalse()
    }

    @Test
    fun granted_whenLocationIsOneOfSeveralPermissions() {
        // Cold-start path requests a whole batch at once; the location grant must
        // be found regardless of its position in the array.
        val perms = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WAKE_LOCK,
        )
        val grants = intArrayOf(
            PackageManager.PERMISSION_DENIED,
            PackageManager.PERMISSION_GRANTED,
            PackageManager.PERMISSION_GRANTED,
        )
        assertThat(locationGrantedIn(perms, grants)).isTrue()
    }

    @Test
    fun notGranted_whenGrantResultsShorterThanPermissions() {
        // Defensive: a truncated grantResults array must not throw.
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val grants = intArrayOf()
        assertThat(locationGrantedIn(perms, grants)).isFalse()
    }
}
