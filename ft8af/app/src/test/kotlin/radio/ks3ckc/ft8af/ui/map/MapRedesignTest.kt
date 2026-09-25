package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * Pure unit tests for the Map redesign (concept 4c) logic: layer-chip
 * visibility, state-border zoom gating, the gray-line info pill countdown, and
 * the "Me" recenter pan. No Android/Compose runtime is touched.
 */
class MapRedesignTest {

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    // ---- layer chip visibility ------------------------------------------------

    @Test
    fun decodesChip_showsOnlyNonWorked() {
        assertThat(isStationVisible(isWorked = false, decodesOn = true, workedOn = false)).isTrue()
        assertThat(isStationVisible(isWorked = true, decodesOn = true, workedOn = false)).isFalse()
    }

    @Test
    fun workedChip_showsOnlyWorked() {
        assertThat(isStationVisible(isWorked = true, decodesOn = false, workedOn = true)).isTrue()
        assertThat(isStationVisible(isWorked = false, decodesOn = false, workedOn = true)).isFalse()
    }

    @Test
    fun bothChipsOff_hidesEverything() {
        assertThat(isStationVisible(isWorked = false, decodesOn = false, workedOn = false)).isFalse()
        assertThat(isStationVisible(isWorked = true, decodesOn = false, workedOn = false)).isFalse()
    }

    // ---- state-border zoom gate ----------------------------------------------

    @Test
    fun stateBorders_hiddenUntilZoomThreshold() {
        assertThat(showStateBorders(1f)).isFalse()
        assertThat(showStateBorders(STATE_BORDER_ZOOM - 0.01f)).isFalse()
        assertThat(showStateBorders(STATE_BORDER_ZOOM)).isTrue()
        assertThat(showStateBorders(8f)).isTrue()
    }

    // ---- gray-line countdown formatting --------------------------------------

    @Test
    fun formatHoursMinutes_rendersHoursAndMinutes() {
        assertThat(formatHoursMinutes(72)).isEqualTo("1h 12m")
        assertThat(formatHoursMinutes(45)).isEqualTo("45m")
        assertThat(formatHoursMinutes(120)).isEqualTo("2h 0m")
        assertThat(formatHoursMinutes(0)).isEqualTo("0m")
    }

    @Test
    fun minutesToGrayLine_findsNextTerminatorCrossing() {
        // Boulder, CO, mid-afternoon local: sunset comes within 24 h and the
        // day/night state must actually flip at the reported minute.
        val lat = 40.0
        val lon = -105.0
        val now = utc(2024, 5, 21, 21, 0) // ~15:00 MDT
        val mins = minutesToGrayLine(lat, lon, now)
        assertThat(mins).isNotNull()
        assertThat(mins!!).isIn(1L..1440L)

        val before = isNight(lat, lon, subsolarPoint(now))
        val at = isNight(lat, lon, subsolarPoint(now + mins * 60_000L))
        assertThat(at).isNotEqualTo(before)
    }

    // ---- "Me" recenter pan ---------------------------------------------------

    @Test
    fun panToCenter_bringsOperatorToCanvasCentre() {
        // A zoomed viewport with room to pan on both axes (no clamp at the edge).
        val vp = EquirectViewport(canvasW = 1000f, canvasH = 1000f, userScale = 4f)
        val off = vp.panToCenter(lat = 45.0, lon = 45.0)
        val centered = EquirectViewport(1000f, 1000f, 4f, off.x, off.y)
        val p = centered.projectLatLon(45.0, 45.0)
        assertThat(abs(p.x - 500f)).isLessThan(0.5f)
        assertThat(abs(p.y - 500f)).isLessThan(0.5f)
    }

    // ---- fit-to-markers framing --------------------------------------------

    @Test
    fun fitEquirectView_nullWhenNothingToFrame() {
        assertThat(fitEquirectView(emptyList(), 1000f, 2000f, 1f, 8f)).isNull()
        assertThat(fitEquirectView(listOf(40.0 to -100.0), 0f, 0f, 1f, 8f)).isNull()
    }

    @Test
    fun fitEquirectView_zoomsIntoAClusteredRegion() {
        // A tight US cluster should zoom well past 1x and frame that region.
        val pts = listOf(40.0 to -105.0, 41.0 to -104.0, 39.0 to -106.0)
        val fit = fitEquirectView(pts, 1200f, 1920f, 1f, 8f)
        assertThat(fit).isNotNull()
        assertThat(fit!!.scale).isGreaterThan(1.5f)
        assertThat(fit.scale).isAtMost(8f)

        // The cluster's centre projects near the canvas centre after the fit.
        val vp = EquirectViewport(1200f, 1920f, fit.scale, fit.panX, fit.panY)
        val c = vp.projectLatLon(40.0, -105.0)
        assertThat(abs(c.x - 600f)).isLessThan(140f)
        assertThat(abs(c.y - 960f)).isLessThan(220f)
    }

    @Test
    fun fitEquirectView_globalSpreadStaysZoomedOut() {
        // Markers on every continent can't be zoomed into — clamp at min zoom.
        val pts = listOf(40.0 to -100.0, 50.0 to 10.0, 36.0 to 140.0, -33.0 to 151.0, -23.0 to -46.0)
        val fit = fitEquirectView(pts, 1200f, 1920f, 1f, 8f)
        assertThat(fit).isNotNull()
        assertThat(fit!!.scale).isWithin(0.01f).of(1f)
    }

    @Test
    fun panToCenter_isClampedWithinBounds() {
        // At zoom 1 on a square canvas the world can't pan off vertically, so the
        // clamp keeps panY within the (small) allowed range rather than blowing out.
        val vp = EquirectViewport(canvasW = 1000f, canvasH = 1000f, userScale = 1f)
        val off = vp.panToCenter(lat = 80.0, lon = 170.0)
        assertThat(abs(off.x)).isAtMost(vp.maxPanX())
        assertThat(abs(off.y)).isAtMost(vp.maxPanY())
    }
}
