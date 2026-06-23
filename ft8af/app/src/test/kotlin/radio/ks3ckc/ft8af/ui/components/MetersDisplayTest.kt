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

    // ---- swrSampleFromRatio ----

    @Test
    fun swrSample_flatMatchIsGood() {
        val s = swrSampleFromRatio(1.0f)
        assertThat(s.type).isEqualTo(MeterType.SWR)
        assertThat(s.zone).isEqualTo(MeterZone.GOOD)
        assertThat(s.fraction).isEqualTo(0f)
        assertThat(s.text).isEqualTo("1.0:1")
    }

    @Test
    fun swrSample_belowOneClampsToOne() {
        // A nonsense sub-1.0 ratio is treated as 1.0, not a negative bar.
        val s = swrSampleFromRatio(0.5f)
        assertThat(s.fraction).isEqualTo(0f)
        assertThat(s.text).isEqualTo("1.0:1")
    }

    @Test
    fun swrSample_zonesByRatio() {
        assertThat(swrSampleFromRatio(1.4f).zone).isEqualTo(MeterZone.GOOD)
        assertThat(swrSampleFromRatio(2.0f).zone).isEqualTo(MeterZone.CAUTION)
        assertThat(swrSampleFromRatio(3.0f).zone).isEqualTo(MeterZone.DANGER)
    }

    @Test
    fun swrSample_barFullAtThreeToOne() {
        assertThat(swrSampleFromRatio(3.0f).fraction).isEqualTo(1f)
        assertThat(swrSampleFromRatio(2.0f).fraction).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun swrSample_veryHighShowsInfinity() {
        assertThat(swrSampleFromRatio(12f).text).isEqualTo("∞")
        assertThat(swrSampleFromRatio(12f).fraction).isEqualTo(1f)
    }

    // ---- swrRatioFromNormalized (numeric inverse of swrRatioToNormalized) ----

    @Test
    fun swrRatioFromNormalized_matchesBreakpoints() {
        // Same breakpoints as MeterProtectionController.normalizedSwrToRatio.
        assertThat(swrRatioFromNormalized(0)).isEqualTo(1.0f)
        assertThat(swrRatioFromNormalized(60)).isWithin(0.001f).of(1.5f)
        assertThat(swrRatioFromNormalized(120)).isWithin(0.001f).of(3.0f)
        assertThat(swrRatioFromNormalized(200)).isWithin(0.001f).of(7.0f)
    }

    @Test
    fun swrSampleFromNormalized_threadsThroughRatio() {
        // normalized 120 ≈ 3.0:1 → DANGER, full bar.
        val s = swrSampleFromNormalized(120)
        assertThat(s.zone).isEqualTo(MeterZone.DANGER)
        assertThat(s.text).isEqualTo("3.0:1")
    }

    // ---- alc samples ----

    @Test
    fun alcSampleSerial_usesTargetWindow() {
        val s = alcSampleSerial(90, targetLow = 60, targetHigh = 120)
        assertThat(s.type).isEqualTo(MeterType.ALC)
        assertThat(s.zone).isEqualTo(MeterZone.GOOD)
        assertThat(s.text).isEqualTo("35%") // 90/255*100 = 35.3 -> 35
    }

    @Test
    fun alcSampleFlexDb_lowIsGoodHighIsDanger() {
        // Flex ALC: low dB = no compression = good; climbing past 0 = overdrive.
        assertThat(alcSampleFlexDb(-120f).zone).isEqualTo(MeterZone.GOOD)
        assertThat(alcSampleFlexDb(5f).zone).isEqualTo(MeterZone.CAUTION)
        assertThat(alcSampleFlexDb(15f).zone).isEqualTo(MeterZone.DANGER)
        // -150..20 dB maps onto 0..1; -150 floors the bar.
        assertThat(alcSampleFlexDb(-150f).fraction).isEqualTo(0f)
        assertThat(alcSampleFlexDb(20f).fraction).isEqualTo(1f)
        assertThat(alcSampleFlexDb(-30f).text).isEqualTo("-30 dB")
    }

    @Test
    fun alcSampleXiegu_scales0to120() {
        assertThat(alcSampleXiegu(0f).zone).isEqualTo(MeterZone.GOOD)
        assertThat(alcSampleXiegu(60f).zone).isEqualTo(MeterZone.CAUTION)
        assertThat(alcSampleXiegu(100f).zone).isEqualTo(MeterZone.DANGER)
        assertThat(alcSampleXiegu(60f).fraction).isWithin(0.001f).of(0.5f)
    }

    // ---- informational meters ----

    @Test
    fun powerSample_isNeutralAndRelativeToMax() {
        val s = powerSample(25f, maxWatts = 100f)
        assertThat(s.type).isEqualTo(MeterType.POWER)
        assertThat(s.zone).isEqualTo(MeterZone.NEUTRAL)
        assertThat(s.fraction).isWithin(0.001f).of(0.25f)
        assertThat(s.text).isEqualTo("25 W")
    }

    @Test
    fun powerSample_guardsZeroMax() {
        assertThat(powerSample(25f, maxWatts = 0f).fraction).isEqualTo(0f)
    }

    @Test
    fun sMeterSample_isNeutral() {
        val s = sMeterSampleDbm(-75f)
        assertThat(s.zone).isEqualTo(MeterZone.NEUTRAL)
        assertThat(s.text).isEqualTo("-75 dBm")
        assertThat(s.fraction).isWithin(0.001f).of((-75f + 120f) / 90f)
    }

    @Test
    fun voltSample_greenInBatteryBandRedAtExtremes() {
        assertThat(voltSample(13.8f).zone).isEqualTo(MeterZone.GOOD)
        assertThat(voltSample(11.0f).zone).isEqualTo(MeterZone.CAUTION)
        assertThat(voltSample(9.0f).zone).isEqualTo(MeterZone.DANGER)
        assertThat(voltSample(13.8f).text).isEqualTo("13.8 V")
    }

    @Test
    fun tempSample_zonesByCelsius() {
        assertThat(tempSample(40f).zone).isEqualTo(MeterZone.GOOD)
        assertThat(tempSample(60f).zone).isEqualTo(MeterZone.CAUTION)
        assertThat(tempSample(75f).zone).isEqualTo(MeterZone.DANGER)
        assertThat(tempSample(50f).text).isEqualTo("50°C")
    }

    // ---- enabled / visible ----

    @Test
    fun enabledMeterTypes_defaultsAndOrder() {
        val set =
            enabledMeterTypes(
                swr = true,
                alc = true,
                power = false,
                smeter = false,
                voltage = false,
                temp = false,
            )
        assertThat(set).containsExactly(MeterType.SWR, MeterType.ALC).inOrder()
    }

    @Test
    fun visibleMeters_intersectsAvailableWithEnabledInOrder() {
        // Rig reports SWR, ALC, POWER (in a scrambled order); user enabled SWR+POWER.
        val available =
            listOf(
                powerSample(10f),
                swrSampleFromRatio(1.2f),
                alcSampleFlexDb(-100f),
            )
        val enabled = setOf(MeterType.SWR, MeterType.POWER)
        val visible = visibleMeters(available, enabled)
        // ALC dropped (not enabled); result in display (ordinal) order: SWR then POWER.
        assertThat(visible.map { it.type })
            .containsExactly(MeterType.SWR, MeterType.POWER).inOrder()
    }

    @Test
    fun visibleMeters_emptyWhenRigReportsNothing() {
        assertThat(visibleMeters(emptyList(), setOf(MeterType.SWR, MeterType.ALC))).isEmpty()
    }
}
