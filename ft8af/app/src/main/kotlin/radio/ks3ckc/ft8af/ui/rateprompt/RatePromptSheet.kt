package radio.ks3ckc.ft8af.ui.rateprompt

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.AccentSoft
import radio.ks3ckc.ft8af.theme.BgApp
import radio.ks3ckc.ft8af.theme.BgSurface
import radio.ks3ckc.ft8af.theme.BgSurface2
import radio.ks3ckc.ft8af.theme.Border
import radio.ks3ckc.ft8af.theme.BorderStrong
import radio.ks3ckc.ft8af.theme.FT8AFTypography
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.Signal
import radio.ks3ckc.ft8af.theme.StatusConfirmed
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary
import radio.ks3ckc.ft8af.ui.components.FT8AFBottomSheet

private val ButtonMinHeight = 48.dp
private val TileShape = RoundedCornerShape(12.dp)

/**
 * The in-app rating prompt. The ask step shows a [variant]-specific header, then two
 * equal actions — open the Play Store listing, or write private feedback — over the
 * shared dismiss row.
 *
 * There is deliberately no rating question in front of the Play action: Google Play's
 * in-app review guidelines say an app "shouldn't ask the user any questions before or
 * while presenting the rating button", including opinion or predictive ones ("Would
 * you rate this app 5 stars"). So feedback sits beside Play instead of gating it.
 * Decisions (eligibility, persistence) live in [RatePromptLogic] and [RatePromptHost];
 * this only renders and reports taps.
 *
 * [onDismiss] is swipe-down, Back, scrim tap, or the sheet's close button.
 */
@Composable
internal fun RatePromptSheet(
    visible: Boolean,
    variant: RatePromptVariant,
    stats: RatePromptLogStats,
    step: RatePromptStep,
    onDismiss: () -> Unit,
    onRateOnPlay: () -> Unit,
    onOpenFeedback: () -> Unit,
    onRemindLater: () -> Unit,
    onDontAskAgain: () -> Unit,
    onSendFeedback: (String) -> Unit,
    onSkipFeedback: () -> Unit,
) {
    FT8AFBottomSheet(visible = visible, onDismiss = onDismiss) {
        RatePromptSheetContent(
            variant = variant,
            stats = stats,
            step = step,
            onRateOnPlay = onRateOnPlay,
            onOpenFeedback = onOpenFeedback,
            onRemindLater = onRemindLater,
            onDontAskAgain = onDontAskAgain,
            onSendFeedback = onSendFeedback,
            onSkipFeedback = onSkipFeedback,
        )
    }
}

/** The sheet body without the [FT8AFBottomSheet] chrome, so previews can render it. */
@Composable
internal fun RatePromptSheetContent(
    variant: RatePromptVariant,
    stats: RatePromptLogStats,
    step: RatePromptStep,
    onRateOnPlay: () -> Unit,
    onOpenFeedback: () -> Unit,
    onRemindLater: () -> Unit,
    onDontAskAgain: () -> Unit,
    onSendFeedback: (String) -> Unit,
    onSkipFeedback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = step,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "rate-prompt-step",
        // imePadding: the feedback field raises the keyboard, and the app is
        // edge-to-edge with no adjustResize, so without it the Send/Skip buttons
        // sit hidden behind the IME. Padding grows the bottom-anchored sheet up
        // above the keyboard instead.
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .imePadding(),
    ) { current ->
        when (current) {
            RatePromptStep.ASK -> Column(Modifier.fillMaxWidth()) {
                when (variant) {
                    RatePromptVariant.MILESTONE -> MilestoneHeader(stats)
                    RatePromptVariant.MINIMAL -> MinimalHeader(stats.qsoCount)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.rate_prompt_play_pitch),
                    style = FT8AFTypography.bodyMedium,
                    color = TextMuted,
                )
                Spacer(Modifier.height(20.dp))
                PrimaryButton(stringResource(R.string.rate_prompt_play_action), onClick = onRateOnPlay)
                Spacer(Modifier.height(8.dp))
                TonalButton(stringResource(R.string.rate_prompt_feedback_action), onClick = onOpenFeedback)
                Spacer(Modifier.height(16.dp))
                DismissRow(onRemindLater = onRemindLater, onDontAskAgain = onDontAskAgain)
            }
            RatePromptStep.FEEDBACK -> FeedbackStep(onSend = onSendFeedback, onSkip = onSkipFeedback)
        }
    }
}

@Composable
private fun MilestoneHeader(stats: RatePromptLogStats) {
    Text(
        text = stringResource(R.string.rate_prompt_eyebrow),
        style = FT8AFTypography.labelSmall,
        color = Accent,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = milestoneHeadlineText(stats),
        style = FT8AFTypography.headlineMedium,
        color = TextPrimary,
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatCard(stats.qsoCount, stringResource(R.string.rate_prompt_stat_qsos), Accent)
        StatCard(stats.dxccEntities, stringResource(R.string.rate_prompt_stat_dxcc), Signal)
        StatCard(stats.bandsWorked, stringResource(R.string.rate_prompt_stat_bands), StatusConfirmed)
    }
}

