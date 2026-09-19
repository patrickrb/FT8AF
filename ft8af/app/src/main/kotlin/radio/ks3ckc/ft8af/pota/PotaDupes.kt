package radio.ks3ckc.ft8af.pota

import radio.ks3ckc.ft8af.pota.model.PotaQso

/**
 * POTA duplicate rules for an activation's contact list.
 *
 * POTA credits the same station again only on a *different band*; a repeat on the
 * same band is a dupe no matter the mode (FT8/FT4/FT2 are all DATA to POTA, and
 * per the program rules mode doesn't split dupes anyway — see issue #823). So the
 * dedupe key is callsign + band, and the chronologically-first contact per key is
 * the one that counts.
 *
 * This mirrors the SQL recount in DatabaseOpr.recountActivationQsos (which keeps
 * pota_activation.qso_count dupe-free) — the two must stay in step or the badge
 * count and the rows marked "dupe" won't add up.
 */

/** Case/whitespace-insensitive dedupe key: one entry per station per band. */
internal fun potaDupeKey(callsign: String, band: String): String =
    callsign.trim().uppercase() + "|" + band.trim().uppercase()

/**
 * Fixed-width `yyyyMMddHHmmss` stamp for chronological ordering. Kotlin mirror of
 * [PotaQsoWindow.ROW_STAMP]'s time_on normalization: app-logged rows are HHMMSS,
 * but imported/hand-edited ADIF rows may be HHMM or drop a leading zero ("815"
 * for 08:15) — pad even-width times right to six digits, prepend a zero to
 * odd-width ones first.
 */
internal fun normalizedQsoStamp(qsoDate: String, timeOn: String): String {
    val t = timeOn.trim()
    val time = when {
        t.isEmpty() -> "000000"
        t.length % 2 == 1 -> ("0$t" + "000000").take(6)
        else -> (t + "000000").take(6)
    }
    return qsoDate.trim() + time
}

/**
 * Ids of the QSOs in [qsos] that do NOT count toward the activation: for each
 * (callsign, band) key the chronologically-first contact counts and every later
 * repeat is a dupe. Input order doesn't matter (the DAO returns newest-first);
 * ties on the stamp fall back to insertion order via id so marking is stable.
 */
internal fun potaDupeQsoIds(qsos: List<PotaQso>): Set<Long> {
    val seen = HashSet<String>()
    val dupes = HashSet<Long>()
    val chronological = qsos.sortedWith(
        compareBy({ normalizedQsoStamp(it.qsoDate, it.timeOn) }, { it.id }),
    )
    for (qso in chronological) {
        if (!seen.add(potaDupeKey(qso.callsign, qso.band))) dupes.add(qso.id)
    }
    return dupes
}
