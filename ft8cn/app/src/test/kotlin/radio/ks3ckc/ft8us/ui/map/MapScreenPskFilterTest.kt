package radio.ks3ckc.ft8us.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8us.pskreporter.PskReporterSpot

class MapScreenPskFilterTest {

    @Test
    fun filterPskSpots_filtersByCurrentBandModeAndTime() {
        val now = 1_700_000_000L
        val spots = listOf(
            spot(freq = 14_076_000L, mode = "FT8", flow = now - 120),
            spot(freq = 7_074_000L, mode = "FT8", flow = now - 120),
            spot(freq = 14_076_000L, mode = "FT4", flow = now - 120),
            spot(freq = 14_076_000L, mode = "FT8", flow = now - 8_000),
        )

        val filtered = filterPskSpots(
            spots = spots,
            bandFilter = PskBandFilter.CURRENT,
            modeFilter = PskModeFilter.FT8,
            currentBandHz = 14_074_000L,
            maxAgeSeconds = 3600,
            nowEpochSeconds = now,
        )

        assertThat(filtered).hasSize(1)
        assertThat(filtered.single().frequencyHz).isEqualTo(14_076_000L)
        assertThat(filtered.single().mode).isEqualTo("FT8")
    }

    @Test
    fun filterPskSpots_allBandAndAllMode_keepsMultipleBands() {
        val now = 1_700_000_000L
        val spots = listOf(
            spot(freq = 14_076_000L, mode = "FT8", flow = now - 10),
            spot(freq = 7_074_000L, mode = "FT4", flow = now - 10),
        )

        val filtered = filterPskSpots(
            spots = spots,
            bandFilter = PskBandFilter.ALL,
            modeFilter = PskModeFilter.ALL,
            currentBandHz = 14_074_000L,
            maxAgeSeconds = 3600,
            nowEpochSeconds = now,
        )

        assertThat(filtered).hasSize(2)
    }

    private fun spot(freq: Long, mode: String, flow: Long): PskReporterSpot = PskReporterSpot(
        senderCallsign = "W1AW",
        receiverCallsign = "RX",
        receiverGrid = "FN42",
        receiverLat = 42.0,
        receiverLon = -71.0,
        frequencyHz = freq,
        snr = -10,
        mode = mode,
        flowStartSeconds = flow,
    )
}
