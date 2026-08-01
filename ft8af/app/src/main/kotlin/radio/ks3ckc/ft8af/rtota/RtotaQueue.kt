package radio.ks3ckc.ft8af.rtota

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One batch handed to the uploader, plus how much of the queue it covers. */
data class RtotaBatch(val points: List<TripPoint>, val qsos: List<TripQso>) {
    val isEmpty: Boolean get() = points.isEmpty() && qsos.isEmpty()
    val size: Int get() = points.size + qsos.size
}

/**
 * The outbox for trip mode: breadcrumbs and contacts wait here until they reach
 * rtota.app, and survive a process death while they wait.
 *
 * Roving means hours out of coverage. Everything the app records goes into this
 * queue first and is only dropped once the server has acknowledged it, so a
 * canyon, a dead battery, or an OS-initiated kill costs nothing but time. The
 * live endpoint dedupes QSOs by `callsign + band + mode + UTC minute`, so a
 * re-send after an ambiguous failure is always safe.
 *
 * Writes go straight to disk on every add. That is more I/O than strictly
 * needed, but a breadcrumb the app "has" only in RAM is a breadcrumb it loses in
 * exactly the situation this queue exists for. The file is small (a full day of
 * driving at the default cadence is a few hundred KB).
 *
 * [file] may be null in tests that only exercise the in-memory behaviour.
 */
class RtotaQueue(private val file: File?) {
    private val points = ArrayList<TripPoint>()
    private val qsos = ArrayList<TripQso>()

    /**
     * Caps. Points are the disposable half: at the default cadence 20,000 of
     * them is roughly a week of continuous driving, and past that the oldest go
     * first so the queue can't grow without bound on a phone that never regains
     * signal. QSOs get a cap too, but a far more generous one — a contact is the
     * point of the whole exercise, and the end-of-trip ADIF upload is the only
     * other copy.
     */
    companion object {
        const val MAX_POINTS = 20_000
        const val MAX_QSOS = 20_000

        /** Batch size per request — keeps a single POST modest over 1 bar of LTE. */
        const val BATCH_POINTS = 250
        const val BATCH_QSOS = 250
    }

    @Synchronized
    fun addPoint(point: TripPoint) {
        points.add(point)
        trim()
        persist()
    }

    @Synchronized
    fun addQso(qso: TripQso) {
        qsos.add(qso)
        trim()
        persist()
    }

    @Synchronized
    fun pointCount(): Int = points.size

    @Synchronized
    fun qsoCount(): Int = qsos.size

    @Synchronized
    fun isEmpty(): Boolean = points.isEmpty() && qsos.isEmpty()

    /** The next chunk to upload, oldest first. Does not remove anything. */
    @Synchronized
    fun peekBatch(
        maxPoints: Int = BATCH_POINTS,
        maxQsos: Int = BATCH_QSOS,
    ): RtotaBatch =
        RtotaBatch(
            points = points.take(maxPoints),
            qsos = qsos.take(maxQsos),
        )

    /**
     * Drop a batch the server accepted. Counts, not identities: the queue is
     * append-only at the tail and only ever drained from the head, so removing
     * the first N is exactly what was sent — while an add that raced in during
     * the upload sits safely behind it.
     */
    @Synchronized
    fun commit(batch: RtotaBatch) {
        repeat(minOf(batch.points.size, points.size)) { points.removeAt(0) }
        repeat(minOf(batch.qsos.size, qsos.size)) { qsos.removeAt(0) }
        persist()
    }

    /**
     * Drop queued contacts the server has already stored, identified by the
     * dedupe keys a sync-state handshake returned. Returns how many were removed.
     *
     * Only ever called with keys the server itself reported, and matched on the
     * exact format it stores (see [dedupeKey]) — a near-miss re-sends a contact
     * that then dedupes server-side, which costs a few bytes, while a false match
     * would silently discard a QSO that never arrived. Points are left alone:
     * they carry no key, and re-sending them is already idempotent on
     * (trip, timestamp).
     */
    @Synchronized
    fun pruneAcknowledgedQsos(serverKeys: Set<String>): Int {
        if (serverKeys.isEmpty() || qsos.isEmpty()) return 0
        val before = qsos.size
        qsos.retainAll { it.dedupeKey() !in serverKeys }
        val removed = before - qsos.size
        if (removed > 0) persist()
        return removed
    }

    @Synchronized
    fun clear() {
        points.clear()
        qsos.clear()
        persist()
    }

    /** Load a previously persisted queue. Silently starts empty on a bad file. */
    @Synchronized
    fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            points.clear()
            qsos.clear()
            root.optJSONArray("points")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { tripPointFromJson(it) }?.let { points.add(it) }
                }
            }
            root.optJSONArray("qsos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { tripQsoFromJson(it) }?.let { qsos.add(it) }
                }
            }
            trim()
        } catch (_: Exception) {
            // A half-written file (killed mid-persist) is worth less than a clean
            // start; the ADIF upload at the end of the trip is the safety net.
            points.clear()
            qsos.clear()
        }
    }

    private fun trim() {
        while (points.size > MAX_POINTS) points.removeAt(0)
        while (qsos.size > MAX_QSOS) qsos.removeAt(0)
    }

    private fun persist() {
        val f = file ?: return
        try {
            val root =
                JSONObject().apply {
                    put("points", JSONArray().apply { points.forEach { put(it.toStoredJson()) } })
                    put("qsos", JSONArray().apply { qsos.forEach { put(it.toStoredJson()) } })
                }
            // Write-then-rename: a kill mid-write leaves the previous good file in
            // place instead of a truncated one.
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(root.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
            // Out of space / no permission — keep going in memory rather than
            // taking down the trip.
        }
    }
}
