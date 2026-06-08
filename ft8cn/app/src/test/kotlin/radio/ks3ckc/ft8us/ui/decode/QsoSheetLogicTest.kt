package radio.ks3ckc.ft8us.ui.decode

import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the (pure) QSO-sheet helpers [computeDistanceText] and
 * [gridToLatLon] added alongside the path map. Robolectric is required because
 * both delegate into [MaidenheadGrid], which uses Play-Services `LatLng`.
 *
 * Distance assertions derive the expected unit from the live km/miles setting
 * (mirroring MaidenheadGridTest) so they don't depend on the preference.
 */
@RunWith(RobolectricTestRunner::class)
class QsoSheetLogicTest {

    private val posTol = 0.05 // degrees

    // ---------- computeDistanceText ----------

    @Test
    fun `distance is the placeholder when either grid is missing`() {
        assertThat(computeDistanceText(null, "FN42")).isEqualTo("--")
        assertThat(computeDistanceText("FN42", null)).isEqualTo("--")
        assertThat(computeDistanceText("FN42", "")).isEqualTo("--")
        assertThat(computeDistanceText("", "")).isEqualTo("--")
    }

    @Test
    fun `distance is the placeholder when a grid cannot be parsed`() {
        // "ABC" is an unsupported length, so getDist returns 0 -> getDistStr ""
        // -> we surface the placeholder rather than a misleading "0".
        assertThat(computeDistanceText("FN42", "ABC")).isEqualTo("--")
    }

    @Test
    fun `distance between distinct grids is a number with the current unit`() {
        val label = MaidenheadGrid.getDistUnitLabel()
        val text = computeDistanceText("FN42", "IO91")
        assertThat(text).isNotEqualTo("--")
        assertThat(text).endsWith(label)
        assertThat(text).matches("\\d+ " + label)
    }

    // ---------- gridToLatLon ----------

    @Test
    fun `gridToLatLon returns null for blank or unparseable grids`() {
        assertThat(gridToLatLon(null)).isNull()
        assertThat(gridToLatLon("")).isNull()
        assertThat(gridToLatLon("ABC")).isNull()   // bad length
        assertThat(gridToLatLon("RR73")).isNull()  // greeting collision, rejected
    }

    @Test
    fun `gridToLatLon decodes a four-char grid to its square center`() {
        // FN42 ~ Boston: ~42.5N, -71.0W. Pair is (lat, lon).
        val p = gridToLatLon("FN42")
        assertThat(p).isNotNull()
        assertThat(p!!.first).isWithin(posTol).of(42.5)
        assertThat(p.second).isWithin(posTol).of(-71.0)
    }
}
