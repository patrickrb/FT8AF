package radio.ks3ckc.ft8af

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for [resolveTuneLevel] — the tune-drive decision matrix from issue
 * #408. Pure logic, no runner. The four rows of the behavior matrix:
 *
 *  | mode          | per-band | tune uses                       |
 *  |---------------|----------|---------------------------------|
 *  | same as TX    | off      | live FT8 level                  |
 *  | same as TX    | on       | live FT8 level (already per-band|
 *  |               |          | restored on band change)        |
 *  | independent   | off      | global tune level               |
 *  | independent   | on       | per-band tune level, falling    |
 *  |               |          | back to the global tune level   |
 */
class TuneLevelTest {

    @Test
    fun sameAsTxDrive_usesTheLiveTxLevel() {
        assertThat(
            resolveTuneLevel(
                independent = false, globalTuneLevel = 25,
                perBandEnabled = false, band = "20m",
                serializedTuneLevels = "20m=40", txLevel = 80,
            ),
        ).isEqualTo(80)
        // Per-band on changes nothing for this mode: the live TX level is
        // already the per-band FT8 level (band changes restore it).
        assertThat(
            resolveTuneLevel(
                independent = false, globalTuneLevel = 25,
                perBandEnabled = true, band = "20m",
                serializedTuneLevels = "20m=40", txLevel = 60,
            ),
        ).isEqualTo(60)
    }

    @Test
    fun independent_perBandOff_usesTheGlobalTuneLevel() {
        assertThat(
            resolveTuneLevel(
                independent = true, globalTuneLevel = 25,
                perBandEnabled = false, band = "20m",
                serializedTuneLevels = "20m=40", txLevel = 80,
            ),
        ).isEqualTo(25)
    }

    @Test
    fun independent_perBandOn_usesTheBandsTuneLevel() {
        assertThat(
            resolveTuneLevel(
                independent = true, globalTuneLevel = 25,
                perBandEnabled = true, band = "20m",
                serializedTuneLevels = "20m=40,40m=55", txLevel = 80,
            ),
        ).isEqualTo(40)
        assertThat(
            resolveTuneLevel(
                independent = true, globalTuneLevel = 25,
                perBandEnabled = true, band = "40m",
                serializedTuneLevels = "20m=40,40m=55", txLevel = 80,
            ),
        ).isEqualTo(55)
    }

    @Test
    fun independent_perBandOn_unknownBandFallsBackToGlobal() {
        assertThat(
            resolveTuneLevel(
                independent = true, globalTuneLevel = 25,
                perBandEnabled = true, band = "17m",
                serializedTuneLevels = "20m=40", txLevel = 80,
            ),
        ).isEqualTo(25)
        // Null/blank band (frequency outside any band table): global too.
        assertThat(
            resolveTuneLevel(
                independent = true, globalTuneLevel = 25,
                perBandEnabled = true, band = null,
                serializedTuneLevels = "20m=40", txLevel = 80,
            ),
        ).isEqualTo(25)
    }

    @Test
    fun levelsAreClampedToTheLegalRange() {
        assertThat(
            resolveTuneLevel(
                independent = true, globalTuneLevel = 400,
                perBandEnabled = false, band = null,
                serializedTuneLevels = null, txLevel = 80,
            ),
        ).isEqualTo(100)
        assertThat(
            resolveTuneLevel(
                independent = false, globalTuneLevel = 25,
                perBandEnabled = false, band = null,
                serializedTuneLevels = null, txLevel = -5,
            ),
        ).isEqualTo(0)
    }

    @Test
    fun tuneMapAndTxMapAreIndependentStores() {
        // The per-band recorder is the shared, already-tested helper; this pins
        // the #408 requirement that recording a tune level builds a map that
        // never contains or disturbs FT8 entries — they are separate strings.
        val tuneMap = recordPerBandOutputLevel(
            enabled = true, band = "20m", level = 30, serialized = "",
        )
        assertThat(tuneMap).isEqualTo("20m=30")
        val txMap = "20m=80,40m=70"
        // Resolving tune on 20m reads the tune map, not the TX map.
        assertThat(
            resolveTuneLevel(
                independent = true, globalTuneLevel = 25,
                perBandEnabled = true, band = "20m",
                serializedTuneLevels = tuneMap, txLevel = 80,
            ),
        ).isEqualTo(30)
        assertThat(txMap).isEqualTo("20m=80,40m=70") // untouched
    }
}
