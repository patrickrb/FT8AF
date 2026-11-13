package radio.ks3ckc.ft8af.ui.logbook

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.log.QSLCallsignRecord
import org.junit.Test

/**
 * Unit tests for [computeBestDx] — the pure reducer behind the logbook Stats
 * "Best DX" card. The real card measures great-circle distance via
 * [com.k1af.ft8af.maidenhead.MaidenheadGrid]; here the distance is injected as a
 * fake grid -> km function so the selection/filtering logic is testable on the
 * plain JVM (no Robolectric, no operator grid).
 */
class BestDxTest {

    private fun rec(callsign: String?, grid: String?): QSLCallsignRecord =
        QSLCallsignRecord().apply {
            setCallsign(callsign)
            setGrid(grid)
        }

    // Maps a (trimmed) grid to a canned distance; unknown grids are "unparseable".
    private val fakeDist: (String) -> Double? = { grid ->
        when (grid.uppercase()) {
            "AA00" -> 100.0
            "BB11" -> 5000.0
            "CC22" -> 18000.0
            else -> null
        }
    }

    @Test
    fun picksTheFurthestStation() {
        val best = computeBestDx(
            listOf(rec("A1AA", "AA00"), rec("B2BB", "BB11"), rec("C3CC", "CC22")),
            fakeDist,
        )
        assertThat(best).isNotNull()
        assertThat(best!!.callsign).isEqualTo("C3CC")
        assertThat(best.grid).isEqualTo("CC22")
        assertThat(best.distanceKm).isEqualTo(18000.0)
    }

    @Test
    fun returnsNullForEmptyLog() {
        assertThat(computeBestDx(emptyList(), fakeDist)).isNull()
    }

    @Test
    fun skipsRecordsWithUnparseableGrid() {
        val best = computeBestDx(listOf(rec("A1AA", "ZZ99"), rec("B2BB", "BB11")), fakeDist)
        assertThat(best!!.callsign).isEqualTo("B2BB")
    }

    @Test
    fun skipsBlankCallsignOrGrid() {
        val best = computeBestDx(
            listOf(rec("", "CC22"), rec("B2BB", ""), rec("D4DD", "BB11")),
            fakeDist,
        )
        assertThat(best!!.callsign).isEqualTo("D4DD")
        assertThat(best.distanceKm).isEqualTo(5000.0)
    }

    @Test
    fun returnsNullWhenNothingIsMeasurable() {
        // Distance fn returns null for everything — e.g. operator grid not set.
        assertThat(computeBestDx(listOf(rec("A1AA", "AA00")), { null })).isNull()
    }

    @Test
    fun skipsZeroAndNaNDistances() {
        // A 0 km hit (same grid as the operator) and a NaN must never win over a
        // real distance, mirroring MaidenheadGrid's coincident-point behaviour.
        val best = computeBestDx(
            listOf(rec("SAME", "AA00"), rec("BADGRID", "CC22"), rec("FAR", "BB11")),
            { grid ->
                when (grid) {
                    "AA00" -> 0.0
                    "CC22" -> Double.NaN
                    "BB11" -> 5000.0
                    else -> null
                }
            },
        )
        assertThat(best!!.callsign).isEqualTo("FAR")
    }

    @Test
    fun trimsCallsignAndGrid() {
        val best = computeBestDx(listOf(rec(" C3CC ", " CC22 ")), fakeDist)
        assertThat(best!!.callsign).isEqualTo("C3CC")
        assertThat(best.grid).isEqualTo("CC22")
    }
}
