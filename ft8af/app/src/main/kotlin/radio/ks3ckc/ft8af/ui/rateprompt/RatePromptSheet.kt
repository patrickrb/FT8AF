package radio.ks3ckc.ft8af.ui.rateprompt

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import radio.ks3ckc.ft8af.theme.TextDim
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary
import radio.ks3ckc.ft8af.ui.components.FT8AFBottomSheet

private val StarTileSize = 52.dp
private val ButtonMinHeight = 48.dp
private val TileShape = RoundedCornerShape(12.dp)

/**
 * The in-app rating prompt. The ask step shows a [variant]-specific header over
 * the shared star row + dismiss row; a star tap advances straight to the shared
 * Play or feedback step. All decisions (eligibility, routing, persistence) live in
 * [RatePromptLogic] and [RatePromptHost] — this only renders and reports taps.
 *
 * [onDismiss] is swipe-down, Back, scrim tap, or the sheet's close button.
 */
@Composable
internal fun RatePromptSheet(
    visible: Boolean,
    variant: RatePromptVariant,
    stats: RatePromptLogStats,
    step: RatePromptStep,
    selectedStars: Int,
    onDismiss: () -> Unit,
    onStarSelected: (Int) -> Unit,
    onRemindLater: () -> Unit,
    onDontAskAgain: () -> Unit,
    onRateOnPlay: () -> Unit,
    onNotNow: () -> Unit,
    onSendFeedback: (String) -> Unit,
    onSkipFeedback: () -> Unit,
) {
    FT8AFBottomSheet(visible = visible, onDismiss = onDismiss) {
        RatePromptSheetContent(
            variant = variant,
            stats = stats,
            step = step,
            selectedStars = selectedStars,
            onStarSelected = onStarSelected,
            onRemindLater = onRemindLater,
            onDontAskAgain = onDontAskAgain,
            onRateOnPlay = onRateOnPlay,
            onNotNow = onNotNow,
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
    selectedStars: Int,
    onStarSelected: (Int) -> Unit,
    onRemindLater: () -> Unit,
    onDontAskAgain: () -> Unit,
    onRateOnPlay: () -> Unit,
    onNotNow: () -> Unit,
    onSendFeedback: (String) -> Unit,
    onSkipFeedback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Crossfade between steps: the outgoing ask page keeps its selected stars on
    // screen while it fades, so the tap still reads as registered even though it
    // advances immediately.
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
                Spacer(Modifier.height(20.dp))
                StarRow(selectedStars = selectedStars, onStarSelected = onStarSelected)
                Spacer(Modifier.height(20.dp))
                DismissRow(onRemindLater = onRemindLater, onDontAskAgain = onDontAskAgain)
            }
            RatePromptStep.PLAY -> PlayStep(onRateOnPlay = onRateOnPlay, onNotNow = onNotNow)
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
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.rate_prompt_milestone_question),
        style = FT8AFTypography.bodyMedium,
        color = TextMuted,
    )
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
private fun StarRow(selectedStars: Int, onStarSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        for (star in 1..RATE_PROMPT_STAR_COUNT) {
            val selected = star <= selectedStars
            val description = stringResource(R.string.rate_prompt_star_description, star)
            Box(
                modifier = Modifier
                    .size(StarTileSize)
                    .clip(TileShape)
                    .background(if (selected) AccentSoft else Color.Transparent)
                    .clickable { onStarSelected(star) }
                    .semantics {
                        role = Role.Button
                        contentDescription = description
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = if (selected) Accent else TextDim,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
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
private fun PlayStep(onRateOnPlay: () -> Unit, onNotNow: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        StepHeading(
            title = stringResource(R.string.rate_prompt_play_title),
            body = stringResource(R.string.rate_prompt_play_body),
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(stringResource(R.string.rate_prompt_play_action), onClick = onRateOnPlay)
        Spacer(Modifier.height(4.dp))
        SecondaryButton(stringResource(R.string.rate_prompt_not_now), onClick = onNotNow)
    }
}

@Composable
private fun FeedbackStep(onSend: (String) -> Unit, onSkip: () -> Unit) {
    var feedback by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        StepHeading(
            title = stringResource(R.string.rate_prompt_feedback_title),
            body = stringResource(R.string.rate_prompt_feedback_body),
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
        SecondaryButton(stringResource(R.string.rate_prompt_feedback_skip), onClick = onSkip)
    }
}

@Composable
private fun StepHeading(title: String, body: String) {
    Text(text = title, style = FT8AFTypography.headlineMedium, color = TextPrimary)
    Spacer(Modifier.height(6.dp))
    Text(text = body, style = FT8AFTypography.bodyMedium, color = TextMuted)
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

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ButtonMinHeight),
    ) {
        Text(text = text, style = FT8AFTypography.labelLarge, color = TextMuted)
    }
}

// ---------------------------------------------------------------------------
// Previews — one per state: milestone ask, minimal ask, Play step, feedback step.
// ---------------------------------------------------------------------------

private val PreviewMilestoneStats = RatePromptLogStats(qsoCount = 12, bandsWorked = 2, dxccEntities = 3)
private val PreviewMinimalStats = RatePromptLogStats(qsoCount = 5, bandsWorked = 1, dxccEntities = 1)

@Composable
private fun RatePromptPreviewFrame(
    variant: RatePromptVariant,
    stats: RatePromptLogStats,
    step: RatePromptStep,
    selectedStars: Int = 0,
) {
    Box(Modifier.background(BgSurface2).padding(top = 16.dp)) {
        RatePromptSheetContent(
            variant = variant,
            stats = stats,
            step = step,
            selectedStars = selectedStars,
            onStarSelected = {},
            onRemindLater = {},
            onDontAskAgain = {},
            onRateOnPlay = {},
            onNotNow = {},
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

@Preview(name = "Play step", widthDp = 380)
@Composable
private fun RatePromptPlayStepPreview() {
    RatePromptPreviewFrame(RatePromptVariant.MILESTONE, PreviewMilestoneStats, RatePromptStep.PLAY, selectedStars = 5)
}

@Preview(name = "Feedback step", widthDp = 380)
@Composable
private fun RatePromptFeedbackStepPreview() {
    RatePromptPreviewFrame(RatePromptVariant.MINIMAL, PreviewMinimalStats, RatePromptStep.FEEDBACK, selectedStars = 2)
}
