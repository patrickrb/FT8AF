package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.bandadvisor.alerts.AlertType

class BandAdvisorSettingsTest {

    @Test
    fun `toggled adds and removes`() {
        assertThat(toggled(setOf("10m"), "15m")).containsExactly("10m", "15m")
        assertThat(toggled(setOf("10m", "15m"), "15m")).containsExactly("10m")
        assertThat(toggled(emptySet(), "20m")).containsExactly("20m")
    }

    @Test
    fun `every alert type has a distinct label`() {
        val labels = AlertType.entries.map { alertTypeLabelRes(it) }
        assertThat(labels.toSet()).hasSize(AlertType.entries.size)
    }

    @Test
    fun `region choices match the server region enum names`() {
        for (region in ALERT_REGION_CHOICES) {
            assertThat(region).matches("[A-Z_]+")
        }
        assertThat(ALERT_BAND_CHOICES).contains("20m")
    }
}
