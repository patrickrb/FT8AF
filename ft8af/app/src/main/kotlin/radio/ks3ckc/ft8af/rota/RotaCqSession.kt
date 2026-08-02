package radio.ks3ckc.ft8af.rota

import android.util.Log
import com.k1af.ft8af.GeneralVariables
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The CQ modifier a running trip transmits: `CQ ROTA <call> <grid>`.
 *
 * An FT8 standard message packs the CQ into the 28-bit `c28` field, whose
 * reserved range encodes `CQ` plus *one to four letters* (or three digits) —
 * that is the whole vocabulary the format has. The app enforces this at
 * [com.k1af.ft8af.Ft8Message] (`[0-9]{3}|[A-Z]{1,4}`), where an over-long
 * modifier is dropped and the CQ goes out bare: audible, decodable, and
 * silently missing the thing that made it a trip CQ.
 *
 * `ROTA` fits at exactly four, the same as `POTA`. Until the program was renamed
 * from Road Trips On The Air this token had to be the scrambled `RTOA`, because
 * `RTOTA` was five letters and had no encoding at all; the rename is what let the
 * on-air token finally become the program's actual name.
 */
const val ROTA_CQ_MODIFIER = "ROTA"

/** The modifier grammar an FT8 standard message can actually encode. */
private val ENCODABLE_MODIFIER = Regex("[0-9]{3}|[A-Z]{1,4}")

/**
 * True when [modifier] survives the trip from `GeneralVariables.toModifier` into
 * a transmitted CQ. Anything else is dropped by the message formatter, so a
 * caller that cares whether its token will actually go out asks here first.
 */
fun isEncodableCqModifier(modifier: String?): Boolean =
    !modifier.isNullOrEmpty() && ENCODABLE_MODIFIER.matches(modifier)

/**
 * What to remember as the operator's own preference when a trip takes the
 * modifier over.
 *
 * Returns "" when [current] is already ours, which is the case that matters: a
 * trip re-applying its modifier (a second start, a restore after the process was
 * killed) must not record `ROTA` as the thing to put back — that would make the
 * road-trip token outlive the road trip, and every later CQ would keep claiming
 * a trip that ended.
 */
fun modifierToRemember(current: String?): String = if (current == ROTA_CQ_MODIFIER) "" else current.orEmpty()

/**
 * The modifier to restore when a trip ends, or null to leave it alone.
 *
 * Null means "someone else owns this now". A POTA activation started mid-trip
 * saved `ROTA` and set `POTA`; if ending the trip blindly wrote the pre-trip
 * value back, the operator would be mid-park-activation transmitting a plain CQ,
 * and POTA's own end would then restore `ROTA` on top — a token for a trip that
 * is over. Deferring to whoever holds the modifier keeps both save/restore
 * stacks honest no matter which order they unwind in.
 */
fun modifierAfterTripEnd(
    current: String?,
    remembered: String,
): String? = if (current == ROTA_CQ_MODIFIER) remembered else null

/**
 * Owns `GeneralVariables.toModifier` for the duration of a trip, the same way
 * [radio.ks3ckc.ft8af.pota.PotaSessionManager] owns it for an activation — save
 * what was there, impose ours, put it back on the way out.
 *
 * Ordering note for callers: this must be applied *after* the persisted config
 * has loaded. `DatabaseOpr` assigns `GeneralVariables.toModifier` straight from
 * the stored config row as part of that load, so applying earlier both saves a
 * value that isn't the operator's real preference and gets overwritten moments
 * later.
 */
object RotaCqSession {
    private const val TAG = "RotaCqSession"

    /** The operator's own modifier, parked here while a trip runs. */
    @Volatile
    private var remembered: String = ""

    /** True while this session is the one holding the modifier. */
    val isApplied: Boolean get() = GeneralVariables.toModifier == ROTA_CQ_MODIFIER

    /**
     * Impose `ROTA` for a running trip. Idempotent: calling it again while it is
     * already applied changes nothing and, crucially, does not overwrite the
     * remembered preference.
     */
    @Synchronized
    fun apply() {
        val current = GeneralVariables.toModifier
        if (current == ROTA_CQ_MODIFIER) {
            log("apply — already set")
            return
        }
        remembered = modifierToRemember(current)
        GeneralVariables.toModifier = ROTA_CQ_MODIFIER
        log("apply modifier='$ROTA_CQ_MODIFIER' remembered='$remembered'")
    }

    /** Hand the modifier back when the trip ends. */
    @Synchronized
    fun release() {
        val restored = modifierAfterTripEnd(GeneralVariables.toModifier, remembered)
        if (restored == null) {
            log("release skipped — modifier is now '${GeneralVariables.toModifier}', not ours")
            remembered = ""
            return
        }
        GeneralVariables.toModifier = restored
        log("release restored='$restored'")
        remembered = ""
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        try {
            val ctx = GeneralVariables.getMainContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(File(dir, "debug.log"), true).use { it.append("$ts RotaCq: $msg\n") }
        } catch (_: Exception) {
        }
    }
}
