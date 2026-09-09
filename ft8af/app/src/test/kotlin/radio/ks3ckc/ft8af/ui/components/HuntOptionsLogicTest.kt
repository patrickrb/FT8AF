package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.hunt.HuntPriority

/**
 * Coverage for the pure helpers extracted from HuntOptionsSheet.kt (the sheet's
 * chip data and the HUNT-button subtitle tag), mirroring CqOptionsLogicTest.
 */
class HuntOptionsLogicTest {

    @Test
    fun stripSubtitle_defaultPriorityShowsNothing() {
        assertThat(huntStripSubtitle(HuntPriority.LATEST)).isNull()
    }

    @Test
    fun stripSubtitle_nonDefaultPrioritiesShowShortTags() {
        assertThat(huntStripSubtitle(HuntPriority.STRONGEST)).isEqualTo("STRONG")
        assertThat(huntStripSubtitle(HuntPriority.WEAKEST)).isEqualTo("WEAK")
        assertThat(huntStripSubtitle(HuntPriority.FARTHEST)).isEqualTo("DX")
        assertThat(huntStripSubtitle(HuntPriority.POTA_FIRST)).isEqualTo("POTA")
        assertThat(huntStripSubtitle(HuntPriority.NEW_DXCC_FIRST)).isEqualTo("DXCC")
        assertThat(huntStripSubtitle(HuntPriority.NEW_GRID_FIRST)).isEqualTo("GRID")
    }

    @Test
    fun stripSubtitle_everyPriorityIsCoveredAndFitsTheButton() {
        // A new HuntPriority must get an explicit (short) tag decision — the
        // stacked button clips anything longer than ~6 monospace chars.
        for (p in HuntPriority.entries) {
            val tag = huntStripSubtitle(p)
            if (p == HuntPriority.LATEST) {
                assertThat(tag).isNull()
            } else {
                assertThat(tag).isNotEmpty()
                assertThat(tag!!.length).isAtMost(6)
            }
        }
    }

    @Test
    fun minSnrChoices_startWithOffAndDescend() {
        assertThat(HUNT_MIN_SNR_CHOICES.first()).isNull()
        val values = HUNT_MIN_SNR_CHOICES.filterNotNull()
        assertThat(values).isEqualTo(values.sortedDescending())
        assertThat(values).isNotEmpty()
    }

    @Test
    fun minSnrChipLabel_formatsWithTypographicMinus() {
        assertThat(huntMinSnrChipLabel(-10)).isEqualTo("−10 dB")
        assertThat(huntMinSnrChipLabel(-20)).isEqualTo("−20 dB")
    }
}
