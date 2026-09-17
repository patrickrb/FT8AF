package radio.ks3ckc.ft8af.ui.settings

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import java.io.File

/**
 * Covers the always-visible "Share logs" row in Settings -> About: which file it
 * shares, when there is nothing to share, and the share-sheet intent shape.
 */
@RunWith(RobolectricTestRunner::class)
class DebugLogShareTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun freshLog(): File =
        debugLogFile(context)!!.also { it.parentFile?.mkdirs(); it.delete() }

    @Test
    fun debugLogFile_isDebugLogInExternalFilesDir() {
        val file = debugLogFile(context)
        assertThat(file).isNotNull()
        assertThat(file!!.name).isEqualTo("debug.log")
        assertThat(file.parentFile).isEqualTo(context.getExternalFilesDir(null))
    }

    @Test
    fun buildIntent_nullFile_returnsNull() {
        assertThat(buildDebugLogShareIntent(context, null)).isNull()
    }

    @Test
    fun buildIntent_missingFile_returnsNull() {
        assertThat(buildDebugLogShareIntent(context, freshLog())).isNull()
    }

    @Test
    fun buildIntent_emptyFile_returnsNull() {
        val log = freshLog().apply { writeText("") }
        assertThat(buildDebugLogShareIntent(context, log)).isNull()
    }

    @Test
    fun buildIntent_withLog_wrapsTextSendInChooser() {
        val log = freshLog().apply { writeText("12:00:00.000 hello\n") }
        val fakeUri = Uri.parse("content://radio.ks3ckc.ft8af.fileprovider/external_files/debug.log")
        var uriFor: File? = null

        // Robolectric's fake external dir sits outside FileProvider's configured
        // roots, so the content-URI step is stubbed; the intent shape is what's tested.
        val chooser = buildDebugLogShareIntent(context, log) { uriFor = it; fakeUri }!!
        assertThat(uriFor).isEqualTo(log)
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)

        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("FT8AF debug.log")
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)

        @Suppress("DEPRECATION")
        assertThat(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)).isEqualTo(fakeUri)
    }

    @Test
    fun shareDebugLog_noLog_returnsFalseAndStartsNothing() {
        freshLog()
        assertThat(shareDebugLog(context)).isFalse()
        assertThat(Shadows.shadowOf(context).nextStartedActivity).isNull()
    }
}
