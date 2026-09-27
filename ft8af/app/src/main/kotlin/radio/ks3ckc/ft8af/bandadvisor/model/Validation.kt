package radio.ks3ckc.ft8af.bandadvisor.model

/**
 * Input validation for Band Advisor requests. Grid validation for decode paths
 * already lives in MaidenheadGrid.isDecodableGrid; these helpers cover the
 * advisor's narrower needs: a 4-char request grid and a structurally plausible
 * callsign to gate personal analytics on.
 */

private val GRID_4 = Regex("^[A-Ra-r]{2}[0-9]{2}$")

// Structural amateur callsign check (not an allocation lookup): optional
// prefix ("VP2E/"), a body of 1-2 letters+digit(s) or digit+letters pattern
// with at least one digit and one letter, optional suffix ("/P", "/QRP").
// Intentionally permissive — its job is to reject empty/garbage values like
// "NOCALL" placeholders' obvious junk, not to police license databases.
private val CALLSIGN_BODY = Regex("^[A-Z0-9]{1,3}[0-9][A-Z0-9]{0,3}[A-Z]$")

/**
 * Normalize any decodable grid to the 4-character square the advisor sends —
 * the app never transmits 6-char precision for recommendations (privacy:
 * a 4-char square is ~1°×2°). Returns null when the input can't be reduced
 * to a valid 4-char square.
 */
fun normalizeAdvisorGrid(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.length < 4) return null
    val four = trimmed.substring(0, 4)
    if (!GRID_4.matches(four)) return null
    return four.substring(0, 2).uppercase() + four.substring(2)
}

/**
 * True when [raw] looks like a real amateur callsign. Used to gate personal
 * PSK Reporter analytics — no valid callsign means the feature stays off.
 */
fun isPlausibleCallsign(raw: String?): Boolean {
    val call = raw?.trim()?.uppercase() ?: return false
    if (call.length < 3 || call.length > 12) return false
    // Split off portable prefix/suffix around "/" and validate the longest part
    // as the body (covers "W1AW/P" and "VP2E/K1AF" alike).
    val body = call.split('/').maxByOrNull { it.length } ?: return false
    return CALLSIGN_BODY.matches(body)
}
