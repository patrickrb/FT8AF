package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test

/**
 * Unit tests for [txStatusVisuals] — the pure status-row state mapping for the redesigned TX
 * strip. Verifies the three states and that Tuning wins over Transmitting, and that the "next
 * window" countdown only shows while Listening.
 */
class TxStatusVisualsTest {

    @Test
    fun `listening when idle, shows the countdown`() {
        val v = txStatusVisuals(isTransmitting = false, isTuning = false)
        assertThat(v.labelRes).isEqualTo(R.string.tx_status_listening)
        assertThat(v.listening).isTrue()
    }

    @Test
    fun `transmitting hides the countdown`() {
        val v = txStatusVisuals(isTransmitting = true, isTuning = false)
        assertThat(v.labelRes).isEqualTo(R.string.tx_status_transmitting)
        assertThat(v.listening).isFalse()
    }

    @Test
    fun `tuning hides the countdown`() {
        val v = txStatusVisuals(isTransmitting = false, isTuning = true)
        assertThat(v.labelRes).isEqualTo(R.string.tx_status_tuning)
        assertThat(v.listening).isFalse()
    }

    @Test
    fun `tuning takes precedence over transmitting`() {
        val v = txStatusVisuals(isTransmitting = true, isTuning = true)
        assertThat(v.labelRes).isEqualTo(R.string.tx_status_tuning)
    }
}
