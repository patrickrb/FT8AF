package radio.ks3ckc.ft8af.hunt

import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import radio.ks3ckc.ft8af.pota.PotaCqClassifier
import radio.ks3ckc.ft8af.pota.PotaSpotsRepository

/**
 * How Hunt picks which qualifying CQ to answer when several are decoded in the
 * same cycle. [LATEST] is the historical behavior (most recent decode wins).
 */
enum class HuntPriority {
    LATEST,
    STRONGEST,
    WEAKEST,
    FARTHEST,
    POTA_FIRST,
    NEW_DXCC_FIRST,
    NEW_GRID_FIRST,
}

/**
 * Parse the persisted config value back to a [HuntPriority]. Unknown/blank/null
 * values (fresh install, downgrade, hand-edited config) fall back to [HuntPriority.LATEST]
 * so Hunt never breaks on a bad stored string.
 */
fun huntPriorityFromConfig(value: String?): HuntPriority =
    HuntPriority.entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
        ?: HuntPriority.LATEST

/**
 * One eligible CQ caller, reduced to the facts the ranking needs. Pure data so
 * [selectHuntCandidate] can be unit-tested without Android.
 *
 * @param index        position in the eligible list; the list is most-recent-first,
 *                     so a smaller index means a fresher decode
 * @param snr          decoder SNR in dB, or null when the decoder didn't set one
 * @param distanceKm   great-circle distance to the caller, or null when unknown
 * @param isActivator  the CQ is a POTA/SOTA activation
 * @param isNewPark    the activation is from a park not yet in the hunted log
 * @param isNewDxcc    the caller's DXCC entity hasn't been worked
 * @param isNewGrid    the caller's grid square hasn't been worked
 * @param pileupCalls  how many other stations were heard answering this caller
 *                     in the same decode window
 */
data class HuntCandidate(
    val index: Int,
    val snr: Int? = null,
    val distanceKm: Double? = null,
    val isActivator: Boolean = false,
    val isNewPark: Boolean = false,
    val isNewDxcc: Boolean = false,
    val isNewGrid: Boolean = false,
    val pileupCalls: Int = 0,
)

/**
 * Whether a candidate clears the minimum-signal floor. A null floor disables the
 * check; a candidate with no SNR passes (the decoder occasionally omits SNR, and
 * silently dropping those stations would make Hunt look randomly deaf).
 */
internal fun passesSnrFloor(snr: Int?, minSnrDb: Int?): Boolean =
    minSnrDb == null || snr == null || snr >= minSnrDb

/** POTA_FIRST tier: unhunted park > any activation > everyone else. */
internal fun activatorRank(c: HuntCandidate): Int = when {
    c.isNewPark -> 2
    c.isActivator -> 1
    else -> 0
}

/**
 * Pick the best CQ to answer from this cycle's eligible callers.
 *
 * The floor is a hard filter (a too-weak CQ is never answered — better to wait a
 * cycle than start a QSO that fizzles). Pileup avoidance is a soft preference:
 * candidates nobody else is answering win, but when every caller has a pileup we
 * still pick one rather than sit silent. Ties always go to the freshest decode:
 * the input is most-recent-first and every comparator ends with an explicit
 * [HuntCandidate.index] tie-breaker, so the winner never depends on sort stability.
 */
internal fun selectHuntCandidate(
    candidates: List<HuntCandidate>,
    priority: HuntPriority,
    avoidPileups: Boolean,
    minSnrDb: Int?,
): HuntCandidate? {
    val floored = candidates.filter { passesSnrFloor(it.snr, minSnrDb) }
    if (floored.isEmpty()) return null
    val pool = if (avoidPileups) {
        floored.filter { it.pileupCalls == 0 }.ifEmpty { floored }
    } else {
        floored
    }
    val comparator: Comparator<HuntCandidate> = when (priority) {
        HuntPriority.LATEST -> return pool.first()
        HuntPriority.STRONGEST -> compareByDescending { it.snr ?: Int.MIN_VALUE }
        // For WEAKEST an unknown SNR must not win the "weakest" crown, so it sorts last.
        HuntPriority.WEAKEST -> compareBy { it.snr ?: Int.MAX_VALUE }
        HuntPriority.FARTHEST -> compareByDescending { it.distanceKm ?: -1.0 }
        HuntPriority.POTA_FIRST ->
            compareByDescending<HuntCandidate> { activatorRank(it) }
                .thenByDescending { it.snr ?: Int.MIN_VALUE }
        HuntPriority.NEW_DXCC_FIRST ->
            compareByDescending<HuntCandidate> { it.isNewDxcc }
                .thenByDescending { it.snr ?: Int.MIN_VALUE }
        HuntPriority.NEW_GRID_FIRST ->
            compareByDescending<HuntCandidate> { it.isNewGrid }
                .thenByDescending { it.snr ?: Int.MIN_VALUE }
    }
    // index is unique, so the composed comparator is a total order: the freshest
    // decode wins ties by contract, not by sort stability. minWithOrNull also skips
    // sorting/allocating a ranked copy of the pool every decode cycle.
    return pool.minWithOrNull(comparator.thenBy { it.index })
}

