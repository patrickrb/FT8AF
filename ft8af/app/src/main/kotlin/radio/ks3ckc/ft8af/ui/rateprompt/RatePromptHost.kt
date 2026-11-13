package radio.ks3ckc.ft8af.ui.rateprompt

import android.database.sqlite.SQLiteDatabase
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8af.pota.PotaSessionManager
import radio.ks3ckc.ft8af.ui.settings.sendAppFeedbackEmail

/**
 * Hosts [RatePromptSheet] at the root of FT8AFApp. Each time a QSO is logged
 * ([qsoCompletedAt] turns non-null) or a POTA activation of at least
 * [RATE_PROMPT_MIN_ACTIVATION_QSOS] QSOs ends, it waits for the confirmation
 * toast to clear and for any TX to finish, snapshots the log, and shows the sheet
 * if [evaluateRatePrompt] says so. Every exit path rewrites the persisted state.
 */
@Composable
fun RatePromptHost(
    qsoCompletedAt: Long?,
    isTransmitting: Boolean,
    database: () -> SQLiteDatabase?,
) {
    val context = LocalContext.current
    val store = remember { RatePromptStore.from(context) }
    val transmitting by rememberUpdatedState(isTransmitting)

    var visible by remember { mutableStateOf(false) }
    var variant by remember { mutableStateOf(RatePromptVariant.MINIMAL) }
    var stats by remember { mutableStateOf(RatePromptLogStats(0, 0, 0)) }
    var step by remember { mutableStateOf(RatePromptStep.ASK) }
    val closeLatch = remember { RatePromptCloseLatch() }

    // qsoCompletedAt is a one-shot LiveData that FT8AFApp resets to null right
    // away; keying the check on it directly would cancel the delayed check on
    // that reset. Turn each non-null value into a monotonic token instead (same
    // pattern as QsoCelebration).
    var triggerToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(qsoCompletedAt) {
        if (qsoCompletedAt != null) triggerToken++
    }

    // Ending a POTA activation is a natural pause after a good run — the operator
    // has stopped working stations — so it runs the same check (same gates, same
    // suppression) when the activation earned park credit.
    LaunchedEffect(Unit) {
        PotaSessionManager.endedActivations.collect { ended ->
            if (activationEndTriggersRatePrompt(ended.qsoCount)) triggerToken++
        }
    }

    LaunchedEffect(triggerToken) {
        if (triggerToken == 0 || visible) return@LaunchedEffect
        // Let the post-log toast clear (this also gives the async QSO insert time
        // to land), then hold off while the radio is mid-TX.
        delay(RATE_PROMPT_SHOW_DELAY_MS)
        snapshotFlow { transmitting }.first { !it }
        val db = database() ?: return@LaunchedEffect
        val snapshot = withContext(Dispatchers.IO) {
            runCatching { queryRatePromptLogStats(db) }.getOrNull()
        } ?: return@LaunchedEffect
        val shown = evaluateRatePrompt(snapshot, store.read()) ?: return@LaunchedEffect
        variant = shown
        stats = snapshot
        step = RatePromptStep.ASK
        closeLatch.open()
        visible = true
    }

    val close: ((RatePromptState) -> RatePromptState) -> Unit = close@{ transform ->
        // One persisted close per presentation: the sheet's Back handler stays
        // active during its exit animation, so a repeat dismissal must be a no-op.
        if (!closeLatch.tryClose()) return@close
        visible = false
        store.persist(transform)
    }

    RatePromptSheet(
        visible = visible,
        variant = variant,
        stats = stats,
        step = step,
        onDismiss = {
            val dismissedOn = step
            close { it.afterDismiss(dismissedOn, stats.qsoCount) }
        },
        onRateOnPlay = {
            // Straight to the Play Store listing, not the In-App Review API: Google's
            // guidelines say not to trigger the review card from a button, since it
            // silently shows nothing once the user's review quota is spent. If no store
            // app or browser can open it, keep the sheet up rather than resolving.
            if (openPlayStoreListing(context)) {
                close { it.afterCompleted() }
            }
        },
        onOpenFeedback = { step = RatePromptStep.FEEDBACK },
        onRemindLater = { close { it.afterDecline(stats.qsoCount) } },
        onDontAskAgain = { close { it.afterDontAskAgain() } },
        onSendFeedback = { feedback ->
            // No email app → the "no email app" toast shows and the sheet stays
            // open with the typed text, so the feedback isn't silently dropped
            // and the prompt isn't resolved; Skip still closes it.
            if (sendAppFeedbackEmail(context, feedback)) {
                close { it.afterCompleted() }
            }
        },
        onSkipFeedback = { close { it.afterCompleted() } },
    )
}
