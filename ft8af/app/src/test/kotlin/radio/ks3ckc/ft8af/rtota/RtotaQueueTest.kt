package radio.ks3ckc.ft8af.rtota

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The offline outbox. The behaviours that matter are all failure-shaped: a
 * process killed mid-trip must come back with its backlog, and a partial upload
 * must drop exactly what the server took and not a row more.
 */
@RunWith(RobolectricTestRunner::class)
class RtotaQueueTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val base = 1_700_000_000_000L

    private fun point(i: Int) = TripPoint(base + i * 1000L, 39.0 + i * 0.001, -105.0)

    private fun qso(i: Int) = TripQso("K1AF$i", base + i * 1000L, band = "20m", mode = "FT8")

    private fun queueFile(): File = File(temp.newFolder(), "rtota_queue.json")

    @Test
    fun `counts track what was added`() {
        val q = RtotaQueue(queueFile())
        q.addPoint(point(1))
        q.addPoint(point(2))
        q.addQso(qso(1))
        assertThat(q.pointCount()).isEqualTo(2)
        assertThat(q.qsoCount()).isEqualTo(1)
        assertThat(q.isEmpty()).isFalse()
    }

    @Test
    fun `batch takes the oldest first and leaves the queue intact`() {
        val q = RtotaQueue(queueFile())
        repeat(5) { q.addPoint(point(it)) }
        val batch = q.peekBatch(maxPoints = 3, maxQsos = 3)
        assertThat(batch.points).hasSize(3)
        assertThat(batch.points.first()).isEqualTo(point(0))
        assertThat(q.pointCount()).isEqualTo(5)
    }

    @Test
    fun `commit removes exactly the batch and keeps what arrived during the upload`() {
        val q = RtotaQueue(queueFile())
        repeat(3) { q.addPoint(point(it)) }
        val batch = q.peekBatch()
        // A fix that lands while the POST is in flight must survive the commit.
        q.addPoint(point(99))
        q.commit(batch)
        assertThat(q.pointCount()).isEqualTo(1)
        assertThat(q.peekBatch().points.single()).isEqualTo(point(99))
    }

    @Test
    fun `a queue survives a process restart`() {
        val file = queueFile()
        val first = RtotaQueue(file)
        repeat(4) { first.addPoint(point(it)) }
        first.addQso(qso(1))

        val second = RtotaQueue(file).apply { load() }
        assertThat(second.pointCount()).isEqualTo(4)
        assertThat(second.qsoCount()).isEqualTo(1)
        assertThat(second.peekBatch().qsos.single().callsign).isEqualTo("K1AF1")
    }

    @Test
    fun `a truncated queue file starts empty rather than throwing`() {
        val file = queueFile()
        file.writeText("""{"points":[{"t":1,""")
        val q = RtotaQueue(file).apply { load() }
        assertThat(q.isEmpty()).isTrue()
    }

    @Test
    fun `points are capped, dropping the oldest`() {
        val q = RtotaQueue(null)
        repeat(RtotaQueue.MAX_POINTS + 10) { q.addPoint(point(it)) }
        assertThat(q.pointCount()).isEqualTo(RtotaQueue.MAX_POINTS)
        // The head is now the 11th point ever added.
        assertThat(q.peekBatch(maxPoints = 1).points.single()).isEqualTo(point(10))
    }

    @Test
    fun `clear empties both halves`() {
        val q = RtotaQueue(queueFile())
        q.addPoint(point(1))
        q.addQso(qso(1))
        q.clear()
        assertThat(q.isEmpty()).isTrue()
    }

    @Test
    fun `committing more than the queue holds does not underflow`() {
        val q = RtotaQueue(null)
        q.addPoint(point(1))
        val oversized = RtotaBatch(listOf(point(1), point(2), point(3)), emptyList())
        q.commit(oversized)
        assertThat(q.isEmpty()).isTrue()
    }
}
