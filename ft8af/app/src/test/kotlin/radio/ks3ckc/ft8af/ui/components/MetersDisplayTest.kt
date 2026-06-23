package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the pure meters-HUD logic in MetersDisplay.kt — bar geometry,
 * ALC/SWR zone classification, freshness (live / last-TX / none), and the
 * top-edge open-gesture commit rule. No Compose/Android types, so these run as
 * plain JUnit with no Robolectric runner.
 */
class MetersDisplayTest {

    // ---- meterBarFraction ----

    @Test
    fun barFraction_spansZeroToOne() {
        assertThat(meterBarFraction(0)).isEqualTo(0f)
        assertThat(meterBarFraction(255)).isEqualTo(1f)
    }

    @Test
    fun barFraction_midScale() {
        // 128/255 ≈ 0.502
        assertThat(meterBarFraction(128)).isWithin(0.001f).of(128f / 255f)
    }

    @Test
    fun barFraction_clampsOutOfRange() {
        // Defensive: a stray negative or over-255 value can't produce a bar
        // outside the track.
        assertThat(meterBarFraction(-10)).isEqualTo(0f)
        assertThat(meterBarFraction(999)).isEqualTo(1f)
    }

    // ---- alcPercent ----

    @Test
    fun alcPercent_roundsToWholePercent() {
        assertThat(alcPercent(0)).isEqualTo(0)
        assertThat(alcPercent(255)).isEqualTo(100)
        assertThat(alcPercent(128)).isEqualTo(50) // 128/255*100 = 50.2 -> 50
    }

    // ---- alcZone ----

    @Test
    fun alcZone_zeroIsIdle() {
        assertThat(alcZone(0, targetLow = 60, targetHigh = 120)).isEqualTo(MeterZone.IDLE)
        assertThat(alcZone(-1, targetLow = 60, targetHigh = 120)).isEqualTo(MeterZone.IDLE)
    }

    @Test
    fun alcZone_belowWindowIsCaution() {
        // Under-driven: there's headroom to push harder.
        assertThat(alcZone(40, targetLow = 60, targetHigh = 120)).isEqualTo(MeterZone.CAUTION)
    }

    @Test
    fun alcZone_insideWindowIsGood() {
        assertThat(alcZone(60, targetLow = 60, targetHigh = 120)).isEqualTo(MeterZone.GOOD)
        assertThat(alcZone(90, targetLow = 60, targetHigh = 120)).isEqualTo(MeterZone.GOOD)
        assertThat(alcZone(120, targetLow = 60, targetHigh = 120)).isEqualTo(MeterZone.GOOD)
    }

    @Test
    fun alcZone_aboveWindowIsDanger() {
        // Overdriven distorts the signal.
        assertThat(alcZone(150, targetLow = 60, targetHigh = 120)).isEqualTo(MeterZone.DANGER)
    }

    // ---- swrZone ----

    @Test
    fun swrZone_zeroIsIdle() {
        assertThat(swrZone(0, haltThreshold = 120)).isEqualTo(MeterZone.IDLE)
    }

    @Test
    fun swrZone_wellUnderThresholdIsGood() {
        // 60/120 = 0.5 < 0.6
        assertThat(swrZone(60, haltThreshold = 120)).isEqualTo(MeterZone.GOOD)
    }

    @Test
    fun swrZone_approachingThresholdIsCaution() {
        // 96/120 = 0.8, between 0.6 and 1.0
        assertThat(swrZone(96, haltThreshold = 120)).isEqualTo(MeterZone.CAUTION)
    }

    @Test
    fun swrZone_atOrOverThresholdIsDanger() {
        // At the threshold and beyond — the same point protection halts TX.
        assertThat(swrZone(120, haltThreshold = 120)).isEqualTo(MeterZone.DANGER)
        assertThat(swrZone(200, haltThreshold = 120)).isEqualTo(MeterZone.DANGER)
    }

    @Test
    fun swrZone_invalidThresholdNeverFalseAlarms() {
        // A zero/garbage threshold can't define "danger"; show GOOD rather than
        // flashing red on every reading.
        assertThat(swrZone(200, haltThreshold = 0)).isEqualTo(MeterZone.GOOD)
    }

    // ---- meterFreshness ----

    @Test
    fun freshness_liveWhileTransmittingWithData() {
        assertThat(meterFreshness(isTransmitting = true, hasData = true))
            .isEqualTo(MeterFreshness.LIVE)
    }

    @Test
    fun freshness_lastTxWhenIdleButHaveData() {
        assertThat(meterFreshness(isTransmitting = false, hasData = true))
            .isEqualTo(MeterFreshness.LAST_TX)
    }

    @Test
    fun freshness_noneWhenNoDataEver() {
        // Unsupported rig, or no TX yet: a 0 reading would otherwise masquerade
        // as a real value, so we gate on hasData.
        assertThat(meterFreshness(isTransmitting = false, hasData = false))
            .isEqualTo(MeterFreshness.NONE)
        // Even mid-TX, if the rig has reported nothing it's still NONE.
        assertThat(meterFreshness(isTransmitting = true, hasData = false))
            .isEqualTo(MeterFreshness.NONE)
    }

    // ---- shouldOpenFromEdgeDrag ----

    @Test
    fun edgeDrag_opensPastThreshold() {
        assertThat(shouldOpenFromEdgeDrag(totalDy = 40f, thresholdPx = 36f)).isTrue()
        assertThat(shouldOpenFromEdgeDrag(totalDy = 36f, thresholdPx = 36f)).isTrue()
    }

    @Test
    fun edgeDrag_ignoresShortOrUpwardDrag() {
        // A small downward nudge shouldn't open.
        assertThat(shouldOpenFromEdgeDrag(totalDy = 10f, thresholdPx = 36f)).isFalse()
        // An upward drag (negative dy) must never open.
        assertThat(shouldOpenFromEdgeDrag(totalDy = -50f, thresholdPx = 36f)).isFalse()
    }
}
