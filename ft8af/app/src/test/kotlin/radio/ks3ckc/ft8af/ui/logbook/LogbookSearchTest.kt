package radio.ks3ckc.ft8af.ui.logbook

import com.k1af.ft8af.log.QSLCallsignRecord
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [filterQsoRecords], the pure search extracted from the Logbook
 * Recent tab so an operator can find a contact by callsign, grid, band, or DXCC.
 *
 * QSLCallsignRecord is a plain POJO, so these run on the bare JVM with no
 * Robolectric.
 */
class LogbookSearchTest {

    private fun record(
        callsign: String,
        grid: String = "",
        band: String = "",
        dxcc: String = "",
        where: String? = null,
    ): QSLCallsignRecord {
        val r = QSLCallsignRecord()
        r.setCallsign(callsign)
        r.setGrid(grid)
        r.setBand(band)
        r.dxccStr = dxcc
        r.where = where
        return r
    }

    private fun calls(records: List<QSLCallsignRecord>): List<String?> =
        records.map { it.callsign }

    private val log = listOf(
        record("K1ABC", grid = "FN42", band = "20M", dxcc = "United States"),
        record("PA3XYZ", grid = "JO22", band = "40M", dxcc = "Netherlands"),
        record("VK2DEF", grid = "QF56", band = "20M", dxcc = "Australia"),
        record("JA1QRP", grid = "PM95", band = "15M", dxcc = "Japan"),
    )

    @Test
    fun blankQueryReturnsEverythingUnchanged() {
        assertThat(filterQsoRecords(log, "")).isEqualTo(log)
        assertThat(filterQsoRecords(log, "   ")).isEqualTo(log)
    }

    @Test
    fun matchesByCallsignPrefix() {
        val result = filterQsoRecords(log, "K1")
        assertThat(calls(result)).containsExactly("K1ABC")
    }

    @Test
    fun matchesCallsignSubstringNotJustPrefix() {
        // "XYZ" appears at the end of a callsign — a substring match (not a prefix
        // match) must still find it so the search is forgiving.
        val busier = listOf(
            record("PA3XYZ", grid = "JO22", band = "40M"),
            record("W1ABC", grid = "FN31", band = "20M"),
            record("K9XYZ", grid = "EN52", band = "15M"),
        )
        val result = filterQsoRecords(busier, "XYZ")
        assertThat(calls(result)).containsExactly("PA3XYZ", "K9XYZ").inOrder()
    }

    @Test
    fun isCaseInsensitive() {
        val result = filterQsoRecords(log, "ja1qrp")
        assertThat(calls(result)).containsExactly("JA1QRP")
    }

    @Test
    fun matchesByGrid() {
        val result = filterQsoRecords(log, "JO22")
        assertThat(calls(result)).containsExactly("PA3XYZ")
    }

    @Test
    fun matchesByBand() {
        val result = filterQsoRecords(log, "20M")
        assertThat(calls(result)).containsExactly("K1ABC", "VK2DEF").inOrder()
    }

    @Test
    fun matchesByDxccEntity() {
        val result = filterQsoRecords(log, "japan")
        assertThat(calls(result)).containsExactly("JA1QRP")
    }

    @Test
    fun matchesByWhereLocation() {
        val withWhere = listOf(record("EA8ABC", where = "Canary Islands"))
        val result = filterQsoRecords(withWhere, "canary")
        assertThat(calls(result)).containsExactly("EA8ABC")
    }

    @Test
    fun trimsSurroundingWhitespaceInQuery() {
        val result = filterQsoRecords(log, "  K1ABC  ")
        assertThat(calls(result)).containsExactly("K1ABC")
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertThat(filterQsoRecords(log, "ZZ9ZZZ")).isEmpty()
    }

    @Test
    fun emptyLogReturnsEmpty() {
        assertThat(filterQsoRecords(emptyList(), "K1ABC")).isEmpty()
    }

    @Test
    fun toleratesNullOptionalFields() {
        // where is null and dxcc empty on many rows; the match must not NPE and
        // should still find the callsign.
        val sparse = listOf(record("N0CALL"))
        val result = filterQsoRecords(sparse, "N0")
        assertThat(calls(result)).containsExactly("N0CALL")
    }
}
