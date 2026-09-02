package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [clampAlcLow] and [clampAlcHigh] — the pure helpers that bound
 * the ALC auto-volume target sliders in [TransmissionSettings].
 *
 * These replace six inline `coerceIn(...)` expressions that could crash with an
 * empty-range `IllegalArgumentException` on the UI thread when a restored or
 * hand-edited config persisted an out-of-order ALC pair (e.g. `alcTargetHigh`
 * below 20, or `alcTargetLow` above 240) — the range's max fell below its min.
 * The tests assert both the crash-proofing and byte-identical behaviour on the
 * happy path.
 */
class AlcTargetClampTest {

    // =====================================================================
    // clampAlcLow — byte-identical to coerceIn(10, minOf(200, high - 10))
    //                when high >= 20
    // =====================================================================

    @Test
    fun `low with default high keeps a mid-range value`() {
        // high = 100 → upper = min(200, 90) = 90
        assertThat(clampAlcLow(60, 100)).isEqualTo(60)
    }

    @Test
    fun `low is floored at 10`() {
        assertThat(clampAlcLow(0, 100)).isEqualTo(10)
        assertThat(clampAlcLow(-50, 100)).isEqualTo(10)
    }

    @Test
    fun `low is capped 10 below high`() {
        // high = 100 → upper = 90
        assertThat(clampAlcLow(200, 100)).isEqualTo(90)
        assertThat(clampAlcLow(95, 100)).isEqualTo(90)
    }

    @Test
    fun `low is capped at 200 when high is very large`() {
        assertThat(clampAlcLow(999, 500)).isEqualTo(200)
    }

    // ---- Empty-range crash cases (max < min in the old inline coerceIn) ----

    @Test
    fun `low does not crash when high is below the gap`() {
        // Old: coerceIn(10, minOf(200, 15 - 10) = 5) → IllegalArgumentException.
        assertThat(clampAlcLow(60, 15)).isEqualTo(10)
    }

    @Test
    fun `low does not crash when high equals the minimum`() {
        // high = 20 → upper = minOf(200, 10) = 10 → coerceIn(10, 10) = 10.
        assertThat(clampAlcLow(60, 20)).isEqualTo(10)
    }

    @Test
    fun `low does not crash when high is zero or negative`() {
        assertThat(clampAlcLow(60, 0)).isEqualTo(10)
        assertThat(clampAlcLow(60, -100)).isEqualTo(10)
    }

    // =====================================================================
    // clampAlcHigh — byte-identical to coerceIn(low + 10, 250) when low <= 240
    // =====================================================================

    @Test
    fun `high with default low keeps a mid-range value`() {
        // low = 60 → lower = 70
        assertThat(clampAlcHigh(100, 60)).isEqualTo(100)
    }

    @Test
    fun `high is capped at 250`() {
        assertThat(clampAlcHigh(999, 60)).isEqualTo(250)
    }

    @Test
    fun `high is floored 10 above low`() {
        // low = 60 → lower = 70
        assertThat(clampAlcHigh(50, 60)).isEqualTo(70)
        assertThat(clampAlcHigh(0, 60)).isEqualTo(70)
    }

    // ---- Empty-range crash cases (min > max in the old inline coerceIn) ----

    @Test
    fun `high does not crash when low is above the ceiling minus gap`() {
        // Old: coerceIn(245 + 10 = 255, 250) → 255 > 250 → IllegalArgumentException.
        assertThat(clampAlcHigh(100, 245)).isEqualTo(250)
    }

    @Test
    fun `high does not crash when low equals the ceiling minus gap`() {
        // low = 240 → lower = 250 → coerceIn(250, 250) = 250.
        assertThat(clampAlcHigh(100, 240)).isEqualTo(250)
    }

    @Test
    fun `high does not crash when low is far above the ceiling`() {
        assertThat(clampAlcHigh(100, 1000)).isEqualTo(250)
    }

    // =====================================================================
    // Round-trip: the sliders' own clamping keeps the pair ordered so a
    // second clamp on the sibling value never inverts.
    // =====================================================================

    @Test
    fun `clamped pair stays ordered with a 10-unit gap`() {
        // Start from a corrupted pair and clamp each toward the other.
        val high = clampAlcHigh(5, 250) // low=250 (corrupt) → high floored to 250
        val low = clampAlcLow(250, high) // → capped 10 below high
        assertThat(high - low).isAtLeast(10)
        assertThat(low).isAtLeast(10)
        assertThat(high).isAtMost(250)
    }
}
