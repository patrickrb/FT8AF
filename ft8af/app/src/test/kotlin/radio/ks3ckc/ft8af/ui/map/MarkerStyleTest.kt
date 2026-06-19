package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure unit tests for [markerStyleFor] / [snrTint] — the marker-appearance
 * decision logic. No Android/Compose runtime is touched (Compose's Color is a
 * pure-Kotlin value class), so these run as plain JVM tests.
 */
class MarkerStyleTest {

    private fun style(
        isCQ: Boolean = false,
        isToMe: Boolean = false,
        isWorked: Boolean = false,
        snr: Int = 0,
        isSelected: Boolean = false,
        zoom: Float = 1f,
    ) = markerStyleFor(isCQ, isToMe, isWorked, snr, isSelected, zoom)

    @Test
    fun toMe_isDiamond() {
        assertThat(style(isToMe = true).shape).isEqualTo(MarkerShape.DIAMOND_TO_ME)
    }

    @Test
    fun cq_isTriangle() {
        assertThat(style(isCQ = true).shape).isEqualTo(MarkerShape.TRIANGLE_CQ)
    }

    @Test
    fun worked_isCheck() {
        assertThat(style(isWorked = true).shape).isEqualTo(MarkerShape.CHECK_WORKED)
    }

    @Test
    fun plainDecode_isDot() {
        assertThat(style().shape).isEqualTo(MarkerShape.DOT_DEFAULT)
    }

    @Test
    fun toMeBeatsWorkedAndCq() {
        // A station both worked and now calling us should read as "calling us".
        assertThat(style(isToMe = true, isWorked = true, isCQ = true).shape)
            .isEqualTo(MarkerShape.DIAMOND_TO_ME)
    }

    @Test
    fun workedBeatsCq() {
        assertThat(style(isWorked = true, isCQ = true).shape).isEqualTo(MarkerShape.CHECK_WORKED)
    }

    @Test
    fun label_hiddenBelowThresholdWhenNotSelected() {
        assertThat(style(zoom = LABEL_ZOOM_THRESHOLD - 0.5f, isSelected = false).showLabel).isFalse()
    }

    @Test
    fun label_shownWhenSelectedEvenZoomedOut() {
        assertThat(style(zoom = 1f, isSelected = true).showLabel).isTrue()
    }

    @Test
    fun label_shownAtOrAboveThreshold() {
        assertThat(style(zoom = LABEL_ZOOM_THRESHOLD).showLabel).isTrue()
        assertThat(style(zoom = LABEL_ZOOM_THRESHOLD + 1f).showLabel).isTrue()
    }

    @Test
    fun selected_isLargerRadius() {
        assertThat(style(isSelected = true).radiusPx).isGreaterThan(style(isSelected = false).radiusPx)
    }

    @Test
    fun snrTint_strongerSnrIsMoreOpaque() {
        val weak = snrTint(radio.ks3ckc.ft8af.theme.Signal, -20)
        val strong = snrTint(radio.ks3ckc.ft8af.theme.Signal, 10)
        assertThat(strong.alpha).isGreaterThan(weak.alpha)
    }

    @Test
    fun snrTint_clampsExtremes() {
        // Tolerance covers Compose Color's 8-bit alpha quantization (~1/255 ≈ 0.004).
        val tooLow = snrTint(radio.ks3ckc.ft8af.theme.Signal, -100)
        val tooHigh = snrTint(radio.ks3ckc.ft8af.theme.Signal, 100)
        assertThat(tooLow.alpha).isWithin(0.01f).of(0.45f)
        assertThat(tooHigh.alpha).isWithin(0.01f).of(1.0f)
    }

    @Test
    fun selected_ignoresSnrFade() {
        // A selected weak-signal marker draws at full strength, not faded.
        val s = style(snr = -24, isSelected = true)
        assertThat(s.fill.alpha).isWithin(1e-4f).of(1.0f)
    }
}
