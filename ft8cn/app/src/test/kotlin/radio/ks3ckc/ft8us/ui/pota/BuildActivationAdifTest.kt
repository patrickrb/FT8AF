package radio.ks3ckc.ft8us.ui.pota

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import radio.ks3ckc.ft8us.pota.model.PotaActivation
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
     * comma-separated park string.
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

    private fun activation(parkRef: String) = PotaActivation(
        id = 1L,
        parkRef = parkRef,
        operator = "K1ABC",
        startedAtMs = startedAtMs,
        endedAtMs = startedAtMs + 60_000L,
        qsoCount = 0,
        notes = null,
    )

    @Test
    fun singlePark_emitsOneDocumentWithHeaderAndDeterministicName() {
        insertQso("W1AW", "20240601", "1230", mySigInfo = "K-1234")
        insertQso("K9XYZ", "20240601", "1231", mySigInfo = "K-1234")

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
        insertQso("W1AW", "20240601", "1230", mySigInfo = "K-1234")

        val doc = PotaAdifExporter.buildActivationAdif(db, activation("K-1234")).single()

        // MY_SIG_INFO is pinned to the park (6 UTF-8 bytes), not copied from DB.
        assertThat(doc.content).contains("<MY_SIG_INFO:6>K-1234 ")
        assertThat(doc.content).contains("<MY_SIG:4>POTA ")
    }

    @Test
    fun rowsAreOrderedByDateThenTime() {
        // Insert out of order; the query's ORDER BY qso_date, time_on must sort them.
        insertQso("LATER", "20240601", "1300", mySigInfo = "K-1234")
        insertQso("EARLY", "20240601", "1200", mySigInfo = "K-1234")

        val content = PotaAdifExporter.buildActivationAdif(db, activation("K-1234")).single().content

        assertThat(content.indexOf("EARLY")).isLessThan(content.indexOf("LATER"))
    }

    @Test
    fun twoFer_emitsOneDocumentPerPark_sameQsosDifferentMySigInfo() {
        // A two-fer stores the full comma string in my_sig_info (the WHERE key).
        insertQso("W1AW", "20240601", "1230", mySigInfo = "K-1234,K-5678")
        insertQso("K9XYZ", "20240601", "1231", mySigInfo = "K-1234,K-5678")

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
    fun onlyPotaRowsMatchingTheParkAreIncluded() {
        insertQso("INPARK", "20240601", "1230", mySigInfo = "K-1234")
        // Different park — must not bleed into K-1234's document.
        insertQso("OTHER", "20240601", "1230", mySigInfo = "K-9999")

        val content = PotaAdifExporter.buildActivationAdif(db, activation("K-1234")).single().content

        assertThat(content).contains("INPARK")
        assertThat(content).doesNotContain("OTHER")
    }
}
