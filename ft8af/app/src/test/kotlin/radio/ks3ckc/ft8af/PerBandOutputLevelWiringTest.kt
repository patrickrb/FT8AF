package radio.ks3ckc.ft8af

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the stateful per-band output level wiring (issue #355, PR #386
 * review): [saveOutputLevelForBand]'s synchronized read-modify-write of
 * GeneralVariables.perBandOutputLevels, and the cross-thread visibility
 * (volatile) contract of the backing statics. Robolectric because
 * GeneralVariables carries Android LiveData statics.
 */
@RunWith(RobolectricTestRunner::class)
class PerBandOutputLevelWiringTest {

    private var savedEnabled = false
    private var savedLevels = ""

    @Before
    fun saveGlobals() {
        savedEnabled = GeneralVariables.savePerBandOutputLevel
        savedLevels = GeneralVariables.perBandOutputLevels
    }

    @After
    fun restoreGlobals() {
        GeneralVariables.savePerBandOutputLevel = savedEnabled
        GeneralVariables.perBandOutputLevels = savedLevels
    }

    // ---- volatile contract (review comment: stale reads across threads) ----

    @Test
    fun perBandStatics_areVolatile_forCrossThreadVisibility() {
        // Written from DatabaseOpr's background config-load thread, read from
        // UI + MeterProtectionController threads. Both must stay volatile.
        val toggle = GeneralVariables::class.java.getField("savePerBandOutputLevel")
        val map = GeneralVariables::class.java.getField("perBandOutputLevels")
        assertThat(Modifier.isVolatile(toggle.modifiers)).isTrue()
        assertThat(Modifier.isVolatile(map.modifiers)).isTrue()
    }

    // ---- saveOutputLevelForBand (synchronized read-modify-write) ----

    @Test
    fun save_disabled_changesNothingAndDoesNotPersist() {
        GeneralVariables.savePerBandOutputLevel = false
        GeneralVariables.perBandOutputLevels = "20m=60"
        var persisted: String? = null
        saveOutputLevelForBand("40m", 85) { persisted = it }
        assertThat(GeneralVariables.perBandOutputLevels).isEqualTo("20m=60")
        assertThat(persisted).isNull()
    }

    @Test
    fun save_enabled_updatesMapAndPersistsSameValue() {
        GeneralVariables.savePerBandOutputLevel = true
        GeneralVariables.perBandOutputLevels = "20m=60"
        var persisted: String? = null
        saveOutputLevelForBand("40m", 85) { persisted = it }
        assertThat(GeneralVariables.perBandOutputLevels).isEqualTo("20m=60,40m=85")
        assertThat(persisted).isEqualTo("20m=60,40m=85")
    }

    @Test
    fun save_unchangedLevel_doesNotPersist() {
        GeneralVariables.savePerBandOutputLevel = true
        GeneralVariables.perBandOutputLevels = "20m=60"
        var persistCalls = 0
        saveOutputLevelForBand("20m", 60) { persistCalls++ }
        assertThat(persistCalls).isEqualTo(0)
        assertThat(GeneralVariables.perBandOutputLevels).isEqualTo("20m=60")
    }

    @Test
    fun save_concurrentWritersOnDifferentBands_loseNoUpdates() {
        // UI adjustments and MeterProtectionController auto-reductions can run
        // concurrently. Unsynchronized, the read-modify-write of the serialized
        // map lets one writer clobber another band's entry (last writer wins on
        // stale input). With the lock, every band's final write must survive.
        GeneralVariables.savePerBandOutputLevel = true
        GeneralVariables.perBandOutputLevels = ""
        val bands = (1..8).map { "b$it" }
        val finalLevelFor = { i: Int -> 90 + i } // distinct per band, within 0..100
        val start = CountDownLatch(1)
        val threads = bands.mapIndexed { i, band ->
            Thread {
                start.await()
                // Churn the shared map, then land on this band's final value.
                repeat(200) { n -> saveOutputLevelForBand(band, n % 90) {} }
                saveOutputLevelForBand(band, finalLevelFor(i)) {}
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(30_000) }

        val result = parsePerBandOutputLevels(GeneralVariables.perBandOutputLevels)
        assertThat(result.keys).containsExactlyElementsIn(bands)
        bands.forEachIndexed { i, band ->
            assertThat(result[band]).isEqualTo(finalLevelFor(i))
        }
    }
}
