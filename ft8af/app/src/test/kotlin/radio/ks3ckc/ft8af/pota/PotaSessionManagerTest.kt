package radio.ks3ckc.ft8af.pota

import com.k1af.ft8af.log.QSLRecord
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import radio.ks3ckc.ft8af.pota.model.PotaActivation

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

    // --- endedActivations ---------------------------------------------------

    private val endedFixture = PotaActivation(
        id = 7L,
        parkRef = "K-1234",
        operator = "W1AW",
        startedAtMs = 0L,
        endedAtMs = 1_000L,
        qsoCount = 12,
        notes = null,
    )

    @Test
    fun notifyActivationEnded_publishesTheEndedActivationToListeners() = runBlocking {
        // UNDISPATCHED subscribes before the emit below: the flow has no replay,
        // so only a listener already collecting receives the event.
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            PotaSessionManager.endedActivations.first()
        }

        PotaSessionManager.notifyActivationEnded(endedFixture)

        assertThat(withTimeout(1_000) { received.await() }).isEqualTo(endedFixture)
    }

    @Test
    fun notifyActivationEnded_withNoListener_neitherThrowsNorBlocks() {
        PotaSessionManager.notifyActivationEnded(endedFixture)
        PotaSessionManager.notifyActivationEnded(endedFixture.copy(id = 8L))
    }

    // --- onQsoLogged / qsoCountsForActivation ------------------------------

    @Test
    fun onQsoLogged_whenIdle_isANoOp() {
        // No activation running (the only state reachable without SQLite):
        // must neither crash nor conjure an activation.
        PotaSessionManager.onQsoLogged("K-1234")

        assertThat(PotaSessionManager.currentActivation.value).isNull()
    }

    @Test
    fun qsoCountsForActivation_matchesDatabaseBumpPredicate() {
        // Mirrors DatabaseOpr's `park_ref = ? AND ended_at IS NULL` binding:
        // only an exact match on the full (possibly comma-joined) ref counts.
        assertThat(PotaSessionManager.qsoCountsForActivation("K-1234", "K-1234")).isTrue()
        assertThat(PotaSessionManager.qsoCountsForActivation("K-1234,K-5678", "K-1234,K-5678")).isTrue()

        assertThat(PotaSessionManager.qsoCountsForActivation("K-1234", null)).isFalse()
        assertThat(PotaSessionManager.qsoCountsForActivation("K-1234", "")).isFalse()
        assertThat(PotaSessionManager.qsoCountsForActivation("K-1234", "K-5678")).isFalse()
        assertThat(PotaSessionManager.qsoCountsForActivation("K-1234,K-5678", "K-1234")).isFalse()
    }
}
