package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [clampVolume] — the pure volume-stepping helper used by the
 * inline TX volume slider's +/- buttons. No Android runtime needed.
 */
class TxStripVolumeTest {

    @Test
    fun `step up by 5 from mid-range`() {
        assertThat(clampVolume(50, 5)).isEqualTo(55)
    }

    @Test
    fun `step down by 5 from mid-range`() {
        assertThat(clampVolume(50, -5)).isEqualTo(45)
    }

    @Test
    fun `step up clamps at 100`() {
        assertThat(clampVolume(98, 5)).isEqualTo(100)
    }

    @Test
    fun `step down clamps at 0`() {
        assertThat(clampVolume(2, -5)).isEqualTo(0)
    }

    @Test
    fun `step up from 100 stays at 100`() {
        assertThat(clampVolume(100, 5)).isEqualTo(100)
    }

    @Test
    fun `step down from 0 stays at 0`() {
        assertThat(clampVolume(0, -5)).isEqualTo(0)
    }

    @Test
    fun `step up from 0`() {
        assertThat(clampVolume(0, 5)).isEqualTo(5)
    }

    @Test
    fun `step down from 100`() {
        assertThat(clampVolume(100, -5)).isEqualTo(95)
    }

    @Test
    fun `large positive delta clamps at 100`() {
        assertThat(clampVolume(50, 200)).isEqualTo(100)
    }

    @Test
    fun `large negative delta clamps at 0`() {
        assertThat(clampVolume(50, -200)).isEqualTo(0)
    }

    @Test
    fun `zero delta returns same value`() {
        assertThat(clampVolume(42, 0)).isEqualTo(42)
    }

    @Test
    fun `boundary value 100 with zero delta`() {
        assertThat(clampVolume(100, 0)).isEqualTo(100)
    }

    @Test
    fun `boundary value 0 with zero delta`() {
        assertThat(clampVolume(0, 0)).isEqualTo(0)
    }
}
