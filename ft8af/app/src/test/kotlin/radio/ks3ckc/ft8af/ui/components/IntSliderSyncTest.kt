package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [shouldSyncSliderPos] and [pixelToValue] — the pure helpers
 * used by [IntSlider].
 */
class IntSliderSyncTest {

    // =====================================================================
    // shouldSyncSliderPos
    // =====================================================================

    // ---- During drag: raw position rounds to the same int → no sync ----

    @Test
    fun `mid-range drag position rounds to external value`() {
        assertThat(shouldSyncSliderPos(45.3f, 45)).isFalse()
    }

    @Test
    fun `drag position just below half rounds down`() {
        assertThat(shouldSyncSliderPos(45.4f, 45)).isFalse()
    }

    @Test
    fun `drag position at half rounds up`() {
        assertThat(shouldSyncSliderPos(45.5f, 46)).isFalse()
    }

    @Test
    fun `exact integer position matches`() {
        assertThat(shouldSyncSliderPos(50.0f, 50)).isFalse()
    }

    // ---- External change: +/- button moves value away from raw → sync ----

    @Test
    fun `button increments value past raw position`() {
        assertThat(shouldSyncSliderPos(45.3f, 50)).isTrue()
    }

    @Test
    fun `button decrements value below raw position`() {
        assertThat(shouldSyncSliderPos(45.3f, 40)).isTrue()
    }

    @Test
    fun `value clamped during drag triggers sync`() {
        assertThat(shouldSyncSliderPos(195.3f, 190)).isTrue()
    }

    // ---- Boundary values ----

    @Test
    fun `zero position and value`() {
        assertThat(shouldSyncSliderPos(0.0f, 0)).isFalse()
    }

    @Test
    fun `max position and value`() {
        assertThat(shouldSyncSliderPos(100.0f, 100)).isFalse()
    }

    @Test
    fun `negative raw position rounds to zero`() {
        assertThat(shouldSyncSliderPos(-0.3f, 0)).isFalse()
    }

    @Test
    fun `external value differs by exactly one`() {
        assertThat(shouldSyncSliderPos(45.3f, 46)).isTrue()
    }

    // =====================================================================
    // pixelToValue
    // =====================================================================

    @Test
    fun `pixel at track start returns range start`() {
        assertThat(pixelToValue(20f, 20f, 320f, 0f, 100f)).isEqualTo(0f)
    }

    @Test
    fun `pixel at track end returns range end`() {
        assertThat(pixelToValue(320f, 20f, 320f, 0f, 100f)).isEqualTo(100f)
    }

    @Test
    fun `pixel at track midpoint returns range midpoint`() {
        assertThat(pixelToValue(170f, 20f, 320f, 0f, 100f)).isEqualTo(50f)
    }

    @Test
    fun `pixel before track start clamps to range start`() {
        assertThat(pixelToValue(0f, 20f, 320f, 0f, 100f)).isEqualTo(0f)
    }

    @Test
    fun `pixel past track end clamps to range end`() {
        assertThat(pixelToValue(400f, 20f, 320f, 0f, 100f)).isEqualTo(100f)
    }

    @Test
    fun `non-zero range start maps correctly`() {
        // Track 20..320 (300px), range 10..200 (190 units)
        // Midpoint pixel = 170, fraction = 0.5, value = 10 + 0.5*190 = 105
        assertThat(pixelToValue(170f, 20f, 320f, 10f, 200f)).isEqualTo(105f)
    }

    @Test
    fun `degenerate track width returns range start`() {
        assertThat(pixelToValue(50f, 50f, 50f, 0f, 100f)).isEqualTo(0f)
    }
}