@Composable
private fun milestoneHeadlineText(stats: RatePromptLogStats): String {
    val capitalized = stringArrayResource(R.array.rate_prompt_count_words_capitalized).asList()
    val lower = stringArrayResource(R.array.rate_prompt_count_words).asList()
    return when (milestoneHeadline(stats.bandsWorked, stats.dxccEntities)) {
        MilestoneHeadline.ENTITIES_ACROSS_BANDS -> stringResource(
            R.string.rate_prompt_headline_entities_bands,
            spelledCount(stats.dxccEntities, capitalized),
            spelledCount(stats.bandsWorked, lower),
        )
        MilestoneHeadline.ENTITIES -> stringResource(
            R.string.rate_prompt_headline_entities,
            spelledCount(stats.dxccEntities, capitalized),
        )
        MilestoneHeadline.CONTACTS_ACROSS_BANDS -> stringResource(
            R.string.rate_prompt_headline_contacts_bands,
            spelledCount(stats.qsoCount, capitalized),
            spelledCount(stats.bandsWorked, lower),
        )
    }
}

@Composable
private fun RowScope.StatCard(value: Int, caption: String, color: Color) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(TileShape)
            .background(BgSurface)
            .border(1.dp, Border, TileShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = value.toString(),
            color = color,
            fontFamily = GeistMonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
        )
        Text(
            text = caption,
            style = FT8AFTypography.bodySmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun MinimalHeader(qsoCount: Int) {
    Text(
        text = stringResource(R.string.rate_prompt_minimal_title),
        style = FT8AFTypography.headlineMedium,
        color = TextPrimary,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.rate_prompt_minimal_body, qsoCount),
        style = FT8AFTypography.bodyMedium,
        color = TextMuted,
    )
}

@Composable
private fun DismissRow(onRemindLater: () -> Unit, onDontAskAgain: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onRemindLater,
            shape = TileShape,
            border = BorderStroke(1.dp, BorderStrong),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ButtonMinHeight),
        ) {
            Text(
                text = stringResource(R.string.rate_prompt_remind_later),
                style = FT8AFTypography.labelLarge,
                color = TextPrimary,
            )
        }
        TextButton(
            onClick = onDontAskAgain,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ButtonMinHeight),
        ) {
            Text(
                text = stringResource(R.string.rate_prompt_dont_ask_again),
                style = FT8AFTypography.labelLarge,
                color = TextFaint,
            )
        }
    }
}

@Composable
private fun FeedbackStep(onSend: (String) -> Unit, onSkip: () -> Unit) {
    var feedback by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.rate_prompt_feedback_title),
            style = FT8AFTypography.headlineMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.rate_prompt_feedback_body),
            style = FT8AFTypography.bodyMedium,
            color = TextMuted,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = feedback,
            onValueChange = { feedback = it },
            placeholder = {
                Text(stringResource(R.string.rate_prompt_feedback_hint), color = TextFaint)
            },
            singleLine = false,
            minLines = 4,
            textStyle = FT8AFTypography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Accent,
                focusedBorderColor = Accent,
                unfocusedBorderColor = BorderStrong,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = stringResource(R.string.rate_prompt_feedback_send),
            enabled = canSendRatePromptFeedback(feedback),
            onClick = { onSend(feedback) },
        )
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ButtonMinHeight),
        ) {
            Text(
                text = stringResource(R.string.rate_prompt_feedback_skip),
                style = FT8AFTypography.labelLarge,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = TileShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            contentColor = BgApp,
            disabledContainerColor = AccentSoft,
            disabledContentColor = TextFaint,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ButtonMinHeight),
    ) {
        Text(text = text, style = FT8AFTypography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Second action beside the primary one: same size, quieter surface. */
@Composable
private fun TonalButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = TileShape,
        border = BorderStroke(1.dp, Border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = BgSurface, contentColor = TextPrimary),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ButtonMinHeight),
    ) {
        Text(text = text, style = FT8AFTypography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------
// Previews — one per state: milestone ask, minimal ask, feedback step.
// ---------------------------------------------------------------------------

private val PreviewMilestoneStats = RatePromptLogStats(qsoCount = 12, bandsWorked = 2, dxccEntities = 3)
private val PreviewMinimalStats = RatePromptLogStats(qsoCount = 5, bandsWorked = 1, dxccEntities = 1)

@Composable
private fun RatePromptPreviewFrame(
    variant: RatePromptVariant,
    stats: RatePromptLogStats,
    step: RatePromptStep,
) {
    Box(Modifier.background(BgSurface2).padding(top = 16.dp)) {
        RatePromptSheetContent(
            variant = variant,
            stats = stats,
            step = step,
            onRateOnPlay = {},
            onOpenFeedback = {},
            onRemindLater = {},
            onDontAskAgain = {},
            onSendFeedback = {},
            onSkipFeedback = {},
        )
    }
}

@Preview(name = "Milestone ask", widthDp = 380)
@Composable
private fun RatePromptMilestoneAskPreview() {
    RatePromptPreviewFrame(RatePromptVariant.MILESTONE, PreviewMilestoneStats, RatePromptStep.ASK)
}

@Preview(name = "Minimal ask", widthDp = 380)
@Composable
private fun RatePromptMinimalAskPreview() {
    RatePromptPreviewFrame(RatePromptVariant.MINIMAL, PreviewMinimalStats, RatePromptStep.ASK)
}

@Preview(name = "Feedback step", widthDp = 380)
@Composable
private fun RatePromptFeedbackStepPreview() {
    RatePromptPreviewFrame(RatePromptVariant.MINIMAL, PreviewMinimalStats, RatePromptStep.FEEDBACK)
}
