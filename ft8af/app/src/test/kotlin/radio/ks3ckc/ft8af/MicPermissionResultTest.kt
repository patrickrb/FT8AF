package radio.ks3ckc.ft8af

import android.Manifest
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers [micGrantedIn] — the gate that decides whether a permission result should
 * rebuild the mic capture. On first launch the recorder starts before the user answers
 * the RECORD_AUDIO prompt, its AudioRecord fails, and RX stays deaf for the session
 * unless the grant restarts it.
 *
 * Manifest permission strings and PERMISSION_GRANTED/DENIED are compile-time
 * constants, so a bare JUnit runner suffices — no Android runtime needed.
 */
class MicPermissionResultTest {

    @Test
    fun granted_whenRecordAudioGranted() {
        val perms = arrayOf(Manifest.permission.RECORD_AUDIO)
        val grants = intArrayOf(PackageManager.PERMISSION_GRANTED)
        assertThat(micGrantedIn(perms, grants)).isTrue()
    }

    @Test
    fun granted_whenRecordAudioGrantedAmongOthers() {
        // The first-launch request batches every permission; RECORD_AUDIO need not be first.
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
        val grants = intArrayOf(PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_GRANTED)
        assertThat(micGrantedIn(perms, grants)).isTrue()
    }

    @Test
    fun notGranted_whenRecordAudioDenied() {
        val perms = arrayOf(Manifest.permission.RECORD_AUDIO)
        val grants = intArrayOf(PackageManager.PERMISSION_DENIED)
        assertThat(micGrantedIn(perms, grants)).isFalse()
    }

    @Test
    fun notGranted_whenRecordAudioNotInResult() {
        // A result for an unrelated permission must not tear down a working capture.
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val grants = intArrayOf(PackageManager.PERMISSION_GRANTED)
        assertThat(micGrantedIn(perms, grants)).isFalse()
    }

    @Test
    fun notGranted_whenResultsCancelled() {
        // A cancelled request delivers the permissions with an empty results array.
        val perms = arrayOf(Manifest.permission.RECORD_AUDIO)
        assertThat(micGrantedIn(perms, intArrayOf())).isFalse()
    }
}
