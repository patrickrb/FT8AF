package radio.ks3ckc.ft8us.ui.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.ft8us.pota.model.PotaQso

/**
 * Unit tests for [buildActivationMapData]. Robolectric is required because it
 * delegates into MaidenheadGrid, which uses Play-Services `LatLng`.
 */
@RunWith(RobolectricTestRunner::class)
class PotaActivationMapDataTest {

    private val posTol = 0.05 // degrees

    private fun qso(callsign: String, grid: String, myGrid: String = "") = PotaQso(
        id = callsign.hashCode().toLong(),
        callsign = callsign,
        grid = grid,
        band = "20M",
        mode = "FT8",
        rstSent = "-10",
        rstRcvd = "-12",
        qsoDate = "20260101",
        timeOn = "120000",
        sig = "POTA",
        sigInfo = null,
        myGrid = myGrid,
    )

    @Test
    fun `contacts with blank or unparseable grids are dropped`() {
        val data = buildActivationMapData(
            listOf(
                qso("W1AW", "FN31", myGrid = "FN42"),
                qso("N0CALL", "", myGrid = "FN42"),     // blank grid
                qso("BADGRD", "ABC", myGrid = "FN42"),  // unparseable
            ),
            fallbackOperatorGrid = null,
        )
        assertThat(data).isNotNull()
        assertThat(data!!.contacts.map { it.callsign }).containsExactly("W1AW")
    }

    @Test
    fun `operator location comes from the first usable myGrid`() {
        val data = buildActivationMapData(
            listOf(
                qso("W1AW", "IO91", myGrid = ""),       // no myGrid here
                qso("K2ABC", "JN58", myGrid = "FN42"),  // first usable myGrid
            ),
            fallbackOperatorGrid = null,
        )
        assertThat(data).isNotNull()
        // FN42 ~ Boston (42.5N, -71.0W).
        assertThat(data!!.operatorLat).isWithin(posTol).of(42.5)
        assertThat(data.operatorLon).isWithin(posTol).of(-71.0)
        assertThat(data.contacts).hasSize(2)
    }

    @Test
    fun `fallback operator grid is used when no QSO carries myGrid`() {
        val data = buildActivationMapData(
            listOf(qso("W1AW", "JN58", myGrid = "")),
            fallbackOperatorGrid = "FN42",
        )
        assertThat(data).isNotNull()
        assertThat(data!!.operatorLat).isWithin(posTol).of(42.5)
    }

    @Test
    fun `returns null when there are no plottable contacts`() {
        val data = buildActivationMapData(
            listOf(qso("N0CALL", "", myGrid = "FN42")),
            fallbackOperatorGrid = "FN42",
        )
        assertThat(data).isNull()
    }

    @Test
    fun `returns null when the operator location is unknown`() {
        // Plottable contact, but no myGrid anywhere and no fallback.
        val data = buildActivationMapData(
            listOf(qso("W1AW", "FN31", myGrid = "")),
            fallbackOperatorGrid = null,
        )
        assertThat(data).isNull()
    }
}
