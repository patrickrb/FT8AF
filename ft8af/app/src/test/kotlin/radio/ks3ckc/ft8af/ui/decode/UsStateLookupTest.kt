package radio.ks3ckc.ft8af.ui.decode

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Coverage for [UsStateLookup.stateFromGrid] — maps a Maidenhead grid to a US
 * state code using the bundled us_grid_states.json asset.
 *
 * Robolectric is required because the lookup table is loaded from the app's
 * assets via [android.content.Context.getAssets]; the JSON ships in
 * src/main/assets and Robolectric serves it to the test.
 *
 * The map is keyed by the uppercased first four characters of the grid; only a
 * couple of stable, documented entries are asserted directly (BL11 -> HI is
 * Honolulu, BP51 -> AK is Anchorage) plus the guard branches that short-circuit
 * before any asset read. The table itself is generated — see
 * scripts/generate-us-grid-states.mjs — so anchors are city grids that no
 * regeneration can plausibly move, not arbitrary cells.
 */
@RunWith(RobolectricTestRunner::class)
class UsStateLookupTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun stateFromGrid_nullGrid_returnsNull() {
        assertThat(UsStateLookup.stateFromGrid(context, null)).isNull()
    }

    @Test
    fun stateFromGrid_emptyGrid_returnsNull() {
        assertThat(UsStateLookup.stateFromGrid(context, "")).isNull()
    }

    @Test
    fun stateFromGrid_tooShortGrid_returnsNull() {
        // Fewer than 4 characters cannot key the 4-char map.
        assertThat(UsStateLookup.stateFromGrid(context, "BL1")).isNull()
    }

    @Test
    fun stateFromGrid_knownHawaiiGrid_returnsHI() {
        assertThat(UsStateLookup.stateFromGrid(context, "BL11")).isEqualTo("HI")
    }

    @Test
    fun stateFromGrid_knownAlaskaGrid_returnsAK() {
        assertThat(UsStateLookup.stateFromGrid(context, "BP51")).isEqualTo("AK")
    }

    @Test
    fun stateFromGrid_lowercaseGrid_isUppercasedBeforeLookup() {
        assertThat(UsStateLookup.stateFromGrid(context, "bl11")).isEqualTo("HI")
    }

    @Test
    fun stateFromGrid_usesOnlyFirstFourCharacters() {
        // A 6-character grid (subsquare appended) keys on its first four chars.
        assertThat(UsStateLookup.stateFromGrid(context, "BL11ah")).isEqualTo("HI")
    }

    @Test
    fun stateFromGrid_unknownGrid_returnsNull() {
        // A syntactically valid grid that is not in the US table.
        assertThat(UsStateLookup.stateFromGrid(context, "ZZ99")).isNull()
    }

    @Test
    fun stateFromGrid_isLocaleIndependent_underTurkishDefault() {
        // The grid is uppercased before lookup. Under the Turkish locale a
        // lower-case 'i' uppercases to the dotted 'İ' (U+0130), which would miss
        // the ASCII keys — so the lookup must normalize with Locale.ROOT. Guard
        // that a lower-case grid still resolves regardless of the default locale.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertThat(UsStateLookup.stateFromGrid(context, "bl11")).isEqualTo("HI")
        } finally {
            Locale.setDefault(previous)
        }
    }
}
