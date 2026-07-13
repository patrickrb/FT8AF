package radio.ks3ckc.ft8af.ui.decode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the pure "Last heard" relative-time logic [computeLastHeard]
 * shown above the map on the QSO Details sheet. Pure math/logic, so no
 * Robolectric runner is needed.
 */
class LastHeardTest {

    private val now = 1_700_000_000_000L // fixed reference "now" in epoch ms

    @Test
    fun `missing timestamp is Unknown`() {
        assertThat(computeLastHeard(0L, now)).isEqualTo(LastHeard.Unknown)
        assertThat(computeLastHeard(-1L, now)).isEqualTo(LastHeard.Unknown)
    }

    @Test
    fun `a future timestamp clamps to just now`() {
        // Heard "in the future" (clock skew) must never read as a negative age.
        assertThat(computeLastHeard(now + 10_000L, now)).isEqualTo(LastHeard.JustNow)
    }

    @Test
    fun `under five seconds is just now`() {
        assertThat(computeLastHeard(now, now)).isEqualTo(LastHeard.JustNow)
        assertThat(computeLastHeard(now - 4_999L, now)).isEqualTo(LastHeard.JustNow)
    }

    @Test
    fun `seconds bucket covers 5s up to under a minute`() {
        assertThat(computeLastHeard(now - 5_000L, now)).isEqualTo(LastHeard.Seconds(5))
        assertThat(computeLastHeard(now - 45_000L, now)).isEqualTo(LastHeard.Seconds(45))
        assertThat(computeLastHeard(now - 59_999L, now)).isEqualTo(LastHeard.Seconds(59))
    }

    @Test
    fun `minutes bucket covers one minute up to under an hour`() {
        assertThat(computeLastHeard(now - 60_000L, now)).isEqualTo(LastHeard.Minutes(1))
        assertThat(computeLastHeard(now - 12L * 60_000L, now)).isEqualTo(LastHeard.Minutes(12))
        assertThat(computeLastHeard(now - 3_599_999L, now)).isEqualTo(LastHeard.Minutes(59))
    }

    @Test
    fun `hours bucket covers one hour up to under a day`() {
        assertThat(computeLastHeard(now - 3_600_000L, now)).isEqualTo(LastHeard.Hours(1))
        assertThat(computeLastHeard(now - 3L * 3_600_000L, now)).isEqualTo(LastHeard.Hours(3))
        assertThat(computeLastHeard(now - (86_400_000L - 1L), now)).isEqualTo(LastHeard.Hours(23))
    }

    @Test
    fun `days bucket covers a day and beyond, including very old timestamps`() {
        assertThat(computeLastHeard(now - 86_400_000L, now)).isEqualTo(LastHeard.Days(2 - 1))
        assertThat(computeLastHeard(now - 2L * 86_400_000L, now)).isEqualTo(LastHeard.Days(2))
        // Very old timestamp still formats cleanly as a large day count.
        assertThat(computeLastHeard(now - 500L * 86_400_000L, now)).isEqualTo(LastHeard.Days(500))
    }
}
