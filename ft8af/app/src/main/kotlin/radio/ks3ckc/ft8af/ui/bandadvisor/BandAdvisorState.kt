package radio.ks3ckc.ft8af.ui.bandadvisor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R
import com.k1af.ft8af.database.OperationBand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import radio.ks3ckc.ft8af.bandadvisor.AdvisorRequest
import radio.ks3ckc.ft8af.bandadvisor.AdvisorResult
import radio.ks3ckc.ft8af.bandadvisor.BandAdvisor
import radio.ks3ckc.ft8af.bandadvisor.UnavailableReason
import radio.ks3ckc.ft8af.bandadvisor.model.BandRecommendation
import radio.ks3ckc.ft8af.bandadvisor.model.Freshness
import radio.ks3ckc.ft8af.bandadvisor.model.OperatingGoal
import radio.ks3ckc.ft8af.bandadvisor.model.isPlausibleCallsign
import radio.ks3ckc.ft8af.flags.FeatureFlag
import radio.ks3ckc.ft8af.flags.FeatureFlags

// ---------------------------------------------------------------------------
// Pure helpers (unit-tested without Compose)
// ---------------------------------------------------------------------------

/** UI state for the Band Advisor card + detail sheet. */
sealed interface BandAdvisorUiState {
    /** Feature flag off — render nothing, request nothing. */
    object Hidden : BandAdvisorUiState

    /** No valid grid configured; the advisor cannot ask for a recommendation. */
    object NoGrid : BandAdvisorUiState

    object Loading : BandAdvisorUiState

    data class Ready(
        val recommendation: BandRecommendation,
        val freshness: Freshness,
        val fromCache: Boolean,
    ) : BandAdvisorUiState

    data class Unavailable(val reason: UnavailableReason) : BandAdvisorUiState
}

/** Map a repository result to UI state. Pure. */
internal fun uiStateOf(result: AdvisorResult): BandAdvisorUiState = when (result) {
    is AdvisorResult.Available ->
        BandAdvisorUiState.Ready(result.recommendation, result.freshness, result.fromCache)
    is AdvisorResult.Unavailable -> when (result.reason) {
        UnavailableReason.NO_GRID -> BandAdvisorUiState.NoGrid
        else -> BandAdvisorUiState.Unavailable(result.reason)
    }
}

/**
 * Build the advisor request from operator settings. The callsign rides along
 * only when the personal-analytics flag is on AND it is structurally valid —
 * otherwise the request stays anonymous (regional data only).
 */
internal fun advisorRequestFrom(
    grid: String?,
    callsign: String?,
    personalAnalyticsEnabled: Boolean,
    goal: OperatingGoal,
    mode: String,
): AdvisorRequest = AdvisorRequest(
    grid = grid,
    callsign = callsign?.takeIf { personalAnalyticsEnabled && isPlausibleCallsign(it) },
    goal = goal,
    mode = mode,
)

/**
 * The bandList index to tune for a recommended dial, or null when that exact
 * dial is not in the band plan for the active mode — the advisor NEVER invents
 * dial entries; an unknown frequency downgrades to "tune manually" in the UI.
 * (OperationBand.getIndexByFreq is deliberately not used: it appends unknown
 * frequencies to the table as a side effect.)
 */
internal fun advisorTuneIndexFor(
    bands: List<OperationBand.Band>,
    frequencyHz: Long,
    modeId: Int,
): Int? {
    for (i in bands.indices) {
        val b = bands[i]
        if (b.band == frequencyHz && b.mode == modeId) return i
    }
    return null
}

/** Rating bucket for the collapsed card ("Excellent • 87% confidence"). */
internal fun ratingLabelRes(score: Double): Int = when {
    score >= 0.75 -> R.string.band_advisor_rating_excellent
    score >= 0.55 -> R.string.band_advisor_rating_good
    score >= 0.35 -> R.string.band_advisor_rating_fair
    else -> R.string.band_advisor_rating_poor
}

/** Whole minutes since the recommendation was generated (>= 0). */
internal fun ageMinutes(generatedAtMs: Long, nowMs: Long): Long =
    ((nowMs - generatedAtMs).coerceAtLeast(0)) / 60_000

// ---------------------------------------------------------------------------
// State holder (thin; all decisions above are pure)
// ---------------------------------------------------------------------------

