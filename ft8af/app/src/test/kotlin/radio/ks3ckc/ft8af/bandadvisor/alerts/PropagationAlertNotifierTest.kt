package radio.ks3ckc.ft8af.bandadvisor.alerts

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class PropagationAlertNotifierTest {
    private lateinit var savedDefault: TimeZone

    @Before
    fun setUp() {
        savedDefault = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedDefault)
    }

    private val at1755Utc = 1_790_531_700_000L // 2026-09-27T17:55:00Z

    @Test
    fun `conditions time is UTC regardless of device timezone`() {
        for (zone in listOf("America/Chicago", "Asia/Kolkata", "Pacific/Auckland", "UTC")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            assertThat(formatConditionsTimeUtc(at1755Utc)).isEqualTo("17:55")
        }
    }

    @Test
    fun `conditions time is zero padded 24 hour`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        assertThat(formatConditionsTimeUtc(0L)).isEqualTo("00:00")
        assertThat(formatConditionsTimeUtc(at1755Utc - 17 * 3_600_000L + 5 * 60_000L)).isEqualTo("01:00")
    }
}
