package radio.ks3ckc.ft8af.ui.pota

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.ft8af.pota.model.PotaActivation
import java.util.TimeZone

/**
 * Coverage for [PotaAdifExporter.buildActivationAdif] — the shared ADIF builder
 * behind both the share-sheet export and the in-app POTA upload. It reads QSO
 * rows from SQLite and emits one document per park, so the test drives a real
 * (Robolectric) in-memory database.
 *
 * The filename embeds a timestamp formatted in the default timezone, so the
 * suite pins the JVM default to UTC to keep the expected name deterministic.
 */
@RunWith(RobolectricTestRunner::class)
class BuildActivationAdifTest {

    private lateinit var db: SQLiteDatabase
    private var savedTz: TimeZone? = null

    // 2024-06-01 12:34:00 UTC — drives the "pota-<park>-20240601-1234.adi" name.
    private val startedAtMs = 1_717_245_240_000L

    @Before
    fun setUp() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        db = SQLiteDatabase.create(null)
        // Only the columns buildActivationAdif reads (it does SELECT * then looks
        // up each by name); my_sig_info is the WHERE key, the rest are emitted.
        db.execSQL(
            """
            CREATE TABLE QSLTable (
                call TEXT, gridsquare TEXT, mode TEXT, band TEXT, freq TEXT,
                rst_sent TEXT, rst_rcvd TEXT, qso_date TEXT, time_on TEXT, time_off TEXT,
                station_callsign TEXT, my_gridsquare TEXT, my_sig TEXT, sig TEXT,
                sig_info TEXT, my_sig_info TEXT
            )
            """.trimIndent(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        savedTz?.let { TimeZone.setDefault(it) }
    }

    /**
     * Insert one POTA QSO. [mySigInfo] is the value stored in the DB's
     * my_sig_info column — the WHERE key, which for a two-fer is the full
     * comma-separated park string. [timeOn] is HHMMSS (how app-logged QSOs
     * store it) so the exporter's `qso_date || time_on` window filter lines up
     * with the 14-char yyyyMMddHHmmss activation stamps.
     */
    private fun insertQso(
        call: String,
        qsoDate: String,
        timeOn: String,
        mySigInfo: String,
        mode: String = "FT8",
    ) {
        db.insert("QSLTable", null, ContentValues().apply {
            put("call", call)
            put("gridsquare", "FN42")
            put("mode", mode)
            put("band", "20m")
            put("freq", "14.074")
            put("rst_sent", "-05")
            put("rst_rcvd", "-10")
            put("qso_date", qsoDate)
            put("time_on", timeOn)
            put("time_off", timeOn)
            put("station_callsign", "K1ABC")
            put("my_gridsquare", "FN31")
            put("my_sig", "POTA")
            put("sig", "")
            put("sig_info", "")
            put("my_sig_info", mySigInfo)
        })
    }

    // One-hour window: [2024-06-01 12:34:00, 13:34:00] UTC. QSOs in the tests
    // below are logged inside this window; the exporter scopes to it.
    private fun activation(parkRef: String) = PotaActivation(
        id = 1L,
        parkRef = parkRef,
        operator = "K1ABC",
        startedAtMs = startedAtMs,
        endedAtMs = startedAtMs + 3_600_000L,
        qsoCount = 0,
        notes = null,
    )

    @Test
    fun singlePark_emitsOneDocumentWithHeaderAndDeterministicName() {
        insertQso("W1AW", "20240601", "123500", mySigInfo = "K-1234")
        insertQso("K9XYZ", "20240601", "123600", mySigInfo = "K-1234")

        val docs = PotaAdifExporter.buildActivationAdif(db, activation("K-1234"))

        assertThat(docs).hasSize(1)
        val doc = docs.single()
        assertThat(doc.parkRef).isEqualTo("K-1234")
        assertThat(doc.filename).isEqualTo("pota-K-1234-20240601-1234.adi")
        assertThat(doc.content).startsWith("FT8AF POTA Activation K-1234\n")
        assertThat(doc.content).contains("<ADIF_VER:5>3.1.4 ")
        assertThat(doc.content).contains("<PROGRAMID:5>FT8AF ")
        assertThat(doc.content).contains("<EOH>\n")
        // Two QSOs in, two records out.
        assertThat(doc.content.split("<EOR>").size - 1).isEqualTo(2)
        assertThat(doc.content).contains("<CALL:4>W1AW ")
        assertThat(doc.content).contains("<CALL:5>K9XYZ ")
    }

    @Test
    fun singlePark_overridesMySigInfoToTheParkRef() {
        insertQso("W1AW", "20240601", "123500", mySigInfo = "K-1234")

        val doc = PotaAdifExporter.buildActivationAdif(db, activation("K-1234")).single()

        // MY_SIG_INFO is pinned to the park (6 UTF-8 bytes), not copied from DB.
        assertThat(doc.content).contains("<MY_SIG_INFO:6>K-1234 ")
        assertThat(doc.content).contains("<MY_SIG:4>POTA ")
    }

    @Test
    fun rowsAreOrderedByDateThenTime() {
        // Insert out of order; the query's ORDER BY qso_date, time_on must sort them.
        // Both times are inside the activation window (12:34–13:34).
        insertQso("LATER", "20240601", "130000", mySigInfo = "K-1234")
        insertQso("EARLY", "20240601", "123500", mySigInfo = "K-1234")

        val content = PotaAdifExporter.buildActivationAdif(db, activation("K-1234")).single().content

        assertThat(content.indexOf("EARLY")).isLessThan(content.indexOf("LATER"))
    }

    @Test
    fun twoFer_emitsOneDocumentPerPark_sameQsosDifferentMySigInfo() {
        // A two-fer stores the full comma string in my_sig_info (the WHERE key).
        insertQso("W1AW", "20240601", "123500", mySigInfo = "K-1234,K-5678")
        insertQso("K9XYZ", "20240601", "123600", mySigInfo = "K-1234,K-5678")

        val docs = PotaAdifExporter.buildActivationAdif(db, activation("K-1234,K-5678"))

        assertThat(docs.map { it.parkRef }).containsExactly("K-1234", "K-5678").inOrder()

        val first = docs[0]
        val second = docs[1]
        // Both files carry the same QSOs...
        assertThat(first.content).contains("<CALL:4>W1AW ")
        assertThat(second.content).contains("<CALL:4>W1AW ")
        assertThat(first.content.split("<EOR>").size).isEqualTo(second.content.split("<EOR>").size)
        // ...but each pins MY_SIG_INFO to its own park.
        assertThat(first.content).contains("<MY_SIG_INFO:6>K-1234 ")
        assertThat(first.content).doesNotContain("<MY_SIG_INFO:6>K-5678 ")
        assertThat(second.content).contains("<MY_SIG_INFO:6>K-5678 ")
        assertThat(second.content).doesNotContain("<MY_SIG_INFO:6>K-1234 ")
        // Filenames are per-park.
        assertThat(first.filename).isEqualTo("pota-K-1234-20240601-1234.adi")
        assertThat(second.filename).isEqualTo("pota-K-5678-20240601-1234.adi")
    }

    @Test
    fun noMatchingQsos_returnsEmptyList_notHeaderOnlyDocs() {
        // A row exists, but for a different park — so this activation has no QSOs.
        insertQso("OTHER", "20240601", "123500", mySigInfo = "K-9999")

        val docs = PotaAdifExporter.buildActivationAdif(db, activation("K-1234"))

        // Must be empty (so callers reject it), not one header-only doc per park.
        assertThat(docs).isEmpty()
    }

    @Test
    fun noRowsAtAll_returnsEmptyList() {
        val docs = PotaAdifExporter.buildActivationAdif(db, activation("K-1234,K-5678"))

        assertThat(docs).isEmpty()
    }

    @Test
    fun onlyQsosInsideTheActivationWindowAreIncluded() {
        // Three QSOs at the SAME park, but from three different sessions:
        // one before the window, one inside it, one after. Only the in-window
        // QSO belongs to this activation — the export must not lump the others
        // in (the reported bug: every activation at a park bled together).
        insertQso("BEFORE", "20240601", "120000", mySigInfo = "K-1234") // 12:00:00 < 12:34
        insertQso("INSIDE", "20240601", "130000", mySigInfo = "K-1234") // 13:00:00 in window
        insertQso("AFTER", "20240601", "140000", mySigInfo = "K-1234") // 14:00:00 > 13:34

        val content = PotaAdifExporter.buildActivationAdif(db, activation("K-1234")).single().content

        assertThat(content).contains("INSIDE")
        assertThat(content).doesNotContain("BEFORE")
        assertThat(content).doesNotContain("AFTER")
        // Exactly the one in-window QSO.
        assertThat(content.split("<EOR>").size - 1).isEqualTo(1)
    }

    @Test
    fun variableWidthTimeOn_isNormalizedForTheWindowCompare() {
        // time_on is stored variable-width: app-logged rows are HHMMSS, but
        // imported/edited ADIF rows may be HHMM or drop a leading zero ("815" =
        // 08:15). A naive `qso_date || time_on` compare would misplace those; the
        // shared PotaQsoWindow.ROW_STAMP normalizes to 6 digits first. Window
        // below is 08:00:00–09:00:00 UTC on 2024-06-01.
        val window = PotaActivation(
            id = 1L,
            parkRef = "K-1234",
            operator = "K1ABC",
            startedAtMs = 1_717_228_800_000L, // 2024-06-01 08:00:00 UTC
            endedAtMs = 1_717_232_400_000L, // 2024-06-01 09:00:00 UTC
            qsoCount = 0,
            notes = null,
        )
        insertQso("ODD", "20240601", "815", mySigInfo = "K-1234") // 08:15, dropped leading zero
        insertQso("HHMM", "20240601", "0830", mySigInfo = "K-1234") // 08:30, HHMM (no seconds)
        insertQso("FULL", "20240601", "084500", mySigInfo = "K-1234") // 08:45, HHMMSS
        insertQso("LATEODD", "20240601", "930", mySigInfo = "K-1234") // 09:30, after window

        val content = PotaAdifExporter.buildActivationAdif(db, window).single().content

        assertThat(content).contains("ODD")
        assertThat(content).contains("HHMM")
        assertThat(content).contains("FULL")
        // 09:30 is past the 09:00 end — must be excluded despite the short width.
        assertThat(content).doesNotContain("LATEODD")
        assertThat(content.split("<EOR>").size - 1).isEqualTo(3)
    }

    @Test
    fun openActivation_includesQsosAfterStart_withNoEndBound() {
        // An activation still in progress has endedAtMs == null. QSOs logged
        // after the start must still export; the far-future upper bound lets them.
        insertQso("LIVE", "20240601", "130000", mySigInfo = "K-1234")
        val open = activation("K-1234").copy(endedAtMs = null)

        val content = PotaAdifExporter.buildActivationAdif(db, open).single().content

        assertThat(content).contains("LIVE")
    }

    @Test
    fun onlyPotaRowsMatchingTheParkAreIncluded() {
        insertQso("INPARK", "20240601", "123500", mySigInfo = "K-1234")
        // Different park — must not bleed into K-1234's document.
        insertQso("OTHER", "20240601", "123500", mySigInfo = "K-9999")

        val content = PotaAdifExporter.buildActivationAdif(db, activation("K-1234")).single().content

        assertThat(content).contains("INPARK")
        assertThat(content).doesNotContain("OTHER")
    }
}
