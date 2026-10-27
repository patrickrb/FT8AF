package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.FT8Common
import com.k1af.ft8af.Ft8Message
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the #953 banner SNR helpers: [latestDecodeFrom], [heardSnr],
 * [sentReportOrNull], [qsoBannerSnr] and [bannerSnrLabel]. The QSO banner and the
 * QSO sheet's Signal card read the same decode, and the report we sent is shown
 * labelled, only once it has actually gone out.
 */
@RunWith(RobolectricTestRunner::class)
class QsoBannerSnrTest {

    private fun msg(from: String?, snr: Int, utc: Long = 0L): Ft8Message =
        Ft8Message(FT8Common.FT8_MODE).apply {
            callsignFrom = from
            callsignTo = "K1AF"
            extraInfo = "-10"
            this.snr = snr
            utcTime = utc
        }

    // --- latestDecodeFrom ---

    @Test
    fun `latestDecodeFrom picks the newest decode from the station`() {
        val first = msg("R8PD", -14, 1_000L)
        val other = msg("W1AW", 3, 2_000L)
        val latest = msg("R8PD", -7, 3_000L)

        assertThat(latestDecodeFrom(listOf(first, other, latest), "R8PD")).isSameInstanceAs(latest)
    }

    @Test
    fun `latestDecodeFrom ignores case`() {
        val m = msg("R8PD", -7)
        assertThat(latestDecodeFrom(listOf(m), "r8pd")).isSameInstanceAs(m)
    }

    @Test
    fun `latestDecodeFrom treats hashed and plain forms as the same station`() {
        val hashed = msg("<R8PD>", -12, 1_000L)
        val plain = msg("R8PD", -5, 2_000L)
        val list = listOf(hashed, plain)

        // Sheet (raw, bracketed key) and banner (stripped key) land on the same decode.
        assertThat(latestDecodeFrom(list, "<R8PD>")).isSameInstanceAs(plain)
        assertThat(latestDecodeFrom(list, "R8PD")).isSameInstanceAs(plain)
        assertThat(latestDecodeFrom(listOf(hashed), "R8PD")).isSameInstanceAs(hashed)
    }

    @Test
    fun `latestDecodeFrom returns null for no callsign, no list, or no match`() {
        val list = listOf(msg("R8PD", -7))
        assertThat(latestDecodeFrom(list, null)).isNull()
        assertThat(latestDecodeFrom(list, "")).isNull()
        assertThat(latestDecodeFrom(list, "<>")).isNull()
        assertThat(latestDecodeFrom(null, "R8PD")).isNull()
        assertThat(latestDecodeFrom(list, "W1AW")).isNull()
    }

    @Test
    fun `latestDecodeFrom skips decodes with a null sender`() {
        assertThat(latestDecodeFrom(listOf(msg(null, -7)), "R8PD")).isNull()
    }

    // --- heardSnr ---

    @Test
    fun `heardSnr prefers the latest decode`() {
        assertThat(heardSnr(msg("R8PD", -7), fallback = -14)).isEqualTo(-7)
    }

    @Test
    fun `heardSnr falls back when the station is not in the list`() {
        assertThat(heardSnr(null, fallback = -9)).isEqualTo(-9)
    }

    @Test
    fun `heardSnr falls back when the latest decode has no SNR`() {
        assertThat(heardSnr(msg("R8PD", Ft8Message.SNR_UNKNOWN), fallback = -9)).isEqualTo(-9)
    }

    @Test
    fun `heardSnr is null when nothing is known`() {
        assertThat(heardSnr(null, fallback = null)).isNull()
        assertThat(heardSnr(null, fallback = Ft8Message.SNR_UNKNOWN)).isNull()
    }

    // --- sentReportOrNull ---

    @Test
    fun `sentReportOrNull hides the not-sent sentinel`() {
        assertThat(sentReportOrNull(-100)).isNull()
    }

    @Test
    fun `sentReportOrNull maps an unknown SNR to the 0 that goes on the air`() {
        assertThat(sentReportOrNull(Ft8Message.SNR_UNKNOWN)).isEqualTo(0)
    }

    @Test
    fun `sentReportOrNull passes a real report through`() {
        assertThat(sentReportOrNull(-14)).isEqualTo(-14)
        assertThat(sentReportOrNull(5)).isEqualTo(5)
    }

    // --- qsoBannerSnr ---

    @Test
    fun `banner shows heard and labels a different sent report`() {
        // The #953 case: sheet says -7, we sent -14.
        assertThat(qsoBannerSnr(heard = -7, sent = -14)).isEqualTo(QsoBannerSnr(heard = -7, sent = -14))
    }

    @Test
    fun `sent report is dropped when it matches what we hear`() {
        assertThat(qsoBannerSnr(heard = -7, sent = -7)).isEqualTo(QsoBannerSnr(heard = -7, sent = null))
    }

    @Test
    fun `sent report alone when nothing is heard`() {
        assertThat(qsoBannerSnr(heard = null, sent = -14)).isEqualTo(QsoBannerSnr(heard = null, sent = -14))
    }

    @Test
    fun `no sent label before a report goes out`() {
        assertThat(qsoBannerSnr(heard = -7, sent = null)).isEqualTo(QsoBannerSnr(heard = -7, sent = null))
    }

    // --- bannerSnrLabel ---

    @Test
    fun `bannerSnrLabel signs the value`() {
        assertThat(bannerSnrLabel(3)).isEqualTo("+3dB")
        assertThat(bannerSnrLabel(0)).isEqualTo("+0dB")
        assertThat(bannerSnrLabel(-14)).isEqualTo("-14dB")
    }
}
