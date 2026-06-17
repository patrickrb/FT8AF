package radio.ks3ckc.ft8af.ui.decode

import com.k1af.ft8af.Ft8Message
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit-tests the pure [filterMessages] decode-list filter. Robolectric is
 * needed only because [Ft8Message] reaches android.util.Log on construction.
 *
 * These exercise the two chip filters that depend purely on message content
 * (no operator-specific GeneralVariables state, which stays at its defaults
 * here): "All" passes everything through, "CQ Calls" keeps only CQ messages.
 */
@RunWith(RobolectricTestRunner::class)
class DecodeFilterTest {

    private fun cq(from: String) = Ft8Message("CQ", from, "FN42")
    private fun directed(to: String, from: String) = Ft8Message(to, from, "FN42")

    @Test
    fun all_returnsEveryMessage() {
        val messages = listOf(cq("K1ABC"), directed("W1AW", "K2DEF"))

        val result = filterMessages(messages, "All")

        assertThat(result).hasSize(2)
        assertThat(result.map { it.callsignFrom }).containsExactly("K1ABC", "K2DEF")
    }

    @Test
    fun cqCalls_keepsOnlyCqMessages() {
        val cqMsg = cq("K1ABC")
        val directedMsg = directed("W1AW", "K2DEF")

        val result = filterMessages(listOf(cqMsg, directedMsg), "CQ Calls")

        assertThat(result).containsExactly(cqMsg)
    }
}
