package radio.ks3ckc.ft8af.ui.rateprompt

import android.database.SQLException
import android.database.sqlite.SQLiteDatabase

internal const val SQL_RATE_PROMPT_QSO_COUNT = "SELECT COUNT(*) FROM QSLTable"

internal const val SQL_RATE_PROMPT_BANDS_WORKED =
    "SELECT COUNT(DISTINCT UPPER(band)) FROM QSLTable WHERE band IS NOT NULL AND band <> ''"

/**
 * Distinct DXCC entities in the log, resolved the same way the Logbook's DXCC
 * stat is (CountDbOpr.GetDxccCount): each QSO's 4-char grid joined to dxcc_grid.
 */
internal const val SQL_RATE_PROMPT_DXCC_ENTITIES =
    "SELECT COUNT(DISTINCT dg.dxcc) FROM dxcc_grid dg " +
        "INNER JOIN QSLTable q ON dg.grid = UPPER(SUBSTR(q.gridsquare, 1, 4))"

/**
 * Read the rate-prompt [RatePromptLogStats] from the QSO log. Blocking — call it
 * off the main thread. The DXCC lookup degrades to 0 when the dxcc_grid table
 * isn't there yet (first-launch import still running) rather than failing the
 * whole snapshot.
 */
internal fun queryRatePromptLogStats(db: SQLiteDatabase): RatePromptLogStats {
    val dxcc = try {
        countOf(db, SQL_RATE_PROMPT_DXCC_ENTITIES)
    } catch (_: SQLException) {
        0
    }
    return RatePromptLogStats(
        qsoCount = countOf(db, SQL_RATE_PROMPT_QSO_COUNT),
        bandsWorked = countOf(db, SQL_RATE_PROMPT_BANDS_WORKED),
        dxccEntities = dxcc,
    )
}

private fun countOf(db: SQLiteDatabase, sql: String): Int =
    db.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
