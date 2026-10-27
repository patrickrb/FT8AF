package radio.ks3ckc.ft8af.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [CloudlogRejectionDedupe]: the background catch-up sync re-runs on every app start and
 * reconnect, so the same Cloudlog rejection must toast once, not every run.
 */
class CloudlogRejectionDedupeTest {
    private val station =
        "HTTP 401: {\"status\":\"failed\",\"reason\":\"station id does not belong to the API key owner.\"}"
    private val key = "HTTP 401: {\"status\":\"failed\",\"reason\":\"missing or wrong api key\"}"

    @Test
    fun `the same rejection is shown once`() {
        val d = CloudlogRejectionDedupe()

        assertThat(d.shouldShow(station)).isTrue()
        assertThat(d.shouldShow(station)).isFalse()
    }

    @Test
    fun `a different rejection is shown`() {
        val d = CloudlogRejectionDedupe()
        d.shouldShow(station)

        assertThat(d.shouldShow(key)).isTrue()
    }

    @Test
    fun `a clean run re-arms it`() {
        val d = CloudlogRejectionDedupe()
        d.shouldShow(station)

        assertThat(d.shouldShow(null)).isFalse()
        assertThat(d.shouldShow(station)).isTrue()
    }

    @Test
    fun `transport failures never show and do not re-arm`() {
        val d = CloudlogRejectionDedupe()
        d.shouldShow(station)

        assertThat(d.shouldShow("Unknown host: wavelog.example")).isFalse()
        assertThat(d.shouldShow(station)).isFalse()
    }
}