/**
 * Loads recommendations for the UI. One instance lives in FT8AFApp; the card
 * and the detail sheet render [state]. All failures end as UI states — nothing
 * from here can disturb decode/TX paths.
 */
class BandAdvisorStateHolder(private val appContext: Context) {
    var state by mutableStateOf<BandAdvisorUiState>(BandAdvisorUiState.Loading)
        private set

    var goal by mutableStateOf(BandAdvisor.goal(appContext))
        private set

    /** Null while the personal flag is off or no callsign is configured. */
    var personalPanel by mutableStateOf<PersonalPanelState?>(null)
        private set

    private var inFlight: Job? = null
    private var personalInFlight: Job? = null

    fun setGoal(scope: CoroutineScope, newGoal: OperatingGoal) {
        if (newGoal == goal) return
        goal = newGoal
        BandAdvisor.setGoal(appContext, newGoal)
        BandAdvisorTelemetry.event("goal_selected", newGoal.wireName)
        load(scope, force = false)
    }

    /** Fetch a recommendation. No-op while the flag is off. */
    fun load(scope: CoroutineScope, force: Boolean) {
        if (!FeatureFlags.isEnabled(FeatureFlag.BAND_ADVISOR)) {
            state = BandAdvisorUiState.Hidden
            return
        }
        if (inFlight?.isActive == true && !force) return
        val request = advisorRequestFrom(
            grid = GeneralVariables.getMyMaidenheadGrid(),
            callsign = GeneralVariables.myCallsign,
            personalAnalyticsEnabled =
                FeatureFlags.isEnabled(FeatureFlag.PERSONAL_PSK_ANALYTICS),
            goal = goal,
            mode = GeneralVariables.currentMode().displayName,
        )
        // Show the cache instantly (if any) while the refresh runs.
        val cached = BandAdvisor.repository(appContext).cached()
        state = if (cached != null) uiStateOf(cached) else BandAdvisorUiState.Loading
        if (force) BandAdvisorTelemetry.event("manual_refresh", null)
        inFlight = scope.launch {
            val result = try {
                BandAdvisor.repository(appContext).recommendation(request, force)
            } catch (e: Exception) {
                // Repository contract says it never throws; belt-and-braces so a
                // bug here can never take down the app.
                AdvisorResult.Unavailable(UnavailableReason.OFFLINE, e.message)
            }
            state = uiStateOf(result)
            when (val s = state) {
                is BandAdvisorUiState.Ready ->
                    BandAdvisorTelemetry.event(
                        "recommendation_loaded",
                        "${s.recommendation.recommendedBand} ${s.freshness}",
                    )
                is BandAdvisorUiState.Unavailable ->
                    BandAdvisorTelemetry.event("recommendation_unavailable", s.reason.name)
                else -> Unit
            }
        }
    }

    /**
     * Refresh the personal PSK Reporter panel. No-op unless the personal flag
     * is on AND a plausible callsign is configured (the panel disappears
     * entirely otherwise, per the flag contract). PskReporterClient's own
     * cooldown prevents refresh-spamming from reaching the service.
     */
    fun loadPersonal(scope: CoroutineScope) {
        if (!FeatureFlags.isEnabled(FeatureFlag.BAND_ADVISOR) ||
            !FeatureFlags.isEnabled(FeatureFlag.PERSONAL_PSK_ANALYTICS) ||
            !isPlausibleCallsign(GeneralVariables.myCallsign)
        ) {
            personalPanel = null
            return
        }
        if (personalInFlight?.isActive == true) return
        if (personalPanel == null) personalPanel = PersonalPanelState.Loading
        personalInFlight = scope.launch {
            val loaded = try {
                loadPersonalPanel(
                    context = appContext,
                    callsign = GeneralVariables.myCallsign,
                    grid = GeneralVariables.getMyMaidenheadGrid(),
                )
            } catch (e: Exception) {
                PersonalPanelState.Error
            }
            // Null = cooldown/transport skip: keep showing the previous data
            // rather than flashing an error over a perfectly good panel.
            if (loaded != null) {
                personalPanel = loaded
            } else if (personalPanel is PersonalPanelState.Loading) {
                personalPanel = PersonalPanelState.Error
            }
        }
    }
}
