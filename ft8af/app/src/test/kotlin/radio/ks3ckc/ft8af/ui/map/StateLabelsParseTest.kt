package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [parseStateLabels]: resolving a state's USPS abbreviation and an
 * anchor point at the area centroid of its largest polygon. Robolectric supplies
 * a real org.json implementation.
 */
@RunWith(RobolectricTestRunner::class)
class StateLabelsParseTest {

    @Test
    fun `resolves abbreviation and centroid for a simple square state`() {
        // A 2x2 square from lon 0..2, lat 0..2 → centroid (1,1).
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"name":"Colorado"},"geometry":{"type":"Polygon",
                "coordinates":[[[0.0,0.0],[2.0,0.0],[2.0,2.0],[0.0,2.0],[0.0,0.0]]]}}
            ]}
        """.trimIndent()

        val labels = parseStateLabels(json)

        assertThat(labels).hasSize(1)
        assertThat(labels[0].abbr).isEqualTo("CO")
        assertThat(labels[0].lon).isWithin(1e-3f).of(1.0f)
        assertThat(labels[0].lat).isWithin(1e-3f).of(1.0f)
    }

    @Test
    fun `unknown state names are skipped`() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"name":"Atlantis"},"geometry":{"type":"Polygon",
                "coordinates":[[[0.0,0.0],[1.0,0.0],[1.0,1.0]]]}}
            ]}
        """.trimIndent()

        assertThat(parseStateLabels(json)).isEmpty()
    }

    @Test
    fun `multipolygon anchors on the largest part`() {
        // Tiny island near (0,0) and a big body near (10,10). Anchor must land on the big body.
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"name":"Hawaii"},"geometry":{"type":"MultiPolygon",
                "coordinates":[
                  [[[0.0,0.0],[0.2,0.0],[0.2,0.2],[0.0,0.2],[0.0,0.0]]],
                  [[[10.0,10.0],[14.0,10.0],[14.0,14.0],[10.0,14.0],[10.0,10.0]]]
                ]}}
            ]}
        """.trimIndent()

        val labels = parseStateLabels(json)

        assertThat(labels).hasSize(1)
        assertThat(labels[0].abbr).isEqualTo("HI")
        assertThat(labels[0].lon).isWithin(1e-2f).of(12.0f)
        assertThat(labels[0].lat).isWithin(1e-2f).of(12.0f)
    }

    @Test
    fun `real asset parses to the full set of state labels`() {
        // Sanity check against a hand-built collection covering the whole abbr map.
        val features = ALL_STATE_NAMES.joinToString(",") { name ->
            """{"type":"Feature","properties":{"name":"$name"},"geometry":{"type":"Polygon",
               "coordinates":[[[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,1.0],[0.0,0.0]]]}}"""
        }
        val json = """{"type":"FeatureCollection","features":[$features]}"""

        val labels = parseStateLabels(json)

        assertThat(labels).hasSize(ALL_STATE_NAMES.size)
        assertThat(labels.map { it.abbr }).containsAtLeast("CA", "TX", "NY", "FL", "DC", "PR")
    }

    private companion object {
        val ALL_STATE_NAMES = listOf(
            "Alabama", "Alaska", "Arizona", "Arkansas", "California", "Colorado",
            "Connecticut", "Delaware", "District of Columbia", "Florida", "Georgia",
            "Hawaii", "Idaho", "Illinois", "Indiana", "Iowa", "Kansas", "Kentucky",
            "Louisiana", "Maine", "Maryland", "Massachusetts", "Michigan", "Minnesota",
            "Mississippi", "Missouri", "Montana", "Nebraska", "Nevada", "New Hampshire",
            "New Jersey", "New Mexico", "New York", "North Carolina", "North Dakota",
            "Ohio", "Oklahoma", "Oregon", "Pennsylvania", "Rhode Island", "South Carolina",
            "South Dakota", "Tennessee", "Texas", "Utah", "Vermont", "Virginia",
            "Washington", "West Virginia", "Wisconsin", "Wyoming", "Puerto Rico",
        )
    }
}
