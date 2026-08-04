package radio.ks3ckc.ft8af.rota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * QSLRecord → live QSO mapping. Exercised through the pure entry point so no
 * QSLRecord (and therefore no GeneralVariables / rig layer) is needed.
 */
class RotaQsoMapperTest {
    private val now = 1_753_970_709_000L

    @Test
    fun `a normal contact maps every field`() {
        val qso =
            RotaQsoMapper.buildTripQso(
                toCallsign = "k1af",
                qsoDate = "20250731",
                timeOn = "140509",
                qsoDateOff = "20250731",
                timeOff = "140609",
                band = "20m",
                mode = "FT8",
                toGrid = "FN42",
                sendReport = -12,
                receivedReport = 3,
                bandFreqHz = 14_074_000L,
                roverLat = 39.7,
                roverLon = -104.9,
                state = "Colorado",
                nowMs = now,
            )

        assertThat(qso).isNotNull()
        assertThat(qso!!.callsign).isEqualTo("k1af") // uppercased at encode time
        assertThat(qso.timestampMs).isEqualTo(now)
        assertThat(qso.band).isEqualTo("20m")
        assertThat(qso.sentReport).isEqualTo("-12")
        assertThat(qso.rcvdReport).isEqualTo("+03")
        assertThat(qso.frequencyKhz).isWithin(1e-9).of(14074.0)
        assertThat(qso.state).isEqualTo("Colorado")
    }

    @Test
    fun `an SSB contact keeps plain RST reports on the wire`() {
        val qso =
            RotaQsoMapper.buildTripQso(
                toCallsign = "K1AF",
                qsoDate = "20250731",
                timeOn = "140509",
                qsoDateOff = "20250731",
                timeOff = "140609",
                band = "20m",
                mode = "SSB",
                toGrid = "FN42",
                sendReport = 59,
                receivedReport = 57,
                bandFreqHz = 14_250_000L,
                roverLat = 39.7,
                roverLon = -104.9,
                state = "Colorado",
                nowMs = now,
            )

        assertThat(qso!!.mode).isEqualTo("SSB")
        // "59", never the FT8-style "+59" — the signed form would disagree with
        // the ADIF copy and defeat the server's dedupe.
        assertThat(qso.sentReport).isEqualTo("59")
        assertThat(qso.rcvdReport).isEqualTo("57")
        assertThat(qso.frequencyKhz).isWithin(1e-9).of(14250.0)
    }

    @Test
    fun `TIME_ON wins over TIME_OFF so live and ADIF copies dedupe against each other`() {
        val qso =
            RotaQsoMapper.buildTripQso(
                toCallsign = "K1AF",
                qsoDate = "20250731",
                timeOn = "140509",
                qsoDateOff = "20250731",
                timeOff = "141509",
                band = null,
                mode = null,
                toGrid = null,
                sendReport = -100,
                receivedReport = -100,
                bandFreqHz = 0L,
                roverLat = null,
                roverLon = null,
                state = null,
                nowMs = now,
            )
        assertThat(qso!!.timestampMs).isEqualTo(now)
    }

    @Test
    fun `an unusable TIME_ON falls back to the off time`() {
        val qso =
            RotaQsoMapper.buildTripQso(
                toCallsign = "K1AF",
                qsoDate = "",
                timeOn = "",
                qsoDateOff = "20250731",
                timeOff = "140509",
                band = null,
                mode = null,
                toGrid = null,
                sendReport = -100,
                receivedReport = -100,
                bandFreqHz = 0L,
                roverLat = null,
                roverLon = null,
                state = null,
                nowMs = 999L,
            )
        assertThat(qso!!.timestampMs).isEqualTo(now)
    }

    @Test
    fun `a record with no usable timestamps falls back to now`() {
        val qso =
            RotaQsoMapper.buildTripQso(
                toCallsign = "K1AF",
                qsoDate = null,
                timeOn = null,
                qsoDateOff = null,
                timeOff = null,
                band = null,
                mode = null,
                toGrid = null,
                sendReport = -100,
                receivedReport = -100,
                bandFreqHz = 0L,
                roverLat = null,
                roverLon = null,
                state = null,
                nowMs = 4242L,
            )
        assertThat(qso!!.timestampMs).isEqualTo(4242L)
    }

    @Test
    fun `an SWL record with no reports omits them instead of sending the sentinel`() {
        val qso =
            RotaQsoMapper.buildTripQso(
                toCallsign = "K1AF",
                qsoDate = "20250731",
                timeOn = "140509",
                qsoDateOff = null,
                timeOff = null,
                band = "20m",
                mode = "FT8",
                toGrid = "",
                sendReport = -100,
                receivedReport = -120,
                bandFreqHz = 14_074_000L,
                roverLat = null,
                roverLon = null,
                state = null,
                nowMs = now,
            )
        assertThat(qso!!.sentReport).isNull()
        assertThat(qso.rcvdReport).isNull()
        assertThat(qso.grid).isNull()
        // No GPS fix yet — the contact still goes up, just without a position.
        assertThat(qso.roverLat).isNull()
    }

    @Test
    fun `a record with no callsign is not queued at all`() {
        val qso =
            RotaQsoMapper.buildTripQso(
                toCallsign = "  ",
                qsoDate = "20250731",
                timeOn = "140509",
                qsoDateOff = null,
                timeOff = null,
                band = "20m",
                mode = "FT8",
                toGrid = null,
                sendReport = 0,
                receivedReport = 0,
                bandFreqHz = 14_074_000L,
                roverLat = null,
                roverLon = null,
                state = null,
                nowMs = now,
            )
        assertThat(qso).isNull()
    }
}
