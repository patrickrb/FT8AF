package radio.ks3ckc.ft8us.pota

import android.database.sqlite.SQLiteDatabase
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.database.DatabaseOpr
import radio.ks3ckc.ft8us.pota.model.PotaActivation
import radio.ks3ckc.ft8us.pota.model.PotaQso
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thin DAO over the pota_activation SQLite table. Single-threaded callers from the
 * session manager — no locking beyond what SQLite gives us.
 */
internal object PotaActivationDao {

    private fun db(): SQLiteDatabase =
        DatabaseOpr.getInstance(GeneralVariables.getMainContext(), null).db

    fun startActivation(parkRef: String, operator: String?, notes: String?): PotaActivation {
        val now = System.currentTimeMillis()
        val db = db()
        db.execSQL(
            "INSERT INTO pota_activation(park_ref, operator, started_at, qso_count, notes) " +
                "VALUES (?, ?, ?, 0, ?)",
            arrayOf<Any?>(parkRef, operator, now, notes),
        )
        val cursor = db.rawQuery("SELECT last_insert_rowid()", null)
        val id = if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        cursor.close()
        return PotaActivation(
            id = id,
            parkRef = parkRef,
            operator = operator,
            startedAtMs = now,
            endedAtMs = null,
            qsoCount = 0,
            notes = notes,
        )
    }

    fun endActivation(id: Long) {
        db().execSQL(
            "UPDATE pota_activation SET ended_at = ? WHERE id = ?",
            arrayOf<Any?>(System.currentTimeMillis(), id),
        )
    }

    /** Refresh a single row (used to pick up the qso_count bumps DatabaseOpr writes). */
    fun reload(id: Long): PotaActivation? {
        val cursor = db().rawQuery("SELECT * FROM pota_activation WHERE id = ?", arrayOf(id.toString()))
        return cursor.use { c -> if (c.moveToFirst()) c.toActivation() else null }
    }

    fun findActiveActivation(): PotaActivation? {
        val cursor = db().rawQuery(
            "SELECT * FROM pota_activation WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1",
            null,
        )
        return cursor.use { c -> if (c.moveToFirst()) c.toActivation() else null }
    }

    fun history(limit: Int = 50): List<PotaActivation> {
        val cursor = db().rawQuery(
            "SELECT * FROM pota_activation ORDER BY started_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        )
        val out = mutableListOf<PotaActivation>()
        cursor.use { c ->
            while (c.moveToNext()) out.add(c.toActivation())
        }
        return out
    }

    /**
     * Return QSOs belonging to [activation] by matching my_sig = 'POTA' and
     * my_sig_info = parkRef, filtered to the activation's time window so repeat
     * activations at the same park don't bleed into each other.
     */
    fun getActivationQsos(activation: PotaActivation): List<PotaQso> {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        val startStamp = fmt.format(Date(activation.startedAtMs))
        val endStamp = activation.endedAtMs?.let { fmt.format(Date(it)) } ?: "99991231235959"
        val cursor = db().rawQuery(
            """
            SELECT id, call, gridsquare, band, mode, rst_sent, rst_rcvd,
                   qso_date, time_on, sig, sig_info
              FROM QSLTable
             WHERE my_sig = 'POTA' AND my_sig_info = ?
               AND (qso_date || time_on) >= ?
               AND (qso_date || time_on) <= ?
             ORDER BY qso_date DESC, time_on DESC
            """.trimIndent(),
            arrayOf(activation.parkRef, startStamp, endStamp),
        )
        val out = mutableListOf<PotaQso>()
        cursor.use { c ->
            while (c.moveToNext()) {
                out.add(
                    PotaQso(
                        id = c.getLong(0),
                        callsign = c.getString(1) ?: "",
                        grid = c.getString(2) ?: "",
                        band = c.getString(3) ?: "",
                        mode = c.getString(4) ?: "",
                        rstSent = c.getString(5) ?: "",
                        rstRcvd = c.getString(6) ?: "",
                        qsoDate = c.getString(7) ?: "",
                        timeOn = c.getString(8) ?: "",
                        sig = c.getString(9),
                        sigInfo = c.getString(10),
                    ),
                )
            }
        }
        return out
    }

    private fun android.database.Cursor.toActivation(): PotaActivation {
        val endedIdx = getColumnIndex("ended_at")
        return PotaActivation(
            id = getLong(getColumnIndex("id")),
            parkRef = getString(getColumnIndex("park_ref")) ?: "",
            operator = getString(getColumnIndex("operator")),
            startedAtMs = getLong(getColumnIndex("started_at")),
            endedAtMs = if (isNull(endedIdx)) null else getLong(endedIdx),
            qsoCount = getInt(getColumnIndex("qso_count")),
            notes = getString(getColumnIndex("notes")),
        )
    }
}
