package radio.ks3ckc.ft8af.rtota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.ft8af.ui.rtota.describeProfile
import radio.ks3ckc.ft8af.ui.rtota.maskKey
import radio.ks3ckc.ft8af.ui.rtota.nextPrivacy

/**
 * The decision logic pulled out of the Compose screen (tap-to-cycle rows, key
 * masking) plus the trip notification's summary line — none of which can be
 * tested through the Composable itself.
 */
@RunWith(RobolectricTestRunner::class)
class RoadTripScreenLogicTest {
    @Test
    fun `key masking never shows a usable credential`() {
        assertThat(maskKey("rtota_1234567890abcdef")).isEqualTo("rtota_…cdef")
        // Short enough to be a typo rather than a key: show nothing at all.
        assertThat(maskKey("abc")).isEqualTo("••••")
    }

    @Test
    fun `privacy cycles through every level and back to account default`() {
        var p = ""
        val seen = mutableListOf<String>()
        repeat(5) {
            p = nextPrivacy(p)
            seen.add(p)
        }
        assertThat(seen).containsExactly("public", "delayed", "followers", "private", "")
            .inOrder()
    }

    @Test
    fun `an unrecognised stored privacy restarts the cycle`() {
        assertThat(nextPrivacy("nonsense")).isEqualTo("public")
    }

    @Test
    fun `the tracking summary states the real numbers`() {
        val text = describeProfile(SmartBeaconProfile.DEFAULT)
        assertThat(text).contains("30 s above 70 mph")
        assertThat(text).contains("3 min")
        assertThat(text).contains("15° + 255/mph")
        assertThat(text).contains("parked")
    }

    @Test
    fun `notification summarises the trip`() {
        val text =
            RtotaTripService.notificationText(
                RtotaTripState(miles = 142.34, sentQsos = 30, pendingQsos = 7, pendingPoints = 5),
            )
        assertThat(text).isEqualTo("142.3 mi · 37 QSOs · 12 waiting")
    }

    @Test
    fun `notification stays quiet when everything is uploaded`() {
        val text = RtotaTripService.notificationText(RtotaTripState(miles = 12.0, sentQsos = 3))
        assertThat(text).isEqualTo("12.0 mi · 3 QSOs")
    }

    @Test
    fun `notification says parked so a quiet trail does not read as broken`() {
        val text =
            RtotaTripService.notificationText(
                RtotaTripState(miles = 88.0, sentQsos = 12, parked = true),
            )
        assertThat(text).isEqualTo("88.0 mi · 12 QSOs · parked")
    }

    @Test
    fun `notification flags an offline start and the last error`() {
        val text =
            RtotaTripService.notificationText(
                RtotaTripState(miles = 0.4, pendingCreate = true, lastError = "no route to host"),
            )
        assertThat(text).contains("offline start")
        assertThat(text).contains("no route to host")
    }
}
