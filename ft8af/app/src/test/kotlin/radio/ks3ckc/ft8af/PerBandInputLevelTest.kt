package radio.ks3ckc.ft8af

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import java.lang.reflect.Modifier
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for per-band RX input volume (issue #927): the 0..200 range on the
 * shared per-band helpers, the gain-to-percent conversion, and the
 * GeneralVariables wiring (own toggle + map, independent of the TX output
 * map). Robolectric because GeneralVariables carries Android LiveData statics.
 */
@RunWith(RobolectricTestRunner::class)
class PerBandInputLevelTest {

    private var savedOutputEnabled = false
    private var savedOutputLevels = ""
    private var savedInputEnabled = false
    private var savedInputLevels = ""
    private var savedGain = 1.0f

    @Before
    fun saveGlobals() {
        savedOutputEnabled = GeneralVariables.savePerBandOutputLevel
        savedOutputLevels = GeneralVariables.perBandOutputLevels
        savedInputEnabled = GeneralVariables.savePerBandInputLevel
        savedInputLevels = GeneralVariables.perBandInputLevels
        savedGain = GeneralVariables.inputGainPercent
    }

    @After
    fun restoreGlobals() {
        GeneralVariables.savePerBandOutputLevel = savedOutputEnabled
        GeneralVariables.perBandOutputLevels = savedOutputLevels
        GeneralVariables.savePerBandInputLevel = savedInputEnabled
        GeneralVariables.perBandInputLevels = savedInputLevels
        GeneralVariables.inputGainPercent = savedGain
    }

    // ---- maxLevel on the shared helpers ----

    @Test
    fun parse_withInputMax_keepsLevelsAbove100AndClampsTo200() {
        val levels = parsePerBandOutputLevels("20m=150,10m=250,40m=-5", PER_BAND_INPUT_LEVEL_MAX)
        assertThat(levels).containsExactly("20m", 150, "10m", 200, "40m", 0).inOrder()
    }

    @Test
    fun parse_defaultMax_stillClampsTo100() {
        assertThat(parsePerBandOutputLevels("20m=150")).containsExactly("20m", 100)
    }

    @Test
    fun record_withInputMax_storesAbove100() {
        assertThat(recordPerBandOutputLevel(true, "10m", 160, "20m=100", PER_BAND_INPUT_LEVEL_MAX))
            .isEqualTo("20m=100,10m=160")
        assertThat(recordPerBandOutputLevel(true, "10m", 999, "", PER_BAND_INPUT_LEVEL_MAX))
            .isEqualTo("10m=200")
    }

    @Test
    fun resolve_withInputMax_restoresAbove100() {
        assertThat(resolvePerBandOutputLevel(true, "10m", "10m=160", 100, PER_BAND_INPUT_LEVEL_MAX))
            .isEqualTo(160)
    }

    // ---- inputLevelFromGainPercent ----

    @Test
    fun inputLevel_roundsAndClamps() {
        assertThat(inputLevelFromGainPercent(1.0f)).isEqualTo(100)
        assertThat(inputLevelFromGainPercent(1.6f)).isEqualTo(160)
        assertThat(inputLevelFromGainPercent(0.7f)).isEqualTo(70) // 69.999... rounds, not floors
        assertThat(inputLevelFromGainPercent(3.0f)).isEqualTo(200)
        assertThat(inputLevelFromGainPercent(-1.0f)).isEqualTo(0)
    }

    // ---- GeneralVariables wiring ----

    @Test
    fun inputStatics_areVolatile_forCrossThreadVisibility() {
        val toggle = GeneralVariables::class.java.getField("savePerBandInputLevel")
        val map = GeneralVariables::class.java.getField("perBandInputLevels")
        assertThat(Modifier.isVolatile(toggle.modifiers)).isTrue()
        assertThat(Modifier.isVolatile(map.modifiers)).isTrue()
    }

    @Test
    fun save_disabled_changesNothingAndDoesNotPersist() {
        GeneralVariables.savePerBandInputLevel = false
        GeneralVariables.perBandInputLevels = "20m=100"
        var persisted: String? = null
        saveInputLevelForBand("10m", 160) { persisted = it }
        assertThat(GeneralVariables.perBandInputLevels).isEqualTo("20m=100")
        assertThat(persisted).isNull()
    }

    @Test
    fun save_enabled_updatesInputMapOnly() {
        GeneralVariables.savePerBandInputLevel = true
        GeneralVariables.perBandInputLevels = "20m=100"
        GeneralVariables.savePerBandOutputLevel = true
        GeneralVariables.perBandOutputLevels = "20m=60"
        var persisted: String? = null
        saveInputLevelForBand("10m", 160) { persisted = it }
        assertThat(GeneralVariables.perBandInputLevels).isEqualTo("20m=100,10m=160")
        assertThat(persisted).isEqualTo("20m=100,10m=160")
        assertThat(GeneralVariables.perBandOutputLevels).isEqualTo("20m=60")
    }

    @Test
    fun save_unchangedLevel_doesNotPersist() {
        GeneralVariables.savePerBandInputLevel = true
        GeneralVariables.perBandInputLevels = "20m=100"
        var persistCalls = 0
        saveInputLevelForBand("20m", 100) { persistCalls++ }
        assertThat(persistCalls).isEqualTo(0)
    }

    @Test
    fun save_ignoresOutputToggle() {
        // Only the input toggle gates the input map.
        GeneralVariables.savePerBandInputLevel = false
        GeneralVariables.savePerBandOutputLevel = true
        GeneralVariables.perBandInputLevels = ""
        saveInputLevelForBand("20m", 120) {}
        assertThat(GeneralVariables.perBandInputLevels).isEmpty()
    }

    @Test
    fun restore_returnsSavedLevelForBandWhenDifferent() {
        GeneralVariables.savePerBandInputLevel = true
        GeneralVariables.perBandInputLevels = "20m=100,10m=160"
        GeneralVariables.inputGainPercent = 1.0f
        assertThat(restoredInputLevelForBand("10m")).isEqualTo(160)
    }

    @Test
    fun restore_returnsNullWhenAlreadyAtLevelUnknownBandOrDisabled() {
        GeneralVariables.savePerBandInputLevel = true
        GeneralVariables.perBandInputLevels = "20m=100,10m=160"
        GeneralVariables.inputGainPercent = 1.6f
        assertThat(restoredInputLevelForBand("10m")).isNull()
        assertThat(restoredInputLevelForBand("40m")).isNull()
        assertThat(restoredInputLevelForBand(null)).isNull()
        GeneralVariables.savePerBandInputLevel = false
        assertThat(restoredInputLevelForBand("20m")).isNull()
    }

    // ---- applyRestoredInputLevelForBand (band-change restore) ----

    @Test
    fun applyRestore_setsGainAsFractionAndPersistsPercent() {
        GeneralVariables.savePerBandInputLevel = true
        GeneralVariables.perBandInputLevels = "20m=100,10m=160"
        GeneralVariables.inputGainPercent = 1.0f
        var persisted: Int? = null
        val applied = applyRestoredInputLevelForBand("10m") { persisted = it }
        assertThat(applied).isEqualTo(160)
        assertThat(persisted).isEqualTo(160)
        assertThat(GeneralVariables.inputGainPercent).isWithin(1e-6f).of(1.6f)
    }

    @Test
    fun applyRestore_nothingToRestore_leavesGainAndDoesNotPersist() {
        GeneralVariables.savePerBandInputLevel = true
        GeneralVariables.perBandInputLevels = "20m=100"
        GeneralVariables.inputGainPercent = 1.3f
        var persistCalls = 0
        assertThat(applyRestoredInputLevelForBand("40m") { persistCalls++ }).isNull()
        GeneralVariables.savePerBandInputLevel = false
        assertThat(applyRestoredInputLevelForBand("20m") { persistCalls++ }).isNull()
        assertThat(persistCalls).isEqualTo(0)
        assertThat(GeneralVariables.inputGainPercent).isEqualTo(1.3f)
    }

    // ---- inputLevelChangedInDialog ----

    @Test
    fun dialogDismiss_savesOnlyWhenTheLevelMoved() {
        assertThat(inputLevelChangedInDialog(100, 100)).isFalse()
        assertThat(inputLevelChangedInDialog(100, 160)).isTrue()
        assertThat(inputLevelChangedInDialog(160, 0)).isTrue()
    }
}
