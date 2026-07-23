package radio.ks3ckc.ft8af.ui.logbook

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for [gridSquaresWorked], the VUCC (grid-square) counter that backs
 * both the Stats-tab "VUCC Grid Squares" progress bar and the Awards-tab VUCC
 * card. A grid square is the first four Maidenhead characters (e.g. "FN31");
 * VUCC counts *unique* squares worked, so the helper de-dupes and ignores
 * partial/blank grids. All plain-JVM (the helper takes Strings).
 *
 * The regression these guard: the Awards-tab VUCC card used to be hardcoded to
 * 0, so grid chasers saw "0 / 100" no matter how many squares they had worked.
 */
class GridSquaresWorkedTest {

    @Test
    fun countsUniqueFourCharSquares() {
        // FN31 and FN42 are different squares within the same field; both count.
        assertThat(gridSquaresWorked(listOf("FN31", "FN42", "JO22"))).isEqualTo(3)
    }

    @Test
    fun dedupesRepeatedSquares() {
        // Same square worked three times (extra chars truncated to 4) counts once.
        assertThat(gridSquaresWorked(listOf("FN31", "FN31pr", "fn31"))).isEqualTo(1)
    }

    @Test
    fun skipsNullBlankAndTooShortGrids() {
        assertThat(gridSquaresWorked(listOf(null, "", "  ", "FN", "FN3"))).isEqualTo(0)
    }

    @Test
    fun truncatesSixCharGridToItsSquare() {
        // FN31pr and FN31aa share the FN31 square.
        assertThat(gridSquaresWorked(listOf("FN31pr", "FN31aa"))).isEqualTo(1)
    }

    @Test
    fun mixedValidAndInvalidGrids() {
        assertThat(gridSquaresWorked(listOf("FN31", null, "bad", "JO22", "JO22"))).isEqualTo(2)
    }

    @Test
    fun upperCasesLocaleInsensitively() {
        // Under a Turkish locale a default-locale uppercase() maps "i" to the
        // dotted capital "İ", so "io91" and "IO91" would count as two squares.
        // Locale.ROOT keeps both "IO91", so they de-dupe to one.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertThat(gridSquaresWorked(listOf("io91wm", "IO91WM"))).isEqualTo(1)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
