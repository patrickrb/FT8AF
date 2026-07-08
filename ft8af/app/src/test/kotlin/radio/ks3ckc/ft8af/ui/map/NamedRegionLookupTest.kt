package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Point-in-polygon region lookup ([parseNamedRegions], [pointInRing],
 * [regionNameAt]) — the basis for resolving a QSO partner's US state from its grid.
 */
@RunWith(RobolectricTestRunner::class)
class NamedRegionLookupTest {

    @Test
    fun `point inside a ring is detected, outside is not`() {
        // Unit square lon 0..10, lat 0..10.
        val ring = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f, 0f, 0f)
        assertThat(pointInRing(ring, 5f, 5f)).isTrue()
        assertThat(pointInRing(ring, 15f, 5f)).isFalse()
        assertThat(pointInRing(ring, -1f, 5f)).isFalse()
    }

    @Test
    fun `regionNameAt returns the containing region`() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"name":"Kansas"},"geometry":{"type":"Polygon",
                "coordinates":[[[-102.0,37.0],[-94.6,37.0],[-94.6,40.0],[-102.0,40.0],[-102.0,37.0]]]}},
              {"type":"Feature","properties":{"name":"Colorado"},"geometry":{"type":"Polygon",
                "coordinates":[[[-109.0,37.0],[-102.0,37.0],[-102.0,41.0],[-109.0,41.0],[-109.0,37.0]]]}}
            ]}
        """.trimIndent()

        val regions = parseNamedRegions(json)

        // A point near Wichita, KS (-97.3, 37.7) resolves to Kansas.
        assertThat(regionNameAt(regions, -97.3f, 37.7f)).isEqualTo("Kansas")
        // A point near Denver, CO (-105.0, 39.7) resolves to Colorado.
        assertThat(regionNameAt(regions, -105.0f, 39.7f)).isEqualTo("Colorado")
        // Out in the Atlantic → no region.
        assertThat(regionNameAt(regions, -40.0f, 30.0f)).isNull()
    }

    @Test
    fun `multipolygon regions match on any part`() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"name":"Michigan"},"geometry":{"type":"MultiPolygon",
                "coordinates":[
                  [[[-90.0,45.0],[-88.0,45.0],[-88.0,47.0],[-90.0,47.0],[-90.0,45.0]]],
                  [[[-86.0,42.0],[-83.0,42.0],[-83.0,45.0],[-86.0,45.0],[-86.0,42.0]]]
                ]}}
            ]}
        """.trimIndent()

        val regions = parseNamedRegions(json)
        assertThat(regionNameAt(regions, -89.0f, 46.0f)).isEqualTo("Michigan") // upper peninsula
        assertThat(regionNameAt(regions, -84.0f, 43.0f)).isEqualTo("Michigan") // lower peninsula
    }
}
