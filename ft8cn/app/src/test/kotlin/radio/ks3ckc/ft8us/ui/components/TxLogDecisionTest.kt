package radio.ks3ckc.ft8us.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [decideTxLog] — the rule that appends one mini-log row per transmission.
 * Pure (booleans + a string), so no Android runtime is needed.
 *
 * The bug being fixed: repeated transmissions of the same message (e.g. several unanswered
 * RR73s) used to collapse into a single row because rows were de-duplicated by text. Now
 * each transmission (rising edge of isTransmitting) logs its own row.
 */
class TxLogDecisionTest {

    /** Drive a sequence of (isTransmitting, message) frames and return how many rows were logged. */
    private fun runFrames(frames: List<Pair<Boolean, String>>, hasTarget: Boolean = true): Int {
        var was = false
        var pending = false
        var logged = 0
        for ((tx, msg) in frames) {
            val d = decideTxLog(was, tx, pending, msg, hasTarget)
            was = d.wasTransmittingAfter
            pending = d.pendingAfter
            if (d.shouldLog) logged++
        }
        return logged
    }

    @Test
    fun idle_logsNothing() {
        assertThat(runFrames(listOf(false to "", false to ""))).isEqualTo(0)
    }

    @Test
    fun singleTransmission_logsOnce() {
        // TX rises with the message ready, then ends.
        val n = runFrames(listOf(false to "", true to "RA3XYZ UB8CSJ RR73", false to ""))
        assertThat(n).isEqualTo(1)
    }

    @Test
    fun sameTransmissionAcrossRecompositions_logsOnce() {
        // isTransmitting stays true across several frames (recompositions) within one cycle:
        // only the rising edge logs.
        val msg = "RA3XYZ UB8CSJ RR73"
        val n = runFrames(listOf(false to "", true to msg, true to msg, true to msg, false to ""))
        assertThat(n).isEqualTo(1)
    }

    @Test
    fun repeatedIdenticalMessages_logEachTransmission() {
        // THE BUG: three unanswered RR73s in three separate cycles -> three rows.
        val msg = "RA3XYZ UB8CSJ RR73"
        val n = runFrames(
            listOf(
                false to "", true to msg, false to "",   // 1st RR73
                true to msg, false to "",                // 2nd RR73
                true to msg, false to "",                // 3rd RR73
            )
        )
        assertThat(n).isEqualTo(3)
    }

    @Test
    fun risingEdgeBeforeMessageReady_logsWhenMessageArrives() {
        // isTransmitting flips true one frame before the message string is populated:
        // the pending flag bridges it, logging exactly once when the text arrives.
        val n = runFrames(listOf(false to "", true to "", true to "RA3XYZ UB8CSJ R-12", true to "RA3XYZ UB8CSJ R-12"))
        assertThat(n).isEqualTo(1)
    }

    @Test
    fun pendingDoesNotLeakAcrossTransmitEnd() {
        // TX rises but no message ever arrives, then TX ends: nothing logged, and the next
        // real transmission still logs exactly once (pending didn't leak).
        val n = runFrames(
            listOf(
                false to "", true to "", false to "",            // armed but no text, then ended
                true to "RA3XYZ UB8CSJ 73", false to "",         // a real transmission
            )
        )
        assertThat(n).isEqualTo(1)
    }

    @Test
    fun noTarget_logsNothing() {
        // Calling CQ (no target) has no mini-log.
        val n = runFrames(listOf(false to "", true to "CQ UB8CSJ MO07"), hasTarget = false)
        assertThat(n).isEqualTo(0)
    }
}
