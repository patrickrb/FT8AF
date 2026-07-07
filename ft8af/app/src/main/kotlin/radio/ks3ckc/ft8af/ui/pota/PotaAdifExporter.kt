package radio.ks3ckc.ft8af.ui.pota

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.FileProvider
import com.k1af.ft8af.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import radio.ks3ckc.ft8af.pota.PotaQsoWindow
import radio.ks3ckc.ft8af.pota.model.PotaActivation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes ADIF files for a POTA activation and shares them via system intent.
 *
 * pota.app's website upload (https://pota.app/#/user/upload) expects an ADIF where each
 * QSO carries MY_SIG=POTA / MY_SIG_INFO=<park ref>. For multi-park activations (two-fers,
 * three-fers, etc.) we generate one ADIF file per park — each containing the same QSOs but
 * with MY_SIG_INFO set to that single park — and share all files in one intent.
 *
 * [buildActivationAdif] is the shared core: both the share-sheet path here and the
 * authenticated in-app upload (PotaClient.uploadAdif) generate identical bytes from it.
 */
object PotaAdifExporter {

    private const val AUTHORITY = "radio.ks3ckc.ft8af.fileprovider"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One park's ADIF document plus the filename it should be uploaded/shared under. */
    data class NamedAdif(val parkRef: String, val filename: String, val content: String)

