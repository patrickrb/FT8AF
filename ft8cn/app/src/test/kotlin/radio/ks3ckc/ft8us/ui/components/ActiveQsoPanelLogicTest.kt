package radio.ks3ckc.ft8us.ui.components

import com.bg7yoz.ft8cn.Ft8Message
import com.bg7yoz.ft8cn.GeneralVariables
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [buildQsoLog], the (pure) QSO-panel classification/ordering
 * extracted from ActiveQsoPanel. Covers the fix that own-TX loopback never
 * produces a panel row from the decoded list — TX rows come solely from the
 * synthesized key-up entries.
 *
 * Ft8Message's three-arg constructor is (to, from, extra), upper-cased.
 */
@RunWith(RobolectricTestRunner::class)
class ActiveQsoPanelLogicTest {

    private val myCall = "UB8CSJ"
    private val target = "RA3XYZ"

    @Before
    fun setUp() {
        GeneralVariables.myCallsign = myCall
    }

    @After
    fun tearDown() {
        GeneralVariables.myCallsign = ""
    }

    private fun msg(to: String, from: String, utc: Long, snr: Int = -10): Ft8Message =
        Ft8Message(to, from, "-10").apply {
            utcTime = utc
            this.snr = snr
        }

    @Test
    fun `empty target returns empty list`() {
        val list = listOf(msg(myCall, target, 1_000L))
        val result = buildQsoLog(list, displayCallsign = "", myCallsign = myCall, synthTx = emptyList())
        assertThat(result).isEmpty()
    }

    @Test
    fun `message from target to me is RX with snr`() {
        val list = listOf(msg(myCall, target, 1_000L, snr = -7))
        val result = buildQsoLog(list, target, myCall, emptyList())

        assertThat(result).hasSize(1)
        assertThat(result[0].direction).isEqualTo(QsoLogEntry.Direction.RX)
        assertThat(result[0].snr).isEqualTo(-7)
        assertThat(result[0].utcTime).isEqualTo(1_000L)
    }

    @Test
    fun `message from target to someone else is BUSY`() {
        val list = listOf(msg("DL1ABC", target, 1_000L))
        val result = buildQsoLog(list, target, myCall, emptyList())

        assertThat(result).hasSize(1)
        assertThat(result[0].direction).isEqualTo(QsoLogEntry.Direction.BUSY)
    }

    @Test
    fun `unrelated traffic is ignored`() {
        val list = listOf(msg("DL1ABC", "JA1XYZ", 1_000L))
        val result = buildQsoLog(list, target, myCall, emptyList())
        assertThat(result).isEmpty()
    }

    @Test
    fun `own loopback in the decoded list never becomes a TX row`() {
        // A message we sent (from = my call, to = target). Even if such an echo
        // reached the message list it must not render — there is no TX branch.
        val list = listOf(msg(target, myCall, 1_000L))
        val result = buildQsoLog(list, target, myCall, emptyList())
        assertThat(result).isEmpty()
    }

    @Test
    fun `synthesized TX entries are the sole source of TX rows`() {
        val synth = listOf(
            QsoLogEntry(QsoLogEntry.Direction.TX, utcTime = 2_000L, messageText = "RA3XYZ UB8CSJ -10")
        )
        val list = listOf(msg(myCall, target, 1_000L)) // one RX
        val result = buildQsoLog(list, target, myCall, synth)

        assertThat(result).hasSize(2)
        assertThat(result.count { it.direction == QsoLogEntry.Direction.TX }).isEqualTo(1)
        assertThat(result.count { it.direction == QsoLogEntry.Direction.RX }).isEqualTo(1)
    }

    @Test
    fun `entries are sorted ascending by utc time`() {
        val synth = listOf(
            QsoLogEntry(QsoLogEntry.Direction.TX, utcTime = 3_000L, messageText = "TX later")
        )
        val list = listOf(
            msg(myCall, target, 1_000L),   // RX earliest
            msg("DL1ABC", target, 2_000L), // BUSY middle
        )
        val result = buildQsoLog(list, target, myCall, synth)

        assertThat(result.map { it.utcTime }).isInOrder()
        assertThat(result.first().direction).isEqualTo(QsoLogEntry.Direction.RX)
        assertThat(result.last().direction).isEqualTo(QsoLogEntry.Direction.TX)
    }

    @Test
    fun `target match is case insensitive`() {
        val list = listOf(msg(myCall, "ra3xyz", 1_000L))
        val result = buildQsoLog(list, target, myCall, emptyList())
        assertThat(result).hasSize(1)
        assertThat(result[0].direction).isEqualTo(QsoLogEntry.Direction.RX)
    }
}
