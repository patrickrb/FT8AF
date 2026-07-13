package radio.ks3ckc.ft8af.ui.waterfall

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the frequency-ruler tick geometry. [rulerTicks] is pure Kotlin (no
 * Android/Compose runtime), so this is a plain JVM test.
 *
 * The waterfall/columnar views map a signal at `hz` to the horizontal fraction
 * `hz / spectrumWidth` (WaterfallView.freq_width = width / spectrumWidth). A
 * ruler tick must therefore sit at that same fraction, NOT be spread evenly to
 * the far edge — otherwise the labels drift from the traces, the TX marker and
 * tap-to-tune whenever `spectrumWidth` is not a multiple of the 500 Hz step.
 */
class FrequencyRulerTicksTest {

    @Test
    fun multipleOf500_lastTickSitsAtFarEdge() {
        // 3500 is an exact multiple of the 500 Hz step, so the top tick equals
        // spectrumWidth and lands at fraction 1.0 (the old even-weight layout was
        // already correct for this case).
        val ticks = rulerTicks(3500)
        assertThat(ticks.map { it.hz }).containsExactly(0, 500, 1000, 1500, 2000, 2500, 3000, 3500).inOrder()
        assertThat(ticks.first().fraction).isEqualTo(0f)
        assertThat(ticks.last().fraction).isEqualTo(1f)
    }

    @Test
    fun multipleOf500_ticksAreEvenlySpaced() {
        // For a 500-multiple width every gap is identical, so the new
        // fraction-based layout is byte-identical to the old equal-weight one.
        val ticks = rulerTicks(3000)
        val fractions = ticks.map { it.fraction }
        val gaps = fractions.zipWithNext { a, b -> b - a }
        gaps.forEach { assertThat(it).isWithin(1e-6f).of(500f / 3000f) }
    }

    @Test
    fun nonMultipleOf500_topTickIsBelowFarEdge() {
        // 2750 is NOT a multiple of 500: the highest tick is 2500, which lives at
        // 2500/2750 = 0.909 of the width, not jammed against the right edge (1.0)
        // where the old layout wrongly placed it. 2750 Hz itself has no label.
        val ticks = rulerTicks(2750)
        assertThat(ticks.map { it.hz }).containsExactly(0, 500, 1000, 1500, 2000, 2500).inOrder()
        assertThat(ticks.last().hz).isEqualTo(2500)
        assertThat(ticks.last().fraction).isWithin(1e-6f).of(2500f / 2750f)
        assertThat(ticks.last().fraction).isLessThan(1f)
    }

    @Test
    fun everyTickFractionEqualsHzOverSpectrumWidth() {
        val spectrumWidth = 4200
        rulerTicks(spectrumWidth).forEach { tick ->
            assertThat(tick.fraction).isWithin(1e-6f).of(tick.hz.toFloat() / spectrumWidth)
        }
    }

    @Test
    fun nonPositiveSpectrumWidth_yieldsSingleZeroTick() {
        // Defensive: the width picker clamps to 2500..5000, but never divide by a
        // non-positive width. A degenerate width collapses to just the 0 Hz tick.
        assertThat(rulerTicks(0).map { it.hz }).containsExactly(0)
        assertThat(rulerTicks(0).first().fraction).isEqualTo(0f)
        assertThat(rulerTicks(-100).map { it.hz }).containsExactly(0)
    }
}
