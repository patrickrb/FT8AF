@file:JvmName("CloudlogFailureHint")

package radio.ks3ckc.ft8af.sync

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R

/**
 * Turns a rejected Cloudlog / Wavelog / Nextlog upload into something the operator can act on.
 *
 * The failure line that reaches us (see `ThirdPartyService.describeHttpFailure`) is the raw
 * HTTP status plus the server's JSON body, e.g.
 *
 *     HTTP 401: {"status":"failed","reason":"station id does not belong to the API key owner."}
 *
 * That line is exactly right for `debug.log`, but on screen the JSON wrapper is noise and the
 * reason alone still leaves the operator guessing which setting to fix. The two Wavelog
 * rejections that account for nearly every "the test passes but nothing uploads" support
 * thread are a Station ID that isn't the key owner's and a key without write rights, so those
 * get a one-line hint pointing at the setting. Pure string logic, unit-tested; the Android
 * touch-points are [cloudlogFailureMessage] resolving string resources and
 * [showCloudlogRejectionToast] posting the toast.
 */
enum class CloudlogFailureKind {
    /** The `station_profile_id` is blank or belongs to another Wavelog user. */
    STATION_ID,

    /** The API key is missing, unknown, disabled, or read-only. */
    API_KEY,

    /** Anything else: transport errors, ADIF import errors, proxies returning HTML… */
    OTHER,
}

private val REASON = Regex("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
private val MESSAGES_START = Regex("\"messages\"\\s*:\\s*\\[")

/** A failure line that carries an HTTP status, i.e. the server answered (vs. no network). */
private val HTTP_STATUS = Regex("^HTTP \\d{3}\\b")

/**
 * Wavelog / Cloudlog's station-ownership rejection ("station id does not belong to the API
 * key owner.") and close variants. Deliberately not a bare "station": ADIF import messages
 * such as "Wrong station callsign …" are about the QSO, not the Station ID setting.
 */
private val STATION_ID_REASON = Regex("station[ _]?(?:profile[ _]?)?id")

/**
 * Decodes the body of a JSON string literal (the part between the quotes): the standard
 * escapes, including `\uXXXX`. A malformed escape is kept as-is rather than dropped.
 */
internal fun decodeJsonString(s: String): String {
    if (s.indexOf('\\') < 0) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\' || i + 1 >= s.length) {
            out.append(c)
            i++
            continue
        }
        when (val e = s[i + 1]) {
            '"', '\\', '/' -> out.append(e)
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> {
                val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6) else ""
                val code = if (hex.length == 4) hex.toIntOrNull(16) else null
                if (code == null) {
                    out.append(c).append(e)
                } else {
                    out.append(code.toChar())
                    i += 4
                }
            }
            else -> out.append(c).append(e)
        }
        i += 2
    }
    return out.toString()
}

/**
 * The string entries of the JSON `messages` array in [raw], or null when there is no such
 * array or it isn't a complete array of strings (e.g. the body was truncated mid-array).
 * Scans token by token, so a `]` or `,` inside a message doesn't end it early.
 */
private fun parseMessagesArray(raw: String): List<String>? {
    val start = MESSAGES_START.find(raw) ?: return null
    val out = ArrayList<String>()
    var i = start.range.last + 1
    while (i < raw.length) {
        val c = raw[i]
        when {
            c.isWhitespace() || c == ',' -> i++
            c == ']' -> return out
            c == '"' -> {
                var j = i + 1
                while (j < raw.length && raw[j] != '"') j += if (raw[j] == '\\') 2 else 1
                if (j >= raw.length) return null // unterminated: truncated body
                out.add(decodeJsonString(raw.substring(i + 1, j)))
                i = j + 1
            }
            else -> return null // numbers, objects, nested arrays: not the shape we know
        }
    }
    return null // no closing bracket: truncated body
}

/**
 * The server's own explanation from the JSON body (`reason`, else the non-empty `messages`
 * entries joined with "; "), or null when the line carries no such field.
 */
