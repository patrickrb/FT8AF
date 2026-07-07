package radio.ks3ckc.ft8af.car

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Geometry for the car map's world and fit-to-points projections
 * ([CarMapProjection]). Pure math, so no Robolectric runner is needed.
 */
class CarMapProjectionTest {

    private val w = 800
    private val h = 480

    @Test
    fun world_mapsOriginToSurfaceCentreAndEdgesToEdges() {
        val p = CarMapProjection.world(w, h)
        assertThat(p.x(0f)).isWithin(TOL).of(400f)
        assertThat(p.y(0f)).isWithin(TOL).of(240f)
        assertThat(p.x(180f)).isWithin(TOL).of(800f)
        assertThat(p.x(-180f)).isWithin(TOL).of(0f)
        assertThat(p.y(90f)).isWithin(TOL).of(0f)
        assertThat(p.y(-90f)).isWithin(TOL).of(480f)
        assertThat(p.zoomLevel).isEqualTo(1f)
    }

    @Test
    fun fit_centresOnTheMidpointOfThePoints() {
        // Operator in North America, partner in Europe.
        val op = 40.0 to -105.0     // (lat, lon)
        val partner = 51.0 to 0.0
        val p = CarMapProjection.fit(
            width = w, height = h,
            cx = 400f, cy = 240f, contentW = 800f, contentH = 480f,
            points = listOf(op, partner),
        )
        // The geographic midpoint lands at the content centre.
        val midLon = ((op.second + partner.second) / 2).toFloat()
        val midLat = ((op.first + partner.first) / 2).toFloat()
        assertThat(p.x(midLon)).isWithin(TOL).of(400f)
        assertThat(p.y(midLat)).isWithin(TOL).of(240f)
    }

    @Test
    fun fit_zoomsInSoBothPointsSitInsideTheContentBox() {
        val op = 40.0 to -105.0
        val partner = 51.0 to 0.0
        val p = CarMapProjection.fit(
            width = w, height = h,
            cx = 400f, cy = 240f, contentW = 800f, contentH = 480f,
            points = listOf(op, partner),
        )
        // Both endpoints project on-screen (0..w, 0..h) and are zoomed in (>1x).
        assertThat(p.zoomLevel).isGreaterThan(1f)
        for ((lat, lon) in listOf(op, partner)) {
            assertThat(p.x(lon.toFloat())).isAtLeast(0f)
            assertThat(p.x(lon.toFloat())).isAtMost(w.toFloat())
            assertThat(p.y(lat.toFloat())).isAtLeast(0f)
            assertThat(p.y(lat.toFloat())).isAtMost(h.toFloat())
        }
    }

    @Test
    fun fit_clampsZoomForCoincidentPoints() {
        val same = 45.0 to 10.0
        val p = CarMapProjection.fit(
            width = w, height = h,
            cx = 400f, cy = 240f, contentW = 800f, contentH = 480f,
            points = listOf(same, same),
        )
        // Two stations in the same spot must not zoom to infinity.
        assertThat(p.zoomLevel).isEqualTo(CarMapProjection.MAX_ZOOM)
        assertThat(p.x(same.second.toFloat())).isWithin(TOL).of(400f)
        assertThat(p.y(same.first.toFloat())).isWithin(TOL).of(240f)
    }

    @Test
    fun fit_neverZoomsOutPastTheWorldView() {
        // Opposite corners of the globe — the span already exceeds the surface.
        val p = CarMapProjection.fit(
            width = w, height = h,
            cx = 400f, cy = 240f, contentW = 800f, contentH = 480f,
            points = listOf(-80.0 to -170.0, 80.0 to 170.0),
        )
        assertThat(p.zoomLevel).isEqualTo(1f)
    }

    @Test
    fun fit_fallsBackToWorldForDegenerateInputs() {
        val world = CarMapProjection.world(w, h)
        val emptyPoints = CarMapProjection.fit(w, h, 400f, 240f, 800f, 480f, emptyList())
        val zeroContent = CarMapProjection.fit(w, h, 400f, 240f, 0f, 0f, listOf(40.0 to -105.0))
        assertThat(emptyPoints.signature).isEqualTo(world.signature)
        assertThat(zeroContent.signature).isEqualTo(world.signature)
    }

    private companion object {
        const val TOL = 0.01f
    }
}
