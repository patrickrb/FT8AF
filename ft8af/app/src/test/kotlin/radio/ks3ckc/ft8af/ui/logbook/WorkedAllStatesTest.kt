package radio.ks3ckc.ft8af.ui.logbook

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for the Worked All States (WAS) award logic: [workedStates]
 * (grids -> distinct worked-state set, resolved through an injected grid->state
 * lambda) and [neededStates] (the 50 WAS states minus those already worked).
 *
 * All plain-JVM (no Robolectric) — the helpers take Strings/collections and a
 * plain lambda, so no Android context is needed. The production caller injects
 * `UsStateLookup.stateFromGrid(context, grid)` as the resolver.
 */
class WorkedAllStatesTest {

    // A tiny fake grid->state resolver for the tests, independent of the app's
    // real (asset-backed) UsStateLookup.
    private val fakeGridStates = mapOf(
        "FN31" to "CT",
        "FN42" to "MA",
        "DM79" to "CO",
        "CM87" to "CA",
        "BL11" to "HI",
        "BP51" to "AK",
        "FM18" to "DC", // District of Columbia — NOT a WAS state
        "IO91" to null, // resolves to no US state (a DX grid)
    )

    private fun resolver(grid: String): String? = fakeGridStates[grid.take(4).uppercase(Locale.ROOT)]

    @Test
    fun thereAreExactly50WasStatesAndTheyAreDistinct() {
        assertThat(WAS_STATES).hasSize(50)
        assertThat(WAS_STATES.toSet()).hasSize(50)
        // DC is deliberately not a WAS state.
        assertThat(WAS_STATES).doesNotContain("DC")
        // Spot-check a few corners.
        assertThat(WAS_STATES).containsAtLeast("AK", "HI", "CA", "ME", "FL")
    }

    @Test
    fun workedStatesCollectsDistinctResolvedStates() {
        val grids = listOf("FN31pr", "FN42", "DM79", "CM87", "BL11", "BP51")
        val worked = workedStates(grids) { resolver(it) }
        assertThat(worked).containsExactly("CT", "MA", "CO", "CA", "HI", "AK")
    }

    @Test
    fun workedStatesDedupesSameStateFromDifferentGrids() {
        // Two different CT/MA grids collapse to one entry each.
        val grids = listOf("FN31", "FN31aa", "FN42", "FN42zz")
        assertThat(workedStates(grids) { resolver(it) }).containsExactly("CT", "MA")
    }

    @Test
    fun workedStatesExcludesDcAndUnresolvedGrids() {
        val grids = listOf("FM18", "IO91", "FN31")
        // FM18 -> DC (excluded), IO91 -> null, FN31 -> CT (kept).
        assertThat(workedStates(grids) { resolver(it) }).containsExactly("CT")
    }

    @Test
    fun workedStatesIgnoresNullGrids() {
        val grids = listOf(null, "FN31", null)
        assertThat(workedStates(grids) { resolver(it) }).containsExactly("CT")
    }

    @Test
    fun workedStatesIsEmptyWhenNothingResolves() {
        assertThat(workedStates(listOf("IO91", "FM18")) { resolver(it) }).isEmpty()
        assertThat(workedStates(emptyList()) { resolver(it) }).isEmpty()
    }

    @Test
    fun workedStatesUpperCasesLocaleInsensitively() {
        // A resolver returning a lower-case "il" under a Turkish locale must still
        // match the ASCII "IL" WAS state — Locale.ROOT keeps the dot off the I.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val worked = workedStates(listOf("EN50")) { "il" }
            assertThat(worked).containsExactly("IL")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun neededStatesIsAll50WhenNothingWorked() {
        val needed = neededStates(emptySet())
        assertThat(needed).hasSize(50)
        assertThat(needed).containsExactlyElementsIn(WAS_STATES).inOrder()
    }

    @Test
    fun neededStatesRemovesWorkedStatesKeepingCanonicalOrder() {
        val needed = neededStates(setOf("CA", "NY", "TX"))
        assertThat(needed).hasSize(47)
        assertThat(needed).containsNoneOf("CA", "NY", "TX")
        // Order still follows WAS_STATES (ME precedes MD precedes MA ...).
        assertThat(needed).containsAtLeast("ME", "MD", "MA").inOrder()
    }

    @Test
    fun neededStatesIsEmptyWhenAll50Worked() {
        assertThat(neededStates(WAS_STATES.toSet())).isEmpty()
    }

    @Test
    fun neededStatesPreviewReturnsWholeListWhenUnderCap() {
        val (shown, overflow) = neededStatesPreview(listOf("MT", "ND", "WY"), 16)
        assertThat(shown).containsExactly("MT", "ND", "WY").inOrder()
        assertThat(overflow).isEqualTo(0)
    }

    @Test
    fun neededStatesPreviewCapsAndReportsOverflow() {
        val remaining = WAS_STATES // 50 states
        val (shown, overflow) = neededStatesPreview(remaining, 16)
        assertThat(shown).hasSize(16)
        assertThat(shown).containsExactlyElementsIn(remaining.take(16)).inOrder()
        assertThat(overflow).isEqualTo(34)
    }

    @Test
    fun neededStatesPreviewWithZeroCapShowsNothing() {
        val (shown, overflow) = neededStatesPreview(listOf("MT", "ND"), 0)
        assertThat(shown).isEmpty()
        assertThat(overflow).isEqualTo(2)
    }

    @Test
    fun neededStatesPreviewOfEmptyListIsEmpty() {
        val (shown, overflow) = neededStatesPreview(emptyList(), 16)
        assertThat(shown).isEmpty()
        assertThat(overflow).isEqualTo(0)
    }

    @Test
    fun neededStatesIgnoresNonWasEntriesLikeDc() {
        // A stray "DC" in the worked set must not remove a real state nor error.
        val needed = neededStates(setOf("DC", "ca"))
        assertThat(needed).hasSize(49)
        assertThat(needed).doesNotContain("CA")
        assertThat(needed).contains("NY")
    }
}
