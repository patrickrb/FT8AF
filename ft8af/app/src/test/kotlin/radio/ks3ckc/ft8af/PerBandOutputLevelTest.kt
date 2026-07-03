package radio.ks3ckc.ft8af

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the per-band TX output level decision logic (issue #355):
 * parse/serialize of the "20m=60,40m=85" config format, the restore
 * decision on band change, and the record decision on level adjustment.
 * Pure logic — no Android types, no Robolectric.
 */
class PerBandOutputLevelTest {

    // ---- parsePerBandOutputLevels ----

    @Test
    fun parse_nullOrBlank_returnsEmptyMap() {
        assertThat(parsePerBandOutputLevels(null)).isEmpty()
        assertThat(parsePerBandOutputLevels("")).isEmpty()
        assertThat(parsePerBandOutputLevels("   ")).isEmpty()
    }

    @Test
    fun parse_readsBandLevelPairs() {
        val levels = parsePerBandOutputLevels("20m=60,40m=85")
        assertThat(levels).containsExactly("20m", 60, "40m", 85).inOrder()
    }

    @Test
    fun parse_skipsMalformedEntries() {
        // Missing '=', empty band, non-numeric level — all skipped, valid kept.
        val levels = parsePerBandOutputLevels("20m,=50,40m=abc,80m=70,17m=")
        assertThat(levels).containsExactly("80m", 70)
    }

    @Test
    fun parse_clampsLevelsTo0To100() {
        val levels = parsePerBandOutputLevels("20m=150,40m=-5")
        assertThat(levels).containsExactly("20m", 100, "40m", 0)
    }

    @Test
    fun parse_trimsWhitespaceAroundBandAndLevel() {
        val levels = parsePerBandOutputLevels(" 20m = 60 , 40m = 85 ")
        assertThat(levels).containsExactly("20m", 60, "40m", 85)
    }

    @Test
    fun parse_lastDuplicateWins() {
        val levels = parsePerBandOutputLevels("20m=60,20m=75")
        assertThat(levels).containsExactly("20m", 75)
    }

    // ---- serializePerBandOutputLevels ----

    @Test
    fun serialize_roundTripsThroughParse() {
        val original = linkedMapOf("20m" to 60, "40m" to 85, "70cm" to 30)
        val serialized = serializePerBandOutputLevels(original)
        assertThat(serialized).isEqualTo("20m=60,40m=85,70cm=30")
        assertThat(parsePerBandOutputLevels(serialized)).isEqualTo(original)
    }

    @Test
    fun serialize_emptyMap_isEmptyString() {
        assertThat(serializePerBandOutputLevels(emptyMap())).isEmpty()
    }

    // ---- resolvePerBandOutputLevel (restore on band change) ----

    @Test
    fun resolve_disabled_returnsNull() {
        assertThat(
            resolvePerBandOutputLevel(false, "20m", "20m=60", currentLevel = 80),
        ).isNull()
    }

    @Test
    fun resolve_unknownBand_returnsNull() {
        assertThat(resolvePerBandOutputLevel(true, null, "20m=60", 80)).isNull()
        assertThat(resolvePerBandOutputLevel(true, "", "20m=60", 80)).isNull()
    }

    @Test
    fun resolve_bandWithoutSavedValue_fallsBackToGlobal() {
        // No entry for 40m — return null so the caller keeps the current level.
        assertThat(resolvePerBandOutputLevel(true, "40m", "20m=60", 80)).isNull()
        assertThat(resolvePerBandOutputLevel(true, "40m", "", 80)).isNull()
    }

    @Test
    fun resolve_savedValueDiffers_returnsSavedLevel() {
        assertThat(
            resolvePerBandOutputLevel(true, "20m", "20m=60,40m=85", currentLevel = 80),
        ).isEqualTo(60)
    }

    @Test
    fun resolve_savedValueEqualsCurrent_returnsNull() {
        // Already at the saved level — nothing to restore, no toast.
        assertThat(
            resolvePerBandOutputLevel(true, "20m", "20m=80", currentLevel = 80),
        ).isNull()
    }

    // ---- recordPerBandOutputLevel (save on level adjustment) ----

    @Test
    fun record_disabled_returnsNull() {
        assertThat(recordPerBandOutputLevel(false, "20m", 60, "")).isNull()
    }

    @Test
    fun record_unknownBand_returnsNull() {
        assertThat(recordPerBandOutputLevel(true, null, 60, "")).isNull()
        assertThat(recordPerBandOutputLevel(true, "", 60, "")).isNull()
    }

    @Test
    fun record_newBand_appendsEntry() {
        assertThat(recordPerBandOutputLevel(true, "40m", 85, "20m=60"))
            .isEqualTo("20m=60,40m=85")
    }

    @Test
    fun record_firstEntry_fromEmptyConfig() {
        assertThat(recordPerBandOutputLevel(true, "20m", 60, null)).isEqualTo("20m=60")
        assertThat(recordPerBandOutputLevel(true, "20m", 60, "")).isEqualTo("20m=60")
    }

    @Test
    fun record_existingBand_updatesInPlace() {
        assertThat(recordPerBandOutputLevel(true, "20m", 45, "20m=60,40m=85"))
            .isEqualTo("20m=45,40m=85")
    }

    @Test
    fun record_unchangedLevel_returnsNullSoNothingIsPersisted() {
        assertThat(recordPerBandOutputLevel(true, "20m", 60, "20m=60,40m=85")).isNull()
    }

    @Test
    fun record_clampsLevelBeforeComparingAndStoring() {
        assertThat(recordPerBandOutputLevel(true, "20m", 150, "")).isEqualTo("20m=100")
        // Clamped value already stored — no persist needed.
        assertThat(recordPerBandOutputLevel(true, "20m", 150, "20m=100")).isNull()
    }

    @Test
    fun record_thenResolve_restoresWhatWasRecorded() {
        // Full save/switch-away/switch-back cycle at the logic level.
        var serialized = recordPerBandOutputLevel(true, "20m", 60, "")!!
        serialized = recordPerBandOutputLevel(true, "40m", 85, serialized)!!
        // Back on 20m at some other level: the saved 60 comes back.
        assertThat(resolvePerBandOutputLevel(true, "20m", serialized, 85)).isEqualTo(60)
        // On 17m (never saved): fall back to current/global level.
        assertThat(resolvePerBandOutputLevel(true, "17m", serialized, 85)).isNull()
    }
}
