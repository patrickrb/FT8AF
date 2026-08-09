package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.ft8transmit.QueuedCaller
import org.junit.Test

/**
 * Unit tests for the pileup caller-chip display helpers [orderCallerQueue] and
 * [callerSnrLabel]. The display order must mirror the auto-dequeue policy
 * (CallerQueueOrdering) so the leftmost chip is the station the engine works next.
 */
class CallerQueueDisplayTest {

    private fun caller(call: String, snr: Int) =
        QueuedCaller(call, 1000f, 0, snr, 0, 0, "")

    @Test
    fun fifo_preservesQueueOrder() {
        val q = listOf(caller("A", -5), caller("B", 12), caller("C", 3))
        val ordered = orderCallerQueue(q, strongestFirst = false)
        assertThat(ordered.map { it.callsign }).containsExactly("A", "B", "C").inOrder()
    }

    @Test
    fun strongestFirst_sortsBySnrDescending() {
        val q = listOf(caller("A", -5), caller("B", 12), caller("C", 3))
        val ordered = orderCallerQueue(q, strongestFirst = true)
        assertThat(ordered.map { it.callsign }).containsExactly("B", "C", "A").inOrder()
    }

    @Test
    fun strongestFirst_stableForTies() {
        // Equal SNR keeps first-heard order (sortedByDescending is stable).
        val q = listOf(caller("A", 7), caller("B", 7), caller("C", 7))
        val ordered = orderCallerQueue(q, strongestFirst = true)
        assertThat(ordered.map { it.callsign }).containsExactly("A", "B", "C").inOrder()
    }

    @Test
    fun orderCallerQueue_doesNotMutateInput() {
        val q = listOf(caller("A", -5), caller("B", 12))
        orderCallerQueue(q, strongestFirst = true)
        assertThat(q.map { it.callsign }).containsExactly("A", "B").inOrder()
    }

    @Test
    fun emptyQueue_returnsEmpty() {
        assertThat(orderCallerQueue(emptyList(), strongestFirst = true)).isEmpty()
        assertThat(orderCallerQueue(emptyList(), strongestFirst = false)).isEmpty()
    }

    @Test
    fun snrLabel_positiveGetsPlusSign() {
        assertThat(callerSnrLabel(5)).isEqualTo("+5")
        assertThat(callerSnrLabel(0)).isEqualTo("+0")
    }

    @Test
    fun snrLabel_negativeKeepsMinus() {
        assertThat(callerSnrLabel(-12)).isEqualTo("-12")
    }
}
