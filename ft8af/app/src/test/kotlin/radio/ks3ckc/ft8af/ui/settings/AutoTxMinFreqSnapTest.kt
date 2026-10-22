package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [snapAutoTxMinFreq], the slider helper behind the "Lowest auto
 * offset" setting (issue #938) in [TransmissionSettings].
 */
class AutoTxMinFreqSnapTest {

    @Test
    fun `values on the 50 Hz grid are kept`() {
        assertThat(snapAutoTxMinFreq(200)).isEqualTo(200)
        assertThat(snapAutoTxMinFreq(1000)).isEqualTo(1000)
        assertThat(snapAutoTxMinFreq(2000)).isEqualTo(2000)
    }

    @Test
    fun `values snap to the nearest 50 Hz`() {
        assertThat(snapAutoTxMinFreq(1012)).isEqualTo(1000)
        assertThat(snapAutoTxMinFreq(1025)).isEqualTo(1050)
        assertThat(snapAutoTxMinFreq(1049)).isEqualTo(1050)
    }

    @Test
    fun `values are clamped to the finder range`() {
        assertThat(snapAutoTxMinFreq(0)).isEqualTo(200)
        assertThat(snapAutoTxMinFreq(190)).isEqualTo(200)
        assertThat(snapAutoTxMinFreq(2400)).isEqualTo(2000)
    }
}
