package radio.ks3ckc.ft8af.pota

import com.k1af.ft8af.log.QSLRecord
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit coverage for the Android-free surface of [PotaSessionManager].
 *
 * The singleton starts with no active activation (the only way to start one is
 * through [PotaActivationDao], which hits the SQLite database and so cannot run
 * under plain JUnit). With no activation in progress:
 *   - the read-only getters report the "idle" state, and
 *   - [PotaSessionManager.stampQso] leaves the MY_SIG fields untouched but still
 *     stamps the worked-station SIG fields when a spotted park ref is supplied
 *     (the Park-to-Park / hunt path).
 *
 * [QSLRecord] is used here purely as a fixture: its full-args constructor only
 * depends on Android-free helpers (UtcTimer/BaseRigOperation/MaidenheadGrid) and
 * the unmocked android.util.Log returns defaults.
 */
class PotaSessionManagerTest {

    private fun record(): QSLRecord =
        QSLRecord(
            0L, 15_000L, "K1ABC", "", "W1AW", "",
            -5, -10, "FT8", 14_074_000L, 1_500,
        )

    @Test
    fun isActive_defaultsToFalseWhenIdle() {
        assertThat(PotaSessionManager.isActive).isFalse()
    }

    @Test
    fun currentParkRefs_defaultsToEmptyWhenIdle() {
        assertThat(PotaSessionManager.currentParkRefs).isEmpty()
    }

    @Test
    fun currentActivation_isNullWhenIdle() {
        assertThat(PotaSessionManager.currentActivation.value).isNull()
    }

    @Test
    fun activationQsos_areEmptyWhenIdle() {
        assertThat(PotaSessionManager.activationQsos.value).isEmpty()
    }

    @Test
    fun stampQso_withSpottedRef_setsHuntSigFieldsOnly() {
        val r = record()

        PotaSessionManager.stampQso(r, "K-5678")

        // Worked-station (hunt / P2P) fields are stamped from the spotted ref.
        assertThat(r.sig).isEqualTo("POTA")
        assertThat(r.sigInfo).isEqualTo("K-5678")
        // No activation running -> our own activation fields stay untouched.
        assertThat(r.mySig).isNull()
        assertThat(r.mySigInfo).isNull()
    }

    @Test
    fun stampQso_withNullSpottedRef_leavesAllSigFieldsNull() {
        val r = record()

        PotaSessionManager.stampQso(r, null)

        assertThat(r.sig).isNull()
        assertThat(r.sigInfo).isNull()
        assertThat(r.mySig).isNull()
        assertThat(r.mySigInfo).isNull()
    }

    @Test
    fun stampQso_emptySpottedRef_stillStampsSig() {
        // The production code only null-checks the spotted ref (`?.let`), so an
        // empty string is treated as a present value and is stamped verbatim.
        val r = record()

        PotaSessionManager.stampQso(r, "")

        assertThat(r.sig).isEqualTo("POTA")
        assertThat(r.sigInfo).isEqualTo("")
    }
}
