package radio.ks3ckc.ft8af.ui.decode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [QsoPathProjection], the pure equirectangular framing math
 * behind the QSO panel path map. No Android types are involved, so these run as
 * plain JUnit (no Robolectric).
 *
 * A landscape canvas (400 x 160 px) is used throughout to mirror the inset's
 * 2.5:1-ish aspect; with a 160px-tall canvas the latitude axis is the binding
 * constraint for the minimum-span cases.
 */
class QsoPathProjectionTest {

    private val w = 400f
    private val h = 160f
    private val tol = 0.5f // px

    // ---------- normalizeLon ----------

    @Test
    fun `normalizeLon shifts an eastern lon west when that is the short way`() {
        // US operator (~-98) to Japan (~138): the short path crosses the
        // Pacific, so 138 becomes -222.
        assertThat(QsoPathProjection.normalizeLon(-98.0, 138.0)).isWithin(1e-9).of(-222.0)
    }

    @Test
    fun `normalizeLon shifts a western lon east across the antemeridian`() {
        // Operator just west of the dateline (170) to a station just east
        // (-170): short way is +190, not the long way back through 0.
        assertThat(QsoPathProjection.normalizeLon(170.0, -170.0)).isWithin(1e-9).of(190.0)
    }

    @Test
    fun `normalizeLon leaves nearby longitudes unchanged`() {
        assertThat(QsoPathProjection.normalizeLon(-71.0, 0.0)).isWithin(1e-9).of(0.0)
        assertThat(QsoPathProjection.normalizeLon(10.0, -10.0)).isWithin(1e-9).of(-10.0)
    }

    @Test
    fun `projection exposes the normalized remote longitude`() {
        val p = QsoPathProjection(40.0, -98.0, 35.0, 138.0, w, h)
        assertThat(p.normalizedTheirLon).isWithin(1e-9).of(-222.0)
    }

    // ---------- centering ----------

    @Test
    fun `endpoint midpoint projects to the canvas center`() {
        // Boston FN42 (~42.5,-71) to London IO91 (~51.5,0).
        val p = QsoPathProjection(42.5, -71.0, 51.5, 0.0, w, h)
        val midLon = (-71.0 + 0.0) / 2.0
        val midLat = (42.5 + 51.5) / 2.0
        assertThat(p.projectX(midLon)).isWithin(tol).of(w / 2f)
        assertThat(p.projectY(midLat)).isWithin(tol).of(h / 2f)
    }

    @Test
    fun `eastern station projects to the right of a western one`() {
        val p = QsoPathProjection(42.5, -71.0, 51.5, 0.0, w, h)
        assertThat(p.projectX(p.normalizedTheirLon)).isGreaterThan(p.projectX(-71.0))
    }

    @Test
    fun `higher latitude projects higher on screen (smaller y)`() {
        val p = QsoPathProjection(42.5, -71.0, 51.5, 0.0, w, h)
        // North is up: the 51.5N station has a smaller y than the 42.5N one.
        assertThat(p.projectY(51.5)).isLessThan(p.projectY(42.5))
    }

    // ---------- minimum span / scale ----------

    @Test
    fun `nearby stations are clamped to the minimum span`() {
        // Two points ~30 km apart would otherwise zoom to street level; the
        // 25-degree floor (with padding) caps the scale instead.
        val p = QsoPathProjection(40.0, -75.0, 40.1, -75.1, w, h)
        val padFactor = 1.0 + 2.0 * QSO_MAP_PAD_FRAC
        val expectedSpan = QSO_MAP_MIN_SPAN_DEG * padFactor
        // Height (160px) is the binding axis for a span this small.
        val expectedScale = h / expectedSpan
        assertThat(p.scale).isWithin(1e-6).of(expectedScale)
    }

    @Test
    fun `both endpoints fall inside the canvas for a trans-Pacific path`() {
        // Kansas (~38,-98) to Tokyo (~35,140): after normalization both ends
        // must land within the visible canvas, not off either edge.
        val p = QsoPathProjection(38.0, -98.0, 35.0, 140.0, w, h)
        val myX = p.projectX(-98.0)
        val themX = p.projectX(p.normalizedTheirLon)
        assertThat(myX).isAtLeast(0f)
        assertThat(myX).isAtMost(w)
        assertThat(themX).isAtLeast(0f)
        assertThat(themX).isAtMost(w)
    }

    @Test
    fun `wide span uses a uniform scale that fits both axes`() {
        val p = QsoPathProjection(42.5, -71.0, 51.5, 0.0, w, h)
        // Scale never exceeds either axis budget (with the padded spans).
        val padFactor = 1.0 + 2.0 * QSO_MAP_PAD_FRAC
        val spanLon = (0.0 - -71.0) * padFactor
        val spanLat = (51.5 - 42.5) * padFactor
        val maxAllowed = minOf(w / spanLon, h / spanLat)
        assertThat(p.scale).isWithin(1e-6).of(maxAllowed)
    }
}
