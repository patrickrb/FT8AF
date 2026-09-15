package radio.ks3ckc.ft8af.ui.rateprompt

import android.app.Activity
import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RatePromptActionsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `store URIs point at the application id`() {
        assertThat(playStoreMarketUri("radio.ks3ckc.ft8af").toString())
            .isEqualTo("market://details?id=radio.ks3ckc.ft8af")
        assertThat(playStoreWebUri("radio.ks3ckc.ft8af").toString())
            .isEqualTo("https://play.google.com/store/apps/details?id=radio.ks3ckc.ft8af")
    }

    @Test
    fun `store intent is a new-task VIEW`() {
        val intent = playStoreIntent(playStoreMarketUri("x.y"))
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data.toString()).isEqualTo("market://details?id=x.y")
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `findActivity unwraps nested context wrappers`() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        assertThat(activity.findActivity()).isSameInstanceAs(activity)
        assertThat(ContextWrapper(ContextWrapper(activity)).findActivity()).isSameInstanceAs(activity)
    }

    @Test
    fun `findActivity is null for the application context`() {
        assertThat(app.findActivity()).isNull()
    }

    @Test
    fun `store listing opens the market URI first`() {
        assertThat(openPlayStoreListing(app)).isTrue()
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.data).isEqualTo(playStoreMarketUri(app.packageName))
    }

    @Test
    fun `launchPlayReview without an activity falls back to the store listing`() {
        launchPlayReview(app)
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.data).isEqualTo(playStoreMarketUri(app.packageName))
    }
}
