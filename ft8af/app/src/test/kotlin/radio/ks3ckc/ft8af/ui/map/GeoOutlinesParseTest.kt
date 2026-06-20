package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [parseGeoJsonRings], the shared GeoJSON FeatureCollection
 * parser behind both WorldOutlines (land) and UsStateOutlines (state borders).
 * Robolectric supplies a real org.json implementation.
 *
 * Contract: each Polygon contributes its outer ring; each MultiPolygon
 * contributes one outer ring per sub-polygon; inner rings (holes) and
 * non-polygon geometries are ignored. Output is interleaved [lon, lat, ...].
 */
@RunWith(RobolectricTestRunner::class)
class GeoOutlinesParseTest {

    private val tol = 1e-4f

    @Test
    fun `polygon yields one ring with interleaved lon lat in order`() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{},"geometry":{"type":"Polygon",
                "coordinates":[[[-87.0,35.0],[-85.0,34.0],[-86.0,33.0]]]}}
            ]}
        """.trimIndent()

        val rings = parseGeoJsonRings(json)

        assertThat(rings).hasSize(1)
        // 3 points -> 6 interleaved values, lon first.
        assertThat(rings[0].toList()).containsExactly(
            -87.0f, 35.0f, -85.0f, 34.0f, -86.0f, 33.0f,
        ).inOrder()
    }

    @Test
    fun `multipolygon yields one outer ring per sub-polygon`() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{},"geometry":{"type":"MultiPolygon",
                "coordinates":[
                  [[[0.0,0.0],[1.0,0.0],[1.0,1.0]]],
                  [[[10.0,10.0],[11.0,10.0],[11.0,11.0]]]
                ]}}
            ]}
        """.trimIndent()

        val rings = parseGeoJsonRings(json)

        assertThat(rings).hasSize(2)
        assertThat(rings[0][0]).isWithin(tol).of(0.0f)
        assertThat(rings[1][0]).isWithin(tol).of(10.0f)
    }

    @Test
    fun `inner rings (holes) are ignored`() {
        // Outer ring has 4 points (8 values); the hole must not appear.
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{},"geometry":{"type":"Polygon",
                "coordinates":[
                  [[-5.0,-5.0],[5.0,-5.0],[5.0,5.0],[-5.0,5.0]],
                  [[-1.0,-1.0],[1.0,-1.0],[1.0,1.0]]
                ]}}
            ]}
        """.trimIndent()

        val rings = parseGeoJsonRings(json)

        assertThat(rings).hasSize(1)
        assertThat(rings[0]).hasLength(8) // outer ring only
    }

    @Test
    fun `non-polygon geometries are skipped`() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{},"geometry":{"type":"LineString",
                "coordinates":[[0.0,0.0],[1.0,1.0]]}},
              {"type":"Feature","properties":{},"geometry":{"type":"Polygon",
                "coordinates":[[[2.0,2.0],[3.0,2.0],[3.0,3.0]]]}}
            ]}
        """.trimIndent()

        val rings = parseGeoJsonRings(json)

        assertThat(rings).hasSize(1)
        assertThat(rings[0][0]).isWithin(tol).of(2.0f)
    }

    @Test
    fun `empty feature collection yields no rings`() {
        val json = """{"type":"FeatureCollection","features":[]}"""
        assertThat(parseGeoJsonRings(json)).isEmpty()
    }
}
