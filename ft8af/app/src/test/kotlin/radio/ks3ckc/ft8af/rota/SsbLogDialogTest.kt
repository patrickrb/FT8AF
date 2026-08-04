package radio.ks3ckc.ft8af.rota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.ft8af.ui.rota.buildSsbQslRecord
import radio.ks3ckc.ft8af.ui.rota.formatFreqMhz
import radio.ks3ckc.ft8af.ui.rota.isValidGridOrBlank
import radio.ks3ckc.ft8af.ui.rota.parseSsbFrequencyMhzToHz
import radio.ks3ckc.ft8af.ui.rota.parseSsbReport
import radio.ks3ckc.ft8af.ui.rota.ssbEntryValid

/**
 * The decision logic behind the SSB quick-log dialog, tested through the pure
 * helpers rather than the Composable. Robolectric because [buildSsbQslRecord]
 * constructs a QSLRecord, which reaches into the grid/rig layers.
 */
@RunWith(RobolectricTestRunner::class)
class SsbLogDialogTest {
    // 2025-07-31T14:05:09Z — same instant the mapper tests use.
    private val now = 1_753_970_709_000L

    @Test
    fun `frequency parses MHz and rejects junk`() {
        assertThat(parseSsbFrequencyMhzToHz("14.250")).isEqualTo(14_250_000L)
        assertThat(parseSsbFrequencyMhzToHz(" 7.2 ")).isEqualTo(7_200_000L)
        assertThat(parseSsbFrequencyMhzToHz("")).isNull()
        assertThat(parseSsbFrequencyMhzToHz("twenty")).isNull()
        assertThat(parseSsbFrequencyMhzToHz("0")).isNull()
        // Below the server's 100 kHz floor — sending it would 400 the batch.
        assertThat(parseSsbFrequencyMhzToHz("0.05")).isNull()
        assertThat(parseSsbFrequencyMhzToHz("99999")).isNull()
    }

    @Test
    fun `frequency text round-trips through the prefill formatter`() {
        assertThat(formatFreqMhz(14_250_000L)).isEqualTo("14.250")
        assertThat(parseSsbFrequencyMhzToHz(formatFreqMhz(7_178_000L))).isEqualTo(7_178_000L)
        // Never logged one before: the field starts empty, not "0.000".
        assertThat(formatFreqMhz(0L)).isEmpty()
    }

    @Test
    fun `RS reports accept the 11-59 shape and reject anything else`() {
        assertThat(parseSsbReport("59")).isEqualTo(59)
        assertThat(parseSsbReport("11")).isEqualTo(11)
        assertThat(parseSsbReport("35")).isEqualTo(35)
        // Readability tops out at 5 and strength has no zero.
        assertThat(parseSsbReport("69")).isNull()
        assertThat(parseSsbReport("50")).isNull()
        assertThat(parseSsbReport("5")).isNull()
        assertThat(parseSsbReport("599")).isNull()
        assertThat(parseSsbReport("")).isNull()
        assertThat(parseSsbReport("ab")).isNull()
    }

    @Test
    fun `grid accepts blank or a real locator`() {
        assertThat(isValidGridOrBlank("")).isTrue()
        assertThat(isValidGridOrBlank("  ")).isTrue()
        assertThat(isValidGridOrBlank("EM28")).isTrue()
        assertThat(isValidGridOrBlank("fn42ax")).isTrue()
        assertThat(isValidGridOrBlank("12AB")).isFalse()
        assertThat(isValidGridOrBlank("EM2")).isFalse()
        assertThat(isValidGridOrBlank("EM28AXZZ")).isFalse()
    }

    @Test
    fun `entry validity needs a callsign and sane numbers`() {
        assertThat(ssbEntryValid("K1AF", "14.250", "59", "57", "FN42")).isTrue()
        // No dial reading is still a loggable contact.
        assertThat(ssbEntryValid("K1AF", "", "59", "59", "")).isTrue()
        assertThat(ssbEntryValid("", "14.250", "59", "59", "")).isFalse()
        // A typo'd frequency must block, not silently log without one.
        assertThat(ssbEntryValid("K1AF", "14,250", "59", "59", "")).isFalse()
        assertThat(ssbEntryValid("K1AF", "14.250", "99", "59", "")).isFalse()
        assertThat(ssbEntryValid("K1AF", "14.250", "59", "", "")).isFalse()
        assertThat(ssbEntryValid("K1AF", "14.250", "59", "59", "XX")).isFalse()
    }

    @Test
    fun `record carries SSB mode, normalized calls and the dial frequency`() {
        val record =
            buildSsbQslRecord(
                nowMs = now,
                myCallsign = "KS3CKC",
                myGrid = "EM28",
                toCallsign = " k1af ",
                toGrid = "fn42",
                rstSent = 59,
                rstRcvd = 57,
                freqHz = 14_250_000L,
            )

        assertThat(record.mode).isEqualTo("SSB")
        assertThat(record.toCallsign).isEqualTo("K1AF")
        assertThat(record.toMaidenGrid).isEqualTo("FN42")
        assertThat(record.bandFreq).isEqualTo(14_250_000L)
        assertThat(record.bandLength).isEqualTo("20m")
        assertThat(record.sendReport).isEqualTo(59)
        assertThat(record.receivedReport).isEqualTo(57)
        // TIME_ON is what the server's dedupe key rounds to the minute, so the
        // record must timestamp in UTC exactly like the FT8 path does.
        assertThat(record.qso_date).isEqualTo("20250731")
        assertThat(record.time_on).isEqualTo("140509")
    }

    @Test
    fun `record survives a rover with no grid of their own`() {
        val record =
            buildSsbQslRecord(
                nowMs = now,
                myCallsign = "KS3CKC",
                myGrid = "",
                toCallsign = "K1AF",
                toGrid = "",
                rstSent = 59,
                rstRcvd = 59,
                freqHz = 0L,
            )
        assertThat(record.mode).isEqualTo("SSB")
        assertThat(record.toMaidenGrid).isEmpty()
        assertThat(record.bandFreq).isEqualTo(0L)
    }
}
