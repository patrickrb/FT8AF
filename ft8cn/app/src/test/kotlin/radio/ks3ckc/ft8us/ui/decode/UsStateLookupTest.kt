package radio.ks3ckc.ft8us.ui.decode

import com.google.common.truth.Truth.assertThat
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
 * couple of stable, documented entries are asserted directly (BK08 -> HI,
 * BO19 -> AK) plus the guard branches that short-circuit before any asset read.
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
        assertThat(UsStateLookup.stateFromGrid(context, "BK0")).isNull()
    }

    @Test
    fun stateFromGrid_knownHawaiiGrid_returnsHI() {
        assertThat(UsStateLookup.stateFromGrid(context, "BK08")).isEqualTo("HI")
    }

    @Test
    fun stateFromGrid_knownAlaskaGrid_returnsAK() {
        assertThat(UsStateLookup.stateFromGrid(context, "BO19")).isEqualTo("AK")
    }

    @Test
    fun stateFromGrid_lowercaseGrid_isUppercasedBeforeLookup() {
        assertThat(UsStateLookup.stateFromGrid(context, "bk08")).isEqualTo("HI")
    }

    @Test
    fun stateFromGrid_usesOnlyFirstFourCharacters() {
        // A 6-character grid (subsquare appended) keys on its first four chars.
        assertThat(UsStateLookup.stateFromGrid(context, "BK08aa")).isEqualTo("HI")
    }

    @Test
    fun stateFromGrid_unknownGrid_returnsNull() {
        // A syntactically valid grid that is not in the US table.
        assertThat(UsStateLookup.stateFromGrid(context, "ZZ99")).isNull()
    }
}
