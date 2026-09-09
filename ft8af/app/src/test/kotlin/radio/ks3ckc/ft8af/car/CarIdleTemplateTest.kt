package radio.ks3ckc.ft8af.car

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the Android Auto idle screen against the Play rejection of versionCode
 * 2100 — "Auto App Quality Guidelines: Visual info on phone. Your app does not
 * disable features requiring phone interaction while in driving mode."
 *
 * The screen used to render "Open FT8AF on your phone to start the FT8 engine",
 * so the car both told the driver to use their phone and left that available
 * while driving. These tests pin the two properties that fix it: the message is
 * status-only, and the single phone-reaching action is parked-only.
 */
@RunWith(RobolectricTestRunner::class)
class CarIdleTemplateTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun template(onStart: () -> Unit = {}) = engineIdleTemplate(
        title = "FT8AF",
        message = "FT8AF is not on the air yet.",
        startActionTitle = "Start FT8AF",
        onParkedStart = onStart,
    )

    @Test
    fun startAction_isParkedOnly_soTheHostBlocksItWhileDriving() {
        val actions = template().actions
        assertThat(actions).hasSize(1)
        assertThat(actions[0].title?.toString()).isEqualTo("Start FT8AF")
        // The whole point of the fix: the host refuses the click while the car
        // is moving. Dropping the ParkedOnlyOnClickListener wrapper flips this.
        assertThat(actions[0].onClickDelegate?.isParkedOnly).isTrue()
    }

    @Test
    fun idleTemplate_showsTheStatusMessage() {
        val built = template()
        assertThat(built.message.toString()).isEqualTo("FT8AF is not on the air yet.")
    }

    @Test
    fun parkedStart_isNotInvokedWhileBuildingTheTemplate() {
        var started = false
        template { started = true }
        assertThat(started).isFalse()
    }

    /**
     * The shipped string, not just the template shape: a translation or copy
     * edit that reintroduces "open … on your phone" would pass the tests above
     * and fail Play review again.
     */
    @Test
    fun idleMessage_doesNotDirectTheDriverToTheirPhone() {
        val message = context.getString(R.string.car_engine_idle).lowercase()
        listOf("phone", "handset", "tap", "touch").forEach { banned ->
            assertThat(message).doesNotContain(banned)
        }
        assertThat(context.getString(R.string.car_engine_idle_action)).isNotEmpty()
    }
}
