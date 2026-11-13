package radio.ks3ckc.ft8af.bandadvisor.personal

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.pskreporter.PskReporterSpot

class PersonalAnalyticsTest {
    // Observer in EM28 (Kansas-ish).
    private val myLat = 38.5
    private val myLon = -95.0

    private fun spot(
        callsign: String,
        grid: String,
        lat: Double,
        lon: Double,
        freqHz: Long = 14_074_000,
        snr: Int = -10,
        flow: Long = 1_000,
    ) = PskReporterSpot(callsign, grid, lat, lon, freqHz, snr, "FT8", flow)

    private val bandOf: (Long) -> String? = { freq ->
        when (freq) {
            in 14_000_000L..14_350_000L -> "20m"
            in 7_000_000L..7_300_000L -> "40m"
            else -> null
        }
    }

    @Test
    fun `computes per-band and overall stats`() {
        val spots = listOf(
            spot("DL1ABC", "JO62", 52.5, 13.4, snr = -3, flow = 3_000), // Berlin ~7900 km
            spot("G4XYZ", "IO91", 51.5, -0.1, snr = -11, flow = 2_000), // London
            spot("W1AW", "FN31", 41.7, -72.7, snr = -20, flow = 1_000), // Connecticut
            spot("W9ABC", "EN52", 42.0, -88.0, freqHz = 7_074_000, snr = -5, flow = 4_000),
        )
        val report = computePersonalAnalytics(spots, myLat, myLon, bandOf) { call ->
            when {
                call.startsWith("DL") -> "Germany"
                call.startsWith("G") -> "England"
                else -> "United States"
            }
        }

        assertThat(report.perBand.keys).containsExactly("20m", "40m")
        val band20 = report.perBand["20m"]!!
        assertThat(band20.reports).isEqualTo(3)
        assertThat(band20.uniqueReceivers).isEqualTo(3)
        assertThat(band20.countriesReached).isEqualTo(3)
        assertThat(band20.bestSnrDb).isEqualTo(-3)
        assertThat(band20.medianSnrDb).isEqualTo(-11)
        // Berlin is the farthest 20m receiver: ~7900 km from EM28.
        assertThat(band20.maxDistanceKm).isAtLeast(7_000)
        assertThat(band20.maxDistanceKm).isAtMost(8_500)
        assertThat(band20.mostRecentReportEpochSec).isEqualTo(3_000)
        // Europe (NE octant) + domestic east: at least 2 direction octants.
        assertThat(band20.directionOctants).isAtLeast(2)

        assertThat(report.overall.reports).isEqualTo(4)
        assertThat(report.overall.uniqueGridFields).isEqualTo(4)
    }

    @Test
    fun `spots on unmapped frequencies are dropped`() {
        val report = computePersonalAnalytics(
            listOf(spot("K0AAA", "EM48", 38.6, -90.2, freqHz = 999_000)),
            myLat, myLon, bandOf,
        )
        assertThat(report.overall.reports).isEqualTo(0)
        assertThat(report.perBand).isEmpty()
    }

    @Test
    fun `empty input produces an explicit empty state`() {
        val report = computePersonalAnalytics(emptyList(), myLat, myLon, bandOf)
        assertThat(report.overall.reports).isEqualTo(0)
        assertThat(report.overall.bestSnrDb).isNull()
        assertThat(report.overall.mostRecentReportEpochSec).isNull()
    }

    @Test
    fun `duplicate receivers count once`() {
        val spots = listOf(
            spot("W1AW", "FN31", 41.7, -72.7, flow = 1),
            spot("w1aw", "FN31", 41.7, -72.7, flow = 2),
        )
        val report = computePersonalAnalytics(spots, myLat, myLon, bandOf)
        assertThat(report.overall.reports).isEqualTo(2)
        assertThat(report.overall.uniqueReceivers).isEqualTo(1)
    }

    @Test
    fun `baseline comparison suppressed for small current samples`() {
        assertThat(baselineComparison(MIN_REPORTS_FOR_COMPARISON - 1, 9000, listOf(5000, 6000, 5500)))
            .isNull()
    }

    @Test
    fun `baseline comparison suppressed without enough history`() {
        assertThat(baselineComparison(20, 9000, listOf(5000, 6000))).isNull()
        assertThat(baselineComparison(20, 9000, emptyList())).isNull()
    }

    @Test
    fun `baseline comparison is a signed fraction of the historical median`() {
        // Median of history = 6000; 7200 is +20%.
        val up = baselineComparison(20, 7200, listOf(5000, 6000, 7000))!!
        assertThat(up).isWithin(1e-9).of(0.2)
        val down = baselineComparison(20, 3000, listOf(5000, 6000, 7000))!!
        assertThat(down).isWithin(1e-9).of(-0.5)
    }

    @Test
    fun `median handles odd and even sizes`() {
        assertThat(medianOf(listOf(3.0, 1.0, 2.0))).isEqualTo(2.0)
        assertThat(medianOf(listOf(4.0, 1.0, 2.0, 3.0))).isEqualTo(2.5)
    }

    @Test
    fun `haversine sanity`() {
        // EM28 to Berlin ~ 7,900 km; to itself 0.
        assertThat(haversineKm(myLat, myLon, 52.5, 13.4)).isWithin(300.0).of(7_900.0)
        assertThat(haversineKm(myLat, myLon, myLat, myLon)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `azimuth octants bucket correctly`() {
        assertThat(azimuthOctant(0.0, 0.0, 10.0, 0.0)).isEqualTo(0) // due north
        assertThat(azimuthOctant(0.0, 0.0, 0.0, 10.0)).isEqualTo(2) // due east
        assertThat(azimuthOctant(0.0, 0.0, -10.0, 0.0)).isEqualTo(4) // due south
        assertThat(azimuthOctant(0.0, 0.0, 0.0, -10.0)).isEqualTo(6) // due west
        assertThat(azimuthOctant(1.0, 1.0, 1.0, 1.0)).isEqualTo(0) // degenerate
    }
}
