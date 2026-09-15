package radio.ks3ckc.ft8af.ui.settings

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The shared maintainer email intent used by the bug reporter and rate-prompt feedback. */
@RunWith(RobolectricTestRunner::class)
class ReportEmailIntentTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `intent is an email-only send to the maintainer with subject and body`() {
        val intent = buildReportEmailIntent(app, subject = "App feedback (v1.1.0)", body = "It works")

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.type).isEqualTo("text/plain")
        assertThat(intent.selector?.action).isEqualTo(Intent.ACTION_SENDTO)
        assertThat(intent.selector?.data.toString()).isEqualTo("mailto:")
        assertThat(intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
            .asList().containsExactly(app.getString(R.string.bug_report_email))
        assertThat(intent.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("App feedback (v1.1.0)")
        assertThat(intent.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("It works")
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `base intent carries no attachment`() {
        val intent = buildReportEmailIntent(app, subject = "s", body = "b")
        assertThat(intent.hasExtra(Intent.EXTRA_STREAM)).isFalse()
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isEqualTo(0)
    }
}