    /**
     * Build one ADIF document per park reference for [activation]. Each document
     * contains the same QSO rows with MY_SIG_INFO pinned to that single park, so a
     * two-fer produces two uploads. Runs the DB read on the calling thread — call
     * from a background dispatcher.
     */
    fun buildActivationAdif(db: SQLiteDatabase, activation: PotaActivation): List<NamedAdif> {
        // The DB stores the full comma-separated park string in my_sig_info, so we
        // match on that, then split into per-park files below.
        //
        // Scope to the activation's time window (via the shared PotaQsoWindow, the
        // same logic PotaActivationDao.getActivationQsos uses) so repeat
        // activations at the same park don't all get lumped into one export.
        // PotaQsoWindow.ROW_STAMP normalizes the variable-width time_on to a
        // 14-char yyyyMMddHHmmss stamp so the comparison holds even for imported
        // rows with HHMM / dropped-leading-zero times. An open (still active)
        // activation has no end, so it uses a far-future upper bound.
        val startStamp = PotaQsoWindow.stamp(activation.startedAtMs)
        val endStamp = activation.endedAtMs?.let { PotaQsoWindow.stamp(it) } ?: PotaQsoWindow.OPEN_END
        val cursor = db.rawQuery(
            "SELECT * FROM QSLTable WHERE my_sig = 'POTA' AND my_sig_info = ? " +
                "AND ${PotaQsoWindow.ROW_STAMP} >= ? AND ${PotaQsoWindow.ROW_STAMP} <= ? " +
                "ORDER BY qso_date, time_on",
            arrayOf(activation.parkRef, startStamp, endStamp),
        )

        data class QsoRow(
            val call: String?, val grid: String?, val mode: String?,
            val band: String?, val freq: String?, val rstSent: String?,
            val rstRcvd: String?, val date: String?, val timeOn: String?,
            val timeOff: String?, val station: String?, val myGrid: String?,
            val mySig: String?, val sig: String?, val sigInfo: String?,
        )

        val rows = mutableListOf<QsoRow>()
        cursor.use { c ->
            val callIdx = c.getColumnIndex("call")
            val gridIdx = c.getColumnIndex("gridsquare")
            val modeIdx = c.getColumnIndex("mode")
            val bandIdx = c.getColumnIndex("band")
            val freqIdx = c.getColumnIndex("freq")
            val rstSentIdx = c.getColumnIndex("rst_sent")
            val rstRcvdIdx = c.getColumnIndex("rst_rcvd")
            val dateIdx = c.getColumnIndex("qso_date")
            val timeOnIdx = c.getColumnIndex("time_on")
            val timeOffIdx = c.getColumnIndex("time_off")
            val stationIdx = c.getColumnIndex("station_callsign")
            val myGridIdx = c.getColumnIndex("my_gridsquare")
            val mySigIdx = c.getColumnIndex("my_sig")
            val sigIdx = c.getColumnIndex("sig")
            val sigInfoIdx = c.getColumnIndex("sig_info")
            while (c.moveToNext()) {
                rows.add(
                    QsoRow(
                        call = c.getString(callIdx), grid = c.getString(gridIdx),
                        mode = c.getString(modeIdx), band = c.getString(bandIdx),
                        freq = c.getString(freqIdx), rstSent = c.getString(rstSentIdx),
                        rstRcvd = c.getString(rstRcvdIdx), date = c.getString(dateIdx),
                        timeOn = c.getString(timeOnIdx), timeOff = c.getString(timeOffIdx),
                        station = c.getString(stationIdx), myGrid = c.getString(myGridIdx),
                        mySig = c.getString(mySigIdx), sig = c.getString(sigIdx),
                        sigInfo = c.getString(sigInfoIdx),
                    ),
                )
            }
        }

        // No matching QSO rows → no documents. Without this we'd emit header-only
        // ADIF files (one per park), which the upload path would happily POST and the
        // share path would attach as empty logs. Callers treat an empty list as
        // "nothing to upload/share".
        if (rows.isEmpty()) return emptyList()

        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(activation.startedAtMs))
        return activation.parkRefs.map { parkRef ->
            val sb = StringBuilder()
            sb.append("FT8AF POTA Activation $parkRef\n")
            sb.append("<ADIF_VER:5>3.1.4 ")
            sb.append("<PROGRAMID:5>FT8AF ")
            sb.append("<EOH>\n")
            for (r in rows) {
                adifField(sb, "CALL", r.call)
                adifField(sb, "GRIDSQUARE", r.grid)
                adifField(sb, "MODE", r.mode)
                adifField(sb, "BAND", r.band)
                adifField(sb, "FREQ", r.freq)
                adifField(sb, "RST_SENT", r.rstSent)
                adifField(sb, "RST_RCVD", r.rstRcvd)
                adifField(sb, "QSO_DATE", r.date)
                adifField(sb, "TIME_ON", r.timeOn)
                adifField(sb, "TIME_OFF", r.timeOff)
                adifField(sb, "STATION_CALLSIGN", r.station)
                adifField(sb, "MY_GRIDSQUARE", r.myGrid)
                adifField(sb, "MY_SIG", r.mySig)
                // Override MY_SIG_INFO to this single park (not the comma-separated value from DB).
                adifField(sb, "MY_SIG_INFO", parkRef)
                adifField(sb, "SIG", r.sig)
                adifField(sb, "SIG_INFO", r.sigInfo)
                sb.append("<EOR>\n")
            }
            NamedAdif(parkRef = parkRef, filename = "pota-$parkRef-$ts.adi", content = sb.toString())
        }
    }

    fun shareActivationAdif(
        context: Context,
        mainViewModel: MainViewModel,
        activation: PotaActivation,
        onResult: (Boolean) -> Unit,
    ) {
        val db = mainViewModel.databaseOpr?.db ?: run {
            onResult(false)
            return
        }
        scope.launch {
            try {
                val docs = buildActivationAdif(db, activation)
                if (docs.isEmpty()) {
                    // No QSOs matched this activation — nothing to share.
                    onResult(false)
                    return@launch
                }
                val dir = context.getExternalFilesDir(null) ?: run {
                    onResult(false)
                    return@launch
                }
                val parks = activation.parkRefs

                val files = docs.map { doc ->
                    File(dir, doc.filename).apply { writeText(doc.content) }
                }

                val uris = ArrayList(
                    files.map { FileProvider.getUriForFile(context, AUTHORITY, it) },
                )

                val send = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uris[0])
                        putExtra(Intent.EXTRA_SUBJECT, "POTA activation ${parks.first()}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "text/plain"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        putExtra(Intent.EXTRA_SUBJECT, "POTA activation ${activation.parkRefsDisplay}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                val chooser = Intent.createChooser(send, "Share POTA ADIF").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                onResult(true)
            } catch (e: Exception) {
                e.printStackTrace()
                onResult(false)
            }
        }
    }

    // internal + @VisibleForTesting so the byte-length ADIF encoding (the
    // bug-prone part) can be unit-tested directly; not part of the public API.
    @androidx.annotation.VisibleForTesting
    internal fun adifField(sb: StringBuilder, name: String, value: String?) {
        if (value.isNullOrEmpty()) return
        // ADIF length is in bytes, not characters — `value.length` (UTF-16 code units)
        // would mis-tag any non-ASCII content and misalign the following field.
        val bytes = value.toByteArray(Charsets.UTF_8).size
        sb.append("<").append(name).append(":").append(bytes).append(">").append(value).append(" ")
    }
}
