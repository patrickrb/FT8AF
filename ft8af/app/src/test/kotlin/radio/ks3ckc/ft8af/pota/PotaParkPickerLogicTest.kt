package radio.ks3ckc.ft8af.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.ft8af.pota.model.PotaLocation
import radio.ks3ckc.ft8af.pota.model.PotaPark
import radio.ks3ckc.ft8af.pota.model.PotaParkWithDistance
import radio.ks3ckc.ft8af.ui.pota.filterNearbyParks
import radio.ks3ckc.ft8af.ui.pota.filterParks

/**
 * Unit tests for the pure helper functions used by the park picker.
 *
 * [findNearestLocationCodes] and [sortParksByDistance] reach Play-Services `LatLng`
 * and `MaidenheadGrid.getDist()`, so Robolectric is required. The deduplication
 * and filter functions are pure Kotlin but share the test runner for simplicity.
 */
@RunWith(RobolectricTestRunner::class)
class PotaParkPickerLogicTest {

    // -----------------------------------------------------------------------
    // deduplicateRefs
    // -----------------------------------------------------------------------

    @Test
    fun `deduplicateRefs splits comma-separated refs`() {
        val result = deduplicateRefs(listOf("K-1234,K-5678"))
        assertThat(result).containsExactly("K-1234", "K-5678").inOrder()
    }

    @Test
    fun `deduplicateRefs removes duplicates preserving first occurrence`() {
        val result = deduplicateRefs(listOf("K-1234", "K-5678,K-1234"))
        assertThat(result).containsExactly("K-1234", "K-5678").inOrder()
    }

    @Test
    fun `deduplicateRefs trims whitespace and skips empty`() {
        val result = deduplicateRefs(listOf(" K-0001 , , K-0002 "))
        assertThat(result).containsExactly("K-0001", "K-0002").inOrder()
    }

    @Test
    fun `deduplicateRefs returns empty list for empty input`() {
        assertThat(deduplicateRefs(emptyList())).isEmpty()
    }

    @Test
    fun `deduplicateRefs preserves most-recent-first order`() {
        // Simulates 3 activations from newest to oldest
        val result = deduplicateRefs(listOf("K-0003", "K-0002", "K-0001"))
        assertThat(result).containsExactly("K-0003", "K-0002", "K-0001").inOrder()
    }

    // -----------------------------------------------------------------------
    // findNearestLocationCodes
    // -----------------------------------------------------------------------

    @Test
    fun `findNearestLocationCodes picks N closest`() {
        // Philadelphia (39.95, -75.17), locations spread around the US
        val locations = listOf(
            PotaLocation("US-PA", "Pennsylvania", 40.27, -76.88),
            PotaLocation("US-CA", "California", 36.78, -119.42),
            PotaLocation("US-NJ", "New Jersey", 40.06, -74.41),
            PotaLocation("US-TX", "Texas", 31.97, -99.90),
        )
        val result = findNearestLocationCodes(39.95, -75.17, locations, 2)
        assertThat(result).hasSize(2)
        // PA and NJ are closest to Philadelphia
        assertThat(result).containsExactly("US-NJ", "US-PA").inOrder()
    }

    @Test
    fun `findNearestLocationCodes returns fewer than count when input is small`() {
        val locations = listOf(
            PotaLocation("US-PA", "Pennsylvania", 40.27, -76.88),
        )
        val result = findNearestLocationCodes(39.95, -75.17, locations, 5)
        assertThat(result).hasSize(1)
        assertThat(result).containsExactly("US-PA")
    }

    @Test
    fun `broadening candidate count rescues real region from POTA's bad location centers`() {
        // POTA's /locations endpoint ships wrong centers for some foreign
        // regions: ZA-FS, RU-VL and RO-OT all report centers in Kansas. A user
        // in Kansas (39.0, -94.6) therefore sees those mislocated regions
        // out-rank their real state, US-KS.
        val locations = listOf(
            PotaLocation("ZA-FS", "Free State", 38.9717, -95.2355),   // bad: really South Africa
            PotaLocation("RU-VL", "Vladimir", 39.0904, -94.5877),     // bad: really Russia
            PotaLocation("RO-OT", "Olt", 39.768, -94.8509),           // bad: really Romania
            PotaLocation("US-KS", "Kansas", 38.5266, -96.7265),       // correct
            PotaLocation("US-MO", "Missouri", 38.4561, -92.2884),     // correct
        )

        // The old behaviour: only the 3 nearest centers — all mislocated.
        val narrow = findNearestLocationCodes(39.0, -94.6, locations, 3)
        assertThat(narrow).doesNotContain("US-KS")

        // The fix: a wider candidate net pulls in the user's real region. Use
        // the production NEARBY_LOCATION_CANDIDATES value (12) to pin the intent;
        // with only 5 test rows it returns all of them, US-KS/US-MO included.
        val wide = findNearestLocationCodes(39.0, -94.6, locations, 12)
        assertThat(wide).contains("US-KS")
        assertThat(wide).contains("US-MO")
    }

    // -----------------------------------------------------------------------
    // sortParksByDistance
    // -----------------------------------------------------------------------

