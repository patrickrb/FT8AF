package radio.ks3ckc.ft8af.ui.logbook

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the pure logic extracted from the logbook's [GridSquareHeatmap]:
 * [workedGridFields] (records -> worked field set) and [buildGridHeatmapCells]
 * (the 18x18 Maidenhead field coverage grid).
 *
 * The regression these guard: the coverage grid used to iterate only 10 latitude
 * rows (A..J = latitudes <= +10°N), so every worked field north of that — all of
 * North America, Europe, and Japan — was never generated and could never
 * highlight. Both Maidenhead field axes span A..R (18), so the grid must be
 * 18x18. All plain-JVM (no Robolectric): the helpers take Strings/collections.
 */
class GridSquareHeatmapTest {

    @Test
    fun gridIs18x18WithFullFieldCoverage() {
        val cells = buildGridHeatmapCells(emptySet())
        assertThat(cells).hasSize(18)
        cells.forEach { row -> assertThat(row).hasSize(18) }
        // 324 distinct field designators AA..RR.
        val fields = cells.flatten().map { it.field }
        assertThat(fields).hasSize(324)
        assertThat(fields.toSet()).hasSize(324)
        assertThat(fields).containsAtLeast("AA", "RR", "FN", "JO")
    }

    @Test
    fun northernHemisphereFieldIsGeneratedAndFlagged() {
        // "FN" (New England — latitude field 'N', index 13) lies above +10°N, the
        // exact band the old 10-row grid dropped. It must now exist AND flag worked.
        val cells = buildGridHeatmapCells(setOf("FN"))
        val fn = cells.flatten().singleOrNull { it.field == "FN" }
        assertThat(fn).isNotNull()
        assertThat(fn!!.isWorked).isTrue()
    }

    @Test
    fun highLatitudeFieldsAreAllPresent() {
        val fields = buildGridHeatmapCells(emptySet()).flatten().map { it.field }.toSet()
        // Every longitude field paired with the northernmost latitude letters.
        for (lon in 'A'..'R') {
            assertThat(fields).contains("${lon}R") // +80°N band
            assertThat(fields).contains("${lon}K") // just above +10°N
        }
    }

    @Test
    fun onlyWorkedFieldsAreFlagged() {
        val cells = buildGridHeatmapCells(setOf("FN", "JO"))
        val worked = cells.flatten().filter { it.isWorked }.map { it.field }.toSet()
        assertThat(worked).containsExactly("FN", "JO")
    }

    @Test
    fun workedGridFieldsTakesFirstTwoCharsUpperCased() {
        val fields = workedGridFields(listOf("fn31pr", "JO22", "IO91wm"))
        assertThat(fields).containsExactly("FN", "JO", "IO")
    }

    @Test
    fun workedGridFieldsSkipsNullAndTooShortGrids() {
        val fields = workedGridFields(listOf(null, "", "F", "FN"))
        assertThat(fields).containsExactly("FN")
    }

    @Test
    fun workedGridFieldsDedupes() {
        assertThat(workedGridFields(listOf("FN31", "FN42", "fn20"))).containsExactly("FN")
    }
}
