package radio.ks3ckc.ft8af.ui.rateprompt

/**
 * Pure decision logic for the in-app rating prompt. [RatePromptHost] gathers the
 * log stats + persisted [RatePromptState] and asks these functions whether to show
 * the sheet, which header to use, where a star tap routes, and how each dismissal
 * rewrites the persisted state. Nothing here touches Android or Compose, so it is
 * all unit-tested directly.
 */

/** The prompt never appears before the operator has logged this many QSOs. */
internal const val RATE_PROMPT_MIN_QSOS = 5

/** "Remind me later" (or any decline) re-arms the prompt this many QSOs later. */
internal const val RATE_PROMPT_REMIND_INTERVAL_QSOS = 15

/** Declining this many times resolves the prompt for good — never ask again. */
internal const val RATE_PROMPT_MAX_DECLINES = 2

/**
 * Ending a POTA activation also checks the prompt, but only when the activation
 * reached the park-credit minimum — a clean finish, not a bust or a quick test.
 */
internal const val RATE_PROMPT_MIN_ACTIVATION_QSOS = 10

/** Wait for the post-log "QSO : …" confirmation toast to clear before showing. */
internal const val RATE_PROMPT_SHOW_DELAY_MS = 1_500L

internal const val RATE_PROMPT_STAR_COUNT = 5

/** Star ratings at or above this go to the Play review step; below, to feedback. */
internal const val RATE_PROMPT_POSITIVE_MIN_STARS = 4

/** Which header the ask step shows. Everything below the header is shared. */
enum class RatePromptVariant { MILESTONE, MINIMAL }

/** The sheet's current page: the star ask, the Play hand-off, or the feedback form. */
enum class RatePromptStep { ASK, PLAY, FEEDBACK }

/** Which headline the MILESTONE header names the operator's session with. */
enum class MilestoneHeadline { ENTITIES_ACROSS_BANDS, ENTITIES, CONTACTS_ACROSS_BANDS }

/** The persisted eligibility state (DataStore; see [RatePromptStore]). */
internal data class RatePromptState(
    val declineCount: Int = 0,
    val nextEligibleQsoCount: Int = 0,
    val resolved: Boolean = false,
)

/** Snapshot of the log at trigger time — not lifetime device stats. */
internal data class RatePromptLogStats(
    val qsoCount: Int,
    val bandsWorked: Int,
    val dxccEntities: Int,
)

internal fun shouldShowRatePrompt(
    qsoCount: Int,
    nextEligible: Int,
    declineCount: Int,
    resolved: Boolean,
): Boolean =
    !resolved &&
        declineCount < RATE_PROMPT_MAX_DECLINES &&
        qsoCount >= RATE_PROMPT_MIN_QSOS &&
        qsoCount >= nextEligible

internal fun ratePromptVariant(bandsWorked: Int, dxccEntities: Int): RatePromptVariant =
    if (bandsWorked > 1 || dxccEntities > 2) RatePromptVariant.MILESTONE else RatePromptVariant.MINIMAL

/**
 * The variant to show for this log snapshot, or null when the prompt isn't
 * eligible. Combines [shouldShowRatePrompt] and [ratePromptVariant] so the host
 * makes a single decision.
 */
internal fun evaluateRatePrompt(stats: RatePromptLogStats, state: RatePromptState): RatePromptVariant? =
    if (shouldShowRatePrompt(stats.qsoCount, state.nextEligibleQsoCount, state.declineCount, state.resolved)) {
        ratePromptVariant(stats.bandsWorked, stats.dxccEntities)
    } else {
        null
    }

/**
 * Whether ending an activation with [activationQsoCount] QSOs should run the
 * prompt check. Only a trigger — [shouldShowRatePrompt] still gates the result.
 */
internal fun activationEndTriggersRatePrompt(activationQsoCount: Int): Boolean =
    activationQsoCount >= RATE_PROMPT_MIN_ACTIVATION_QSOS

/** 4–5 stars hand off to Play; 1–3 open the feedback form. The value is never sent. */
internal fun ratePromptStepForStars(stars: Int): RatePromptStep {
    require(stars in 1..RATE_PROMPT_STAR_COUNT) { "stars out of range: $stars" }
    return if (stars >= RATE_PROMPT_POSITIVE_MIN_STARS) RatePromptStep.PLAY else RatePromptStep.FEEDBACK
}

/** Headline for the MILESTONE header, picked from whichever milestone was hit. */
internal fun milestoneHeadline(bandsWorked: Int, dxccEntities: Int): MilestoneHeadline = when {
    dxccEntities > 2 && bandsWorked > 1 -> MilestoneHeadline.ENTITIES_ACROSS_BANDS
    dxccEntities > 2 -> MilestoneHeadline.ENTITIES
    else -> MilestoneHeadline.CONTACTS_ACROSS_BANDS
}

/** Spell a count with [words] (index == value), falling back to digits past the list. */
internal fun spelledCount(count: Int, words: List<String>): String =
    words.getOrNull(count) ?: count.toString()

/** Send feedback only once the operator has actually typed something. */
internal fun canSendRatePromptFeedback(text: String): Boolean = text.isNotBlank()

/**
 * A decline — "Remind me later", swipe-down, Back, scrim tap, or "Not now" on
 * the Play step. Counts toward [RATE_PROMPT_MAX_DECLINES] and re-arms the prompt
 * [RATE_PROMPT_REMIND_INTERVAL_QSOS] QSOs past [qsoCount]; hitting the cap
 * resolves it permanently.
 */
internal fun RatePromptState.afterDecline(qsoCount: Int): RatePromptState {
    val declines = declineCount + 1
    return copy(
        declineCount = declines,
        nextEligibleQsoCount = qsoCount + RATE_PROMPT_REMIND_INTERVAL_QSOS,
        resolved = resolved || declines >= RATE_PROMPT_MAX_DECLINES,
    )
}

/** "Don't ask again" — resolved permanently. */
internal fun RatePromptState.afterDontAskAgain(): RatePromptState = copy(resolved = true)

/** A branch was completed (Play review launched, or feedback sent/skipped). */
internal fun RatePromptState.afterCompleted(): RatePromptState = copy(resolved = true)

/**
 * Swipe / Back / close while the sheet is on [step]. On the ask and Play steps
 * that's a decline; on the feedback step the operator has already rated, so
 * closing the form counts as "Skip" — a completed branch.
 */
internal fun RatePromptState.afterDismiss(step: RatePromptStep, qsoCount: Int): RatePromptState =
    when (step) {
        RatePromptStep.ASK, RatePromptStep.PLAY -> afterDecline(qsoCount)
        RatePromptStep.FEEDBACK -> afterCompleted()
    }
