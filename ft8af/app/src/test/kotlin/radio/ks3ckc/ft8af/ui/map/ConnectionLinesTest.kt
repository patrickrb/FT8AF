package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure unit tests for [buildConnectionLines] — which operator→station lines to
 * draw given the decoded stations and the active TX target. No Android runtime.
 */
class ConnectionLinesTest {

    private data class TestStation(
        override val callsign: String,
        override val lat: Double = 10.0,
        override val lon: Double = 20.0,
        override val isToMe: Boolean = false,
    ) : StationLine

    @Test
    fun txTargetMatched_solidLine() {
        val lines = buildConnectionLines(listOf(TestStation("K1ABC")), "K1ABC")
        assertThat(lines).hasSize(1)
        assertThat(lines[0].style).isEqualTo(LineStyle.SOLID_TX)
        assertThat(lines[0].callsign).isEqualTo("K1ABC")
    }

    @Test
    fun txTargetMatched_caseInsensitive() {
        val lines = buildConnectionLines(listOf(TestStation("K1ABC")), "k1abc")
        assertThat(lines).hasSize(1)
        assertThat(lines[0].style).isEqualTo(LineStyle.SOLID_TX)
    }

    @Test
    fun txTargetUnmatched_noLine() {
        // Target isn't among decoded stations (no grid known) → nothing to draw.
        val lines = buildConnectionLines(listOf(TestStation("K1ABC")), "W9XYZ")
        assertThat(lines).isEmpty()
    }

    @Test
    fun toMeStations_dashedLines() {
        val lines = buildConnectionLines(
            listOf(TestStation("A", isToMe = true), TestStation("B", isToMe = true), TestStation("C")),
            null,
        )
        assertThat(lines).hasSize(2)
        assertThat(lines.map { it.style }.toSet()).containsExactly(LineStyle.DASHED_CALLING_ME)
        assertThat(lines.map { it.callsign }.toSet()).containsExactly("A", "B")
    }

    @Test
    fun txTargetAlsoToMe_noDuplicate() {
        // A station that is both the TX target and calling us draws only the solid line.
        val lines = buildConnectionLines(listOf(TestStation("K1ABC", isToMe = true)), "K1ABC")
        assertThat(lines).hasSize(1)
        assertThat(lines[0].style).isEqualTo(LineStyle.SOLID_TX)
    }

    @Test
    fun cqOrNullTarget_onlyDashedToMe() {
        val lines = buildConnectionLines(
            listOf(TestStation("A", isToMe = true), TestStation("B")),
            null,
        )
        assertThat(lines).hasSize(1)
        assertThat(lines[0].style).isEqualTo(LineStyle.DASHED_CALLING_ME)
        assertThat(lines[0].callsign).isEqualTo("A")
    }

    @Test
    fun blankTarget_treatedAsNoTarget() {
        val lines = buildConnectionLines(listOf(TestStation("A", isToMe = true)), "   ")
        assertThat(lines).hasSize(1)
        assertThat(lines[0].style).isEqualTo(LineStyle.DASHED_CALLING_ME)
    }

    @Test
    fun empty_whenNoTargetNoToMe() {
        val lines = buildConnectionLines(listOf(TestStation("A"), TestStation("B")), null)
        assertThat(lines).isEmpty()
    }
}
