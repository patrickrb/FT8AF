package radio.ks3ckc.ft8af.sync

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * The on-screen side of a rejected Cloudlog / Wavelog upload: [cloudlogFailureMessage],
 * [cloudlogRejectionToastText] and [showCloudlogRejectionToast]. The toast must be a real
 * Android toast (the old `ToastMessage.show` stream is invisible in the Compose launcher)
 * and must not fire for transport failures, which stay queued for the next sync.
 */
@RunWith(RobolectricTestRunner::class)
class CloudlogRejectionToastTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var savedContext: Context? = null

    private val stationRejection =
        "HTTP 401: {\"status\":\"failed\",\"reason\":\"station id does not belong to the API key owner.\"}"

    @Before
    fun setUp() {
        savedContext = GeneralVariables.getMainContext()
        GeneralVariables.getInstance().setMainContext(app)
    }

    @After
    fun tearDown() {
        GeneralVariables.getInstance().setMainContext(savedContext)
    }

    @Test
    fun `message is the reason followed by the hint`() {
        assertThat(cloudlogFailureMessage(stationRejection)).isEqualTo(
            "station id does not belong to the API key owner. " + app.getString(R.string.cloudlog_hint_station_id),
        )
    }

    @Test
    fun `message without a known hint is just the reason`() {
        assertThat(cloudlogFailureMessage("HTTP 400: {\"messages\":[\"bad band\"]}")).isEqualTo("bad band")
        assertThat(cloudlogFailureMessage(null)).isEmpty()
    }

    @Test
    fun `toast text wraps the message for a server rejection`() {
        assertThat(cloudlogRejectionToastText(stationRejection)).isEqualTo(
            app.getString(R.string.cloudlog_upload_rejected, cloudlogFailureMessage(stationRejection)),
        )
    }

    @Test
    fun `no toast text for a transport failure or nothing`() {
        assertThat(cloudlogRejectionToastText("Unknown host: wavelog.example")).isNull()
        assertThat(cloudlogRejectionToastText("")).isNull()
        assertThat(cloudlogRejectionToastText(null)).isNull()
    }

    @Test
    fun `a server rejection shows a real toast`() {
        assertThat(showCloudlogRejectionToast(stationRejection)).isTrue()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(cloudlogRejectionToastText(stationRejection))
    }

    @Test
    fun `a transport failure shows no toast`() {
        assertThat(showCloudlogRejectionToast("Timed out: connect timed out")).isFalse()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }

    @Test
    fun `no toast before the app context exists`() {
        GeneralVariables.getInstance().setMainContext(null)

        assertThat(showCloudlogRejectionToast(stationRejection)).isFalse()
    }
}
