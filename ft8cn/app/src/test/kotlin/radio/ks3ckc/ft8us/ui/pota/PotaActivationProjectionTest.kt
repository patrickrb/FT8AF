package radio.ks3ckc.ft8us.ui.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8us.ui.decode.QSO_MAP_MIN_SPAN_DEG
import radio.ks3ckc.ft8us.ui.decode.QSO_MAP_PAD_FRAC
import radio.ks3ckc.ft8us.ui.decode.QsoPathProjection

/**
 * Unit tests for [PotaActivationProjection], the pure N-point equirectangular
 * framing math behind the POTA activation contacts map. No Android types, so
 * these run as plain JUnit (no Robolectric).
 *
 * A landscape canvas (400 x 160 px) mirrors the inset's aspect; with a 160px
 * height the latitude axis binds the minimum-span cases.
 */
class PotaActivationProjectionTest {

    private val w = 400f
    private val h = 160f
    private val tol = 0.5f // px

    @Test
    fun `single contact frames identically to the two-point QSO projection`() {
        // Operator in Kansas (~40,-98), one contact in Japan (~35,138). With a
        // single contact the framing must match QsoPathProjection exactly.
        val pota = PotaActivationProjection(40.0, -98.0, listOf(Pair(35.0, 138.0)), w, h)
        val qso = QsoPathProjection(40.0, -98.0, 35.0, 138.0, w, h)

        assertThat(pota.normalizedContactLons[0]).isWithin(1e-9).of(qso.normalizedTheirLon)
        assertThat(pota.scale).isWithin(1e-6).of(qso.scale)
        assertThat(pota.projectX(-98.0)).isWithin(tol).of(qso.projectX(-98.0))
        assertThat(pota.projectY(40.0)).isWithin(tol).of(qso.projectY(40.0))
    }

    @Test
    fun `every operator and contact point lands inside the canvas`() {
        // Operator in Kansas with contacts spread across Europe, Japan and
        // South America — after normalization all must be on-canvas.
        val contacts = listOf(
            Pair(51.5, 0.0),     // London
            Pair(35.0, 140.0),   // Tokyo (trans-Pacific short path)
            Pair(-34.0, -58.0),  // Buenos Aires
        )
        val p = PotaActivationProjection(38.0, -98.0, contacts, w, h)

        val xs = buildList {
            add(p.projectX(-98.0))
            contacts.indices.forEach { i -> add(p.projectX(p.normalizedContactLons[i])) }
        }
        val ys = buildList {
            add(p.projectY(38.0))
            contacts.forEach { add(p.projectY(it.first)) }
        }
        for (x in xs) {
            assertThat(x).isAtLeast(0f)
            assertThat(x).isAtMost(w)
        }
        for (y in ys) {
            assertThat(y).isAtLeast(0f)
            assertThat(y).isAtMost(h)
        }
    }

    @Test
    fun `trans-Pacific contact uses the short-path normalized longitude`() {
        val p = PotaActivationProjection(38.0, -98.0, listOf(Pair(35.0, 140.0)), w, h)
        // 140E from a -98 operator is reached the short way as -220 (across the
        // Pacific), not the long way east.
        assertThat(p.normalizedContactLons[0]).isWithin(1e-9).of(-220.0)
    }

    @Test
    fun `clustered contacts are clamped to the minimum span`() {
        // Operator plus two contacts all within ~10 km would otherwise zoom to
        // street level; the 25-degree floor (with padding) caps the scale.
        val contacts = listOf(Pair(40.05, -75.05), Pair(40.1, -75.1))
        val p = PotaActivationProjection(40.0, -75.0, contacts, w, h)
        val padFactor = 1.0 + 2.0 * QSO_MAP_PAD_FRAC
        val expectedScale = h / (QSO_MAP_MIN_SPAN_DEG * padFactor)
        assertThat(p.scale).isWithin(1e-6).of(expectedScale)
    }

    @Test
    fun `bounding box midpoint projects to the canvas center`() {
        // Operator (40,-100) with contacts framing a box whose center is
        // (45,-80): lat midpoint of [40,50] and lon midpoint of [-100,-60].
        val contacts = listOf(Pair(50.0, -60.0), Pair(45.0, -80.0))
        val p = PotaActivationProjection(40.0, -100.0, contacts, w, h)
        assertThat(p.projectX(-80.0)).isWithin(tol).of(w / 2f)
        assertThat(p.projectY(45.0)).isWithin(tol).of(h / 2f)
    }
}
