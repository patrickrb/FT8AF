package radio.ks3ckc.ft8af.ui.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.pota.model.PotaActivation

/**
 * Coverage for historyForDisplay — the decision logic behind the recent
 * activations list inlined on the Activate tab. The composable that renders it
 * is a thin wrapper; the filter (drop in-progress) and ordering (newest first)
 * live here. PotaActivation is a plain data class, so no Robolectric is needed.
 */
class HistoryForDisplayTest {

    private fun activation(id: Long, startedAtMs: Long, endedAtMs: Long?): PotaActivation =
        PotaActivation(
            id = id,
            parkRef = "K-$id",
            operator = null,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            qsoCount = 0,
            notes = null,
        )

    @Test
    fun excludesActiveEntries() {
        val finished = activation(1, startedAtMs = 1000, endedAtMs = 2000)
        val active = activation(2, startedAtMs = 3000, endedAtMs = null)

        val result = historyForDisplay(listOf(finished, active))

        assertThat(result.map { it.id }).containsExactly(1L)
    }

    @Test
    fun sortsNewestFirstRegardlessOfInputOrder() {
        val older = activation(1, startedAtMs = 1000, endedAtMs = 1500)
        val newer = activation(2, startedAtMs = 5000, endedAtMs = 5500)
        val middle = activation(3, startedAtMs = 3000, endedAtMs = 3500)

        val result = historyForDisplay(listOf(older, newer, middle))

        assertThat(result.map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test
    fun emptyInputProducesEmptyOutput() {
        assertThat(historyForDisplay(emptyList())).isEmpty()
    }

    @Test
    fun keepsAllFinishedEntries() {
        val a = activation(1, startedAtMs = 1000, endedAtMs = 2000)
        val b = activation(2, startedAtMs = 4000, endedAtMs = 5000)

        val result = historyForDisplay(listOf(a, b))

        assertThat(result).hasSize(2)
        assertThat(result.map { it.id }).containsExactly(2L, 1L).inOrder()
    }
}
