package radio.ks3ckc.ft8us

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [shouldArmHuntOnStartup] — the predicate that decides whether
 * Hunt should be armed when the app cold-starts with autoFollowCQ persisted as true.
 */
class HuntColdStartTest {

    @Test
    fun `hunt enabled with valid callsign — should arm`() {
        assertThat(shouldArmHuntOnStartup(huntEnabled = true, callsign = "W1AW")).isTrue()
    }

    @Test
    fun `hunt disabled with valid callsign — should not arm`() {
        assertThat(shouldArmHuntOnStartup(huntEnabled = false, callsign = "W1AW")).isFalse()
    }

    @Test
    fun `hunt enabled with null callsign — should not arm`() {
        assertThat(shouldArmHuntOnStartup(huntEnabled = true, callsign = null)).isFalse()
    }

    @Test
    fun `hunt enabled with empty callsign — should not arm`() {
        assertThat(shouldArmHuntOnStartup(huntEnabled = true, callsign = "")).isFalse()
    }

    @Test
    fun `hunt disabled with null callsign — should not arm`() {
        assertThat(shouldArmHuntOnStartup(huntEnabled = false, callsign = null)).isFalse()
    }
}
