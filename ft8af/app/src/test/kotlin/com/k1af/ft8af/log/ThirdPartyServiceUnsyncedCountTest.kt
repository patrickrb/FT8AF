package com.k1af.ft8af.log

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Coverage for [ThirdPartyService.countUnsyncedQSOs] — the gate the auto-sync uses to
 * decide whether anything is actually pending before spawning an upload pass. It must
 * agree with the WHERE clause [ThirdPartyService.syncAllQSOs] uses, keyed off the
 * per-service enable flags. Drives a real (Robolectric) in-memory SQLite database.
 */
@RunWith(RobolectricTestRunner::class)
class ThirdPartyServiceUnsyncedCountTest {

    private lateinit var db: SQLiteDatabase
    private var savedCloudlog = false
    private var savedQrz = false

    @Before
    fun setUp() {
        savedCloudlog = GeneralVariables.enableCloudlog
        savedQrz = GeneralVariables.enableQRZ
        db = SQLiteDatabase.create(null)
        db.execSQL(
            """
            CREATE TABLE QSLTable (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                synced_cloudlog INTEGER DEFAULT 0,
                synced_qrz INTEGER DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        GeneralVariables.enableCloudlog = savedCloudlog
        GeneralVariables.enableQRZ = savedQrz
    }

    private fun insert(cloudlog: Int, qrz: Int) {
        db.execSQL(
            "INSERT INTO QSLTable (synced_cloudlog, synced_qrz) VALUES (?, ?)",
            arrayOf<Any>(cloudlog, qrz),
        )
    }

    @Test
    fun zero_whenNoServiceEnabled() {
        GeneralVariables.enableCloudlog = false
        GeneralVariables.enableQRZ = false
        insert(cloudlog = 0, qrz = 0) // pending for both, but nothing is enabled
        assertThat(ThirdPartyService.countUnsyncedQSOs(db)).isEqualTo(0)
    }

    @Test
    fun zero_whenDbNull() {
        GeneralVariables.enableCloudlog = true
        assertThat(ThirdPartyService.countUnsyncedQSOs(null)).isEqualTo(0)
    }

    @Test
    fun countsCloudlogPending_whenOnlyCloudlogEnabled() {
        GeneralVariables.enableCloudlog = true
        GeneralVariables.enableQRZ = false
        insert(cloudlog = 0, qrz = 0) // needs cloudlog
        insert(cloudlog = 1, qrz = 0) // cloudlog done -> not counted (qrz disabled)
        insert(cloudlog = 0, qrz = 1) // needs cloudlog
        assertThat(ThirdPartyService.countUnsyncedQSOs(db)).isEqualTo(2)
    }

    @Test
    fun countsQrzPending_whenOnlyQrzEnabled() {
        GeneralVariables.enableCloudlog = false
        GeneralVariables.enableQRZ = true
        insert(cloudlog = 0, qrz = 0) // needs qrz
        insert(cloudlog = 0, qrz = 1) // qrz done -> not counted
        assertThat(ThirdPartyService.countUnsyncedQSOs(db)).isEqualTo(1)
    }

    @Test
    fun countsEitherPending_whenBothEnabled() {
        GeneralVariables.enableCloudlog = true
        GeneralVariables.enableQRZ = true
        insert(cloudlog = 1, qrz = 1) // fully synced -> not counted
        insert(cloudlog = 0, qrz = 1) // needs cloudlog -> counted
        insert(cloudlog = 1, qrz = 0) // needs qrz -> counted
        insert(cloudlog = 0, qrz = 0) // needs both -> counted once
        assertThat(ThirdPartyService.countUnsyncedQSOs(db)).isEqualTo(3)
    }
}
