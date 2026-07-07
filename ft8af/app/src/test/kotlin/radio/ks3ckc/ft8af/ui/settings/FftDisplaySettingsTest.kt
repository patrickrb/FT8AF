package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test

/**
 * Guards the FFT/waterfall developer-knob label mappings (issue #428) in
 * AdvancedSettings: list sizes must match the wire-value ranges the
 * GeneralVariables setters accept, and out-of-range values must fall back to
 * each knob's default label (Hann / Off / Max) — mirroring the clamping
 * setters, so the row never shows a label for a value the pipeline won't use.
 *
 * No Robolectric: R.string.* are plain int constants and the mappings are pure.
 */
class FftDisplaySettingsTest {

    @Test
    fun `window label list covers wire values 0-4 in order`() {
        assertThat(FFT_WINDOW_NAME_RES).containsExactly(
            R.string.settings_fft_window_rect,
            R.string.settings_fft_window_hann,
            R.string.settings_fft_window_hamming,
            R.string.settings_fft_window_blackman,
            R.string.settings_fft_window_blackman_harris,
        ).inOrder()
    }

    @Test
    fun `averaging label list covers wire values 0-2 in order`() {
        assertThat(FFT_AVERAGING_NAME_RES).containsExactly(
            R.string.settings_fft_avg_off,
            R.string.settings_fft_avg_light,
            R.string.settings_fft_avg_heavy,
        ).inOrder()
    }

    @Test
    fun `aggregation label list covers wire values 0-2 in order`() {
        assertThat(BIN_AGGREGATION_NAME_RES).containsExactly(
            R.string.settings_bin_agg_max,
            R.string.settings_bin_agg_avg,
            R.string.settings_bin_agg_rms,
        ).inOrder()
    }

    @Test
    fun `every valid wire value round-trips to its own label`() {
        for (i in FFT_WINDOW_NAME_RES.indices) {
            assertThat(fftWindowLabelRes(i)).isEqualTo(FFT_WINDOW_NAME_RES[i])
        }
        for (i in FFT_AVERAGING_NAME_RES.indices) {
            assertThat(fftAveragingLabelRes(i)).isEqualTo(FFT_AVERAGING_NAME_RES[i])
        }
        for (i in BIN_AGGREGATION_NAME_RES.indices) {
            assertThat(binAggregationLabelRes(i)).isEqualTo(BIN_AGGREGATION_NAME_RES[i])
        }
    }

    @Test
    fun `out-of-range values fall back to each knob's default label`() {
        for (bad in listOf(-1, 5, 99)) {
            assertThat(fftWindowLabelRes(bad)).isEqualTo(R.string.settings_fft_window_hann)
        }
        for (bad in listOf(-1, 3, 99)) {
            assertThat(fftAveragingLabelRes(bad)).isEqualTo(R.string.settings_fft_avg_off)
            assertThat(binAggregationLabelRes(bad)).isEqualTo(R.string.settings_bin_agg_max)
        }
    }
}
