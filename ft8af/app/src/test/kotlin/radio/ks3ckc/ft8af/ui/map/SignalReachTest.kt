package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure unit tests for [summarizeSignalReach] — the "how far is my signal
 * getting?" headline derived from PSK Reporter "heard me" spots. No
 * Android/Compose runtime is touched.
 */
class SignalReachTest {

    private data class Spot(
        override val callsign: String,
        override val lat: Double,
        override val lon: Double,
        override val snr: Int,
    ) : ReachSpot

    // Operator sits roughly at Kansas (EM29-ish) for distance sanity checks.
    private val opLat = 39.0
    private val opLon = -95.0

    @Test
    fun emptyList_returnsNull() {
        assertThat(summarizeSignalReach(emptyList(), opLat, opLon)).isNull()
    }

    @Test
    fun countsReceivers_andPicksFurthest() {
        val spots = listOf(
            Spot("W1AW", 41.7, -72.7, -5),    // Connecticut — close
            Spot("G0ABC", 51.5, 0.0, -12),    // England — ~7000 km
            Spot("VK3XYZ", -37.8, 144.9, -18), // Australia — ~16000 km, furthest
        )
        val reach = summarizeSignalReach(spots, opLat, opLon)!!
        assertThat(reach.receivers).isEqualTo(3)
        assertThat(reach.furthestCall).isEqualTo("VK3XYZ")
        assertThat(reach.furthestKm).isGreaterThan(14000.0)
    }

    @Test
    fun picksStrongestSnr() {
        val spots = listOf(
            Spot("W1AW", 41.7, -72.7, -15),
            Spot("K5AAA", 30.0, -97.0, -3),   // strongest
            Spot("G0ABC", 51.5, 0.0, -12),
        )
        val reach = summarizeSignalReach(spots, opLat, opLon)!!
        assertThat(reach.strongestCall).isEqualTo("K5AAA")
        assertThat(reach.strongestSnr).isEqualTo(-3)
    }

    @Test
    fun deduplicatesByCallsign_keepingStrongestReport() {
        // Same station heard us twice; count it once and keep the better SNR.
        val spots = listOf(
            Spot("W1AW", 41.7, -72.7, -18),
            Spot("w1aw", 41.7, -72.7, -6),    // lower-case + stronger
        )
        val reach = summarizeSignalReach(spots, opLat, opLon)!!
        assertThat(reach.receivers).isEqualTo(1)
        assertThat(reach.strongestCall).isEqualTo("W1AW")
        assertThat(reach.strongestSnr).isEqualTo(-6)
    }

    @Test
    fun noOperatorGrid_stillReportsCountAndSignal_butNoDistance() {
        val spots = listOf(
            Spot("W1AW", 41.7, -72.7, -5),
            Spot("G0ABC", 51.5, 0.0, -12),
        )
        val reach = summarizeSignalReach(spots, null, null)!!
        assertThat(reach.receivers).isEqualTo(2)
        assertThat(reach.strongestCall).isEqualTo("W1AW")
        assertThat(reach.furthestKm).isEqualTo(0.0)
        assertThat(reach.furthestCall).isEmpty()
    }

    @Test
    fun blankCallsigns_areIgnored() {
        val spots = listOf(
            Spot("   ", 41.7, -72.7, -5),
            Spot("G0ABC", 51.5, 0.0, -12),
        )
        val reach = summarizeSignalReach(spots, opLat, opLon)!!
        assertThat(reach.receivers).isEqualTo(1)
        assertThat(reach.strongestCall).isEqualTo("G0ABC")
    }

    @Test
    fun allBlankCallsigns_returnNull() {
        val spots = listOf(Spot("", 41.7, -72.7, -5), Spot("  ", 51.5, 0.0, -12))
        assertThat(summarizeSignalReach(spots, opLat, opLon)).isNull()
    }
}