/**
 * Count the other stations heard answering [cqCallsign] in the same decode
 * window: any non-CQ message directed at the caller counts as a competitor.
 */
internal fun countRepliesTo(cqCallsign: String?, messages: List<Ft8Message>): Int {
    if (cqCallsign.isNullOrEmpty()) return 0
    return messages.count { m ->
        !m.checkIsCQ() && cqCallsign.equals(m.callsignTo, ignoreCase = true)
    }
}

/**
 * Bridge for the Java transmit engine: rank this cycle's eligible CQ callers by
 * the operator's Hunt options and return the one to answer.
 *
 * The engine keeps its existing eligibility filters (is CQ, not worked, not
 * excluded, POTA-only filter, directional-CQ respect, …) and hands over only the
 * survivors; this decides *which* survivor gets the call, replacing the old
 * take-the-most-recent behavior (which [HuntPriority.LATEST] reproduces).
 */
object HuntTargetSelector {

    @JvmStatic
    fun pick(eligible: List<Ft8Message>, allMessages: List<Ft8Message>): Ft8Message? {
        if (eligible.isEmpty()) return null
        val priority = huntPriorityFromConfig(GeneralVariables.huntPriority)
        val avoidPileups = GeneralVariables.huntAvoidPileups
        val minSnr = GeneralVariables.huntMinSnr
            .takeIf { it != GeneralVariables.HUNT_MIN_SNR_OFF }

        // Nothing to rank: default priority with no filters active picks the most
        // recent caller without paying for distance/park/pileup lookups per cycle.
        if (priority == HuntPriority.LATEST && !avoidPileups && minSnr == null) {
            return eligible.first()
        }

        val myGrid = GeneralVariables.getMyMaidenheadGrid()
        val candidates = eligible.mapIndexed { index, msg ->
            // Only resolve the facts the active options actually rank on — the
            // park-spot map, worked-grid set, and distance math run every decode
            // cycle otherwise.
            val parkRef = if (priority == HuntPriority.POTA_FIRST) {
                PotaSpotsRepository.parkRefFor(msg.callsignFrom)
            } else null
            HuntCandidate(
                index = index,
                snr = if (msg.hasSnr()) msg.snr else null,
                distanceKm = if (priority == HuntPriority.FARTHEST) {
                    distanceKmTo(myGrid, msg.maidenGrid)
                } else null,
                isActivator = priority == HuntPriority.POTA_FIRST &&
                    (PotaCqClassifier.isPotaCq(msg) || msg.modifier == "SOTA"),
                isNewPark = parkRef != null && !GeneralVariables.checkQSLPark(parkRef),
                isNewDxcc = msg.fromDxcc,
                isNewGrid = priority == HuntPriority.NEW_GRID_FIRST &&
                    isUnworkedGrid(msg.maidenGrid),
                pileupCalls = if (avoidPileups) {
                    countRepliesTo(msg.callsignFrom, allMessages)
                } else 0,
            )
        }
        val chosen = selectHuntCandidate(candidates, priority, avoidPileups, minSnr)
            ?: return null
        return eligible[chosen.index]
    }

    private fun distanceKmTo(myGrid: String?, theirGrid: String?): Double? {
        if (myGrid.isNullOrEmpty() || theirGrid.isNullOrEmpty()) return null
        // getDist returns 0 for an unparseable grid; treat that as unknown.
        return MaidenheadGrid.getDist(myGrid, theirGrid).takeIf { it > 0 }
    }

    private fun isUnworkedGrid(grid: String?): Boolean =
        !grid.isNullOrEmpty() && grid.length >= 4 && !GeneralVariables.checkQSLGrid(grid)
}
