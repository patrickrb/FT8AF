package radio.ks3ckc.ft8af.ui.decode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import com.k1af.ft8af.database.DatabaseOpr
import com.k1af.ft8af.log.PriorQso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.StatusConfirmed
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary
import radio.ks3ckc.ft8af.ui.components.FT8AFIcons
import radio.ks3ckc.ft8af.ui.components.GlassCard

/**
 * Structured summary of the operator's prior QSOs with one station, ready to
 * render in the decode sheet's "Worked before" card.
 *
 * `lastLine` is a pre-joined "date · band · mode" recap of the most recent
 * contact (blank parts skipped); `bands` is the distinct set of bands ever
 * worked, in first-worked order, so band-fill hunters can see at a glance what
 * they still need. Both are derived by [summarizeWorkedBefore].
 */
internal data class WorkedBeforeSummary(
    val count: Int,
    val lastLine: String,
    val bands: List<String>,
)

/** Separator used to join the recap parts and the band list. */
private const val PART_SEP = " · " // " · "

/**
 * Collapse a list of prior [PriorQso] rows into a [WorkedBeforeSummary], or null
 * when there is no prior contact (so the caller renders nothing).
 *
 * The most recent QSO is chosen by (qso_date, time_on) ascending, independent of
 * the input order, so callers don't have to pre-sort. Distinct bands are kept in
 * the order they were first worked (input order after the stable sort).
 */
internal fun summarizeWorkedBefore(qsos: List<PriorQso>): WorkedBeforeSummary? {
    if (qsos.isEmpty()) return null

    val sorted = qsos.sortedWith(
        compareBy({ it.qsoDate.orEmpty() }, { it.timeOn.orEmpty() }),
    )
    val last = sorted.last()

    val lastLine = listOf(
        formatQsoDate(last.qsoDate),
        last.band?.trim().orEmpty(),
        last.mode?.trim().orEmpty(),
    ).filter { it.isNotEmpty() }.joinToString(PART_SEP)

    val bands = sorted
        .mapNotNull { it.band?.trim()?.takeIf { b -> b.isNotEmpty() } }
        .distinct()

    return WorkedBeforeSummary(
        count = qsos.size,
        lastLine = lastLine,
        bands = bands,
    )
}

/**
 * Format an ADIF `qso_date` (`YYYYMMDD`) as `YYYY-MM-DD`. Anything that isn't
 * exactly 8 digits is returned trimmed and unchanged (e.g. already-formatted or
 * malformed values), and null becomes "".
 */
internal fun formatQsoDate(raw: String?): String {
    val d = raw?.trim().orEmpty()
    return if (d.length == 8 && d.all { it.isDigit() }) {
        "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}"
    } else {
        d
    }
}

/**
 * "Worked before" card: shows how many times — and most recently when/where —
 * the operator has logged the given [callsign], plus the distinct bands worked.
 *
 * Reads [DatabaseOpr.getPriorQsos] off the main thread and renders nothing until
 * the query resolves, or if the station has never been worked (keeps the sheet
 * clean for the common first-contact case). The heavy lifting is in the pure
 * [summarizeWorkedBefore]; this composable is a thin renderer.
 */
@Composable
internal fun WorkedBeforeCard(callsign: String) {
    val context = LocalContext.current
    val summary by produceState<WorkedBeforeSummary?>(initialValue = null, callsign) {
        value = if (callsign.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val rows = try {
                    DatabaseOpr.getInstance(context, null).getPriorQsos(callsign.trim())
                } catch (_: Exception) {
                    emptyList<PriorQso>()
                }
                summarizeWorkedBefore(rows)
            }
        }
    }

    val s = summary ?: return

    // Own the trailing gap so a never-worked station (early return above) leaves
    // no empty space between the last-heard row and the path map.
    Column(modifier = Modifier.fillMaxWidth()) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FT8AFIcons.Check(color = StatusConfirmed, size = 16.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.qso_worked_before_title),
                        color = TextFaint,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.06.sp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.qso_worked_before_count,
                            s.count,
                            s.count,
                        ),
                        color = StatusConfirmed,
                        fontFamily = GeistMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }

                if (s.lastLine.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.qso_worked_before_last, s.lastLine),
                        color = TextPrimary,
                        fontFamily = GeistMonoFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }

                // Only surface the band list when there's more than one — a single
                // band is already implied by the recap line above.
                if (s.bands.size > 1) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.qso_worked_before_bands,
                            s.bands.joinToString(PART_SEP),
                        ),
                        color = TextMuted,
                        fontFamily = GeistMonoFamily,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}