internal fun cloudlogParsedReason(failure: String?): String? {
    val raw = failure?.trim().orEmpty()
    if (raw.isEmpty()) return null
    REASON.find(raw)?.let { return decodeJsonString(it.groupValues[1]).trim() }
    val parts = parseMessagesArray(raw)?.map { it.trim() }?.filter { it.isNotEmpty() }
    return if (parts.isNullOrEmpty()) null else parts.joinToString("; ")
}

/**
 * The server's own explanation, stripped of the HTTP/JSON wrapper: the `reason` field when
 * there is one, else the non-empty `messages` entries joined with "; ", else the line as-is
 * (a bare "HTTP 502", "Unknown host: …" or a truncated body is already readable).
 * Blank in, blank out.
 */
fun cloudlogServerReason(failure: String?): String = cloudlogParsedReason(failure) ?: failure?.trim().orEmpty()

/**
 * Which setting the failure points at. Only the server's own JSON reason is classified: a
 * transport error such as "Unknown host: station.example.com" says nothing about the
 * settings. Station check runs first: its text mentions the key too.
 */
fun classifyCloudlogFailure(failure: String?): CloudlogFailureKind {
    val reason = cloudlogParsedReason(failure)?.lowercase() ?: return CloudlogFailureKind.OTHER
    if (STATION_ID_REASON.containsMatchIn(reason)) return CloudlogFailureKind.STATION_ID
    if (reason.contains("api key") || reason.contains("apikey") || reason.contains("key invalid")) {
        return CloudlogFailureKind.API_KEY
    }
    return CloudlogFailureKind.OTHER
}

/**
 * True when every further upload in a batch would be refused the same way (wrong Station ID
 * or API key), so a catch-up sync should stop sending Cloudlog rows instead of repeating the
 * same rejection once per QSO.
 */
fun cloudlogFailureStopsBatch(failure: String?): Boolean = classifyCloudlogFailure(failure) != CloudlogFailureKind.OTHER

/**
 * True when the logbook server itself answered with an HTTP error. Transport failures (no
 * signal, unknown host, timeout) are not rejections: the QSO stays queued and the next
 * catch-up sync sends it, so they don't warrant a "rejected" toast.
 */
fun isCloudlogServerRejection(failure: String?): Boolean = HTTP_STATUS.containsMatchIn(failure?.trim().orEmpty())

/** String resource for the fix-it hint, or null when we have nothing better than the reason. */
fun cloudlogHintResId(failure: String?): Int? =
    when (classifyCloudlogFailure(failure)) {
        CloudlogFailureKind.STATION_ID -> R.string.cloudlog_hint_station_id
        CloudlogFailureKind.API_KEY -> R.string.cloudlog_hint_api_key
        CloudlogFailureKind.OTHER -> null
    }

/**
 * One sentence for a toast: the server's reason followed by the hint when there is one.
 * Resolves resources through [GeneralVariables.getStringFromResource] so it is callable from
 * the Java upload thread in MainViewModel as well as from Kotlin.
 */
fun cloudlogFailureMessage(failure: String?): String {
    val reason = cloudlogServerReason(failure)
    val hint = cloudlogHintResId(failure)?.let { GeneralVariables.getStringFromResource(it) }.orEmpty()
    return when {
        hint.isEmpty() -> reason
        reason.isEmpty() -> hint
        else -> "$reason $hint"
    }
}

/**
 * The full toast text for a server rejection, or null when [failure] isn't one (blank, or a
 * transport error — see [isCloudlogServerRejection]).
 */
fun cloudlogRejectionToastText(failure: String?): String? {
    if (!isCloudlogServerRejection(failure)) return null
    return GeneralVariables.getStringFromResource(R.string.cloudlog_upload_rejected)
        .format(cloudlogFailureMessage(failure))
}

/**
 * Shows [cloudlogRejectionToastText] as a real Android toast from any thread. Uses
 * `Toast` rather than `ToastMessage.show`, whose debug-message stream only the legacy
 * activities observe; the Compose launcher would never display it. Returns whether a toast
 * was posted.
 */
fun showCloudlogRejectionToast(failure: String?): Boolean {
    val text = cloudlogRejectionToastText(failure) ?: return false
    val ctx = GeneralVariables.getMainContext()?.applicationContext ?: return false
    Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
    return true
}
