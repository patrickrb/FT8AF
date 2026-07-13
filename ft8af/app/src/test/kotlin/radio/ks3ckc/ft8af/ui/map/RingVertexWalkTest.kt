package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [forEachRingVertex] — the shared, bounded walk over a flattened polygon ring
 * (`[lon0, lat0, lon1, lat1, ...]`) used by the equirectangular / azimuthal /
 * POTA / QSO-path land renderers.
 *
 * The pre-fix draw loops walked `while (i < ring.size) { ...ring[i + 1]...; i += 2 }`,
 * which reads one element past the end of an **odd-length** ring →
 * ArrayIndexOutOfBoundsException on the Compose draw thread. These cases prove
 * the walk is now bounded to `ring.size / 2` complete vertices, byte-identical
 * to the old behavior for every even-length ring.
 */
class RingVertexWalkTest {

    private data class Vertex(val lon: Float, val lat: Float, val first: Boolean)

    private fun walk(ring: FloatArray, minVertices: Int = 3): Pair<Boolean, List<Vertex>> {
        val out = ArrayList<Vertex>()
        val plotted = forEachRingVertex(ring, minVertices) { lon, lat, first ->
            out.add(Vertex(lon, lat, first))
        }
        return plotted to out
    }

    @Test
    fun `even-length ring plots every vertex, first flag on the opener`() {
        // Triangle: 3 complete (lon,lat) vertices.
        val (plotted, verts) = walk(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        assertThat(plotted).isTrue()
        assertThat(verts).containsExactly(
            Vertex(1f, 2f, true),
            Vertex(3f, 4f, false),
            Vertex(5f, 6f, false),
        ).inOrder()
    }

    @Test
    fun `odd-length ring ignores the trailing unpaired coordinate without overrun`() {
        // 7 floats = 3 complete vertices + a stray 7f. The old walk read ring[6]
        // then ring[7] (out of bounds); the bounded walk stops after vertex 3.
        val (plotted, verts) = walk(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f))
        assertThat(plotted).isTrue()
        assertThat(verts).containsExactly(
            Vertex(1f, 2f, true),
            Vertex(3f, 4f, false),
            Vertex(5f, 6f, false),
        ).inOrder()
    }

    @Test
    fun `larger odd-length ring plots all complete pairs and drops the stray`() {
        // 9 floats = 4 complete vertices + a stray 9f.
        val (plotted, verts) = walk(floatArrayOf(0f, 0f, 1f, 1f, 2f, 2f, 3f, 3f, 9f))
        assertThat(plotted).isTrue()
        assertThat(verts.map { it.lon }).containsExactly(0f, 1f, 2f, 3f).inOrder()
    }

    @Test
    fun `ring with fewer than three complete vertices is skipped entirely`() {
        // 4 floats = 2 vertices (< 3): matches the old `if (ring.size < 6) continue`.
        val (plotted, verts) = walk(floatArrayOf(1f, 2f, 3f, 4f))
        assertThat(plotted).isFalse()
        assertThat(verts).isEmpty()
    }

    @Test
    fun `five-float ring (two complete vertices) is skipped like an under-length even ring`() {
        val (plotted, verts) = walk(floatArrayOf(1f, 2f, 3f, 4f, 5f))
        assertThat(plotted).isFalse()
        assertThat(verts).isEmpty()
    }

    @Test
    fun `empty ring is skipped`() {
        val (plotted, verts) = walk(floatArrayOf())
        assertThat(plotted).isFalse()
        assertThat(verts).isEmpty()
    }

    @Test
    fun `the pre-fix unbounded walk really did overrun an odd-length ring`() {
        // Guards against anyone reverting the bound: this reproduces the old
        // `while (i < ring.size)` walk and asserts it throws on odd input, while
        // forEachRingVertex over the same ring does not.
        val ring = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f)
        var threw = false
        try {
            var i = 0
            var sink = 0f
            while (i < ring.size) {
                sink += ring[i] + ring[i + 1]
                i += 2
            }
            assertThat(sink).isNotNaN() // keep `sink` live
        } catch (e: IndexOutOfBoundsException) {
            threw = true
        }
        assertThat(threw).isTrue()
        // The bounded walk over the identical ring does not throw.
        assertThat(walk(ring).first).isTrue()
    }
}