    @Test
    fun `sortParksByDistance orders by distance ascending`() {
        val parks = listOf(
            parkAt("K-0001", "Far Park", 34.05, -118.24),       // LA
            parkAt("K-0002", "Near Park", 40.06, -74.41),       // NJ
            parkAt("K-0003", "Medium Park", 38.90, -77.03),     // DC
        )
        // From Philadelphia
        val result = sortParksByDistance(parks, 39.95, -75.17, 50)
        assertThat(result.map { it.park.reference })
            .containsExactly("K-0002", "K-0003", "K-0001")
            .inOrder()
    }

    @Test
    fun `sortParksByDistance respects limit`() {
        val parks = (1..100).map { i ->
            parkAt("K-%04d".format(i), "Park $i", 40.0 + i * 0.01, -75.0)
        }
        val result = sortParksByDistance(parks, 40.0, -75.0, 50)
        assertThat(result).hasSize(50)
    }

    @Test
    fun `sortParksByDistance skips parks with zero coordinates`() {
        val parks = listOf(
            parkAt("K-0001", "No Coords", 0.0, 0.0),
            parkAt("K-0002", "Real Park", 40.06, -74.41),
        )
        val result = sortParksByDistance(parks, 39.95, -75.17, 50)
        assertThat(result.map { it.park.reference }).containsExactly("K-0002")
    }

    @Test
    fun `sortParksByDistance drops parks beyond the max distance cap`() {
        // A park list mixing genuinely-nearby parks with one from a mislocated
        // region (its real coordinates put it on another continent). The cap
        // keeps the far one from masquerading as nearby.
        val parks = listOf(
            parkAt("US-0001", "Local Park", 40.06, -74.41),   // NJ — near Philadelphia
            parkAt("RU-0024", "Far Park", 55.0, 40.0),        // Russia — ~8000 km away
        )
        val result = sortParksByDistance(parks, 39.95, -75.17, 50, maxDistanceKm = 1_000.0)
        assertThat(result.map { it.park.reference }).containsExactly("US-0001")
    }

    @Test
    fun `sortParksByDistance keeps all parks when no cap is given`() {
        val parks = listOf(
            parkAt("US-0001", "Local Park", 40.06, -74.41),
            parkAt("RU-0024", "Far Park", 55.0, 40.0),
        )
        val result = sortParksByDistance(parks, 39.95, -75.17, 50)
        assertThat(result.map { it.park.reference })
            .containsExactly("US-0001", "RU-0024")
            .inOrder()
    }

    @Test
    fun `sortParksByDistance includes distance in result`() {
        val parks = listOf(parkAt("K-0001", "Park", 40.06, -74.41))
        val result = sortParksByDistance(parks, 39.95, -75.17, 50)
        assertThat(result).hasSize(1)
        assertThat(result[0].distanceKm).isGreaterThan(0.0)
    }

    // -----------------------------------------------------------------------
    // filterParks
    // -----------------------------------------------------------------------

    @Test
    fun `filterParks returns all when query is blank`() {
        val parks = listOf(
            parkAt("K-0001", "Valley Forge"),
            parkAt("K-0002", "Liberty Bell"),
        )
        assertThat(filterParks(parks, "")).hasSize(2)
        assertThat(filterParks(parks, "  ")).hasSize(2)
    }

    @Test
    fun `filterParks matches reference case-insensitively`() {
        val parks = listOf(
            parkAt("K-0001", "Valley Forge"),
            parkAt("K-0002", "Liberty Bell"),
        )
        val result = filterParks(parks, "k-0001")
        assertThat(result).hasSize(1)
        assertThat(result[0].reference).isEqualTo("K-0001")
    }

    @Test
    fun `filterParks matches name substring`() {
        val parks = listOf(
            parkAt("K-0001", "Valley Forge National Historical Park"),
            parkAt("K-0002", "Liberty Bell Trail"),
        )
        val result = filterParks(parks, "forge")
        assertThat(result).hasSize(1)
        assertThat(result[0].name).contains("Forge")
    }

    @Test
    fun `filterParks returns empty when no match`() {
        val parks = listOf(parkAt("K-0001", "Valley Forge"))
        assertThat(filterParks(parks, "xyz")).isEmpty()
    }

    // -----------------------------------------------------------------------
    // filterNearbyParks
    // -----------------------------------------------------------------------

    @Test
    fun `filterNearbyParks returns all when query is blank`() {
        val items = listOf(
            PotaParkWithDistance(parkAt("K-0001", "Park One"), 10.0),
            PotaParkWithDistance(parkAt("K-0002", "Park Two"), 20.0),
        )
        assertThat(filterNearbyParks(items, "")).hasSize(2)
    }

    @Test
    fun `filterNearbyParks matches by reference or name`() {
        val items = listOf(
            PotaParkWithDistance(parkAt("K-0001", "Park Alpha"), 10.0),
            PotaParkWithDistance(parkAt("K-0002", "Park Beta"), 20.0),
        )
        assertThat(filterNearbyParks(items, "alpha")).hasSize(1)
        assertThat(filterNearbyParks(items, "K-0002")).hasSize(1)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun parkAt(
        ref: String,
        name: String,
        lat: Double = 0.0,
        lng: Double = 0.0,
    ) = PotaPark(
        reference = ref,
        name = name,
        locationDesc = "",
        latitude = lat,
        longitude = lng,
    )
}
