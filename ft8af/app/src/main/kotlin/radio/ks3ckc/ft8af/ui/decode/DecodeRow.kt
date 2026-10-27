package radio.ks3ckc.ft8af.ui.decode

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.R
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.QsoStatus
import radio.ks3ckc.ft8af.ui.components.SignalBar
import radio.ks3ckc.ft8af.ui.components.StatusPill
import radio.ks3ckc.ft8af.ui.motion.MotionTokens

/**
 * A single decoded FT8 message row.
 *
 * Layout:
 *  - Left accent bar for CQ messages
 *  - "CQ" or "TO YOU" label
 *  - Callsign (large, monospace)
 *  - Grid locator
 *  - Status pill
 *  - Metadata row: signal bar, SNR, frequency, distance, UTC time
 *  - DX entity location line for CQ messages
 *
 * Background tinting:
 *  - Cyan glow when the message is directed at the operator
 *  - Surface tint for CQ messages
 *  - Transparent for others
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DecodeRow(
    message: Ft8Message,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    animateEntry: Boolean = false,
    nowMillis: Long = 0L,
    isTarget: Boolean = false,
    compact: Boolean = false,
) {
    val isCQ = message.checkIsCQ()
    val isToMe = GeneralVariables.checkIsMyCallsign(message.callsignTo ?: "")
    // Dim rows that are clearly mid-QSO with a third party — they're noise
    // when the operator is scanning for someone to call.
    val isInQsoWithOther = !isCQ && !isToMe && !isTarget
    val shape = RoundedCornerShape(12.dp)

    // Background color based on message type
    val bgColor = when {
        isToMe -> Color(0x145CD6E8)   // cyan glow rgba(92,214,232,0.08)
        isTarget -> TargetSoft         // pink — current call target's transmissions
        isCQ -> BgSurface              // surface card
        else -> Color.Transparent
    }
    val borderColor = when {
        isToMe -> Color(0x385CD6E8)   // rgba(92,214,232,0.22)
        isTarget -> TargetBorder       // pink border for target rows
        isCQ -> Border
        else -> Color.Transparent
    }

    val entryAnim = remember { Animatable(if (animateEntry) 0f else 1f) }
    LaunchedEffect(animateEntry) {
        if (animateEntry) {
            entryAnim.snapTo(0f)
            entryAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = MotionTokens.DurMed,
                    easing = MotionTokens.EasingEmphasizedDecel,
                ),
            )
        } else {
            entryAnim.snapTo(1f)
        }
    }

    val metrics = decodeRowMetrics(compact)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entryAnim.value * decodeRowDimAlpha(isInQsoWithOther)
                translationY = (1f - entryAnim.value) * -12f
            }
            .padding(horizontal = 12.dp, vertical = metrics.outerVerticalDp.dp)
            .clip(shape)
            .background(bgColor, shape)
            .border(1.dp, borderColor, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(
                start = 0.dp,
                end = 12.dp,
                top = metrics.innerVerticalDp.dp,
                bottom = metrics.innerVerticalDp.dp,
            ),
        verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top,
    ) {
        // Left accent bar — pink for the current call target, amber for CQ.
        // Target wins because it's the more actionable signal for the operator.
        val accentColor = when {
            isTarget -> Target
            isCQ -> Accent
            else -> null
        }
        if (accentColor != null) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(metrics.accentBarDp.dp)
                    .background(accentColor, RoundedCornerShape(99.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
        } else {
            Spacer(modifier = Modifier.width(13.dp))
        }

        // Shared across both layouts below, computed once so compact/expanded
        // differ only in ARRANGEMENT, never in the underlying values.
        val hoistedStatus = resolveQsoStatus(message)
        // The canonical decoded frame (e.g. "K1ABC W9XYZ EN37") -- same text
        // the expanded view's second line shows. Compact uses this instead of
        // a derived callsign+grid snippet so free-text/telemetry messages
        // (which have no parsed callsignFrom) aren't left blank.
        val hoistedMsgText = message.getMessageText()?.trim().orEmpty()

        Column(modifier = Modifier.weight(1f)) {
            if (compact) {
                // Single-line compact row: the canonical message text (same
                // content as the expanded view's second line) plus its third
                // line's signal metadata, minus the signal bargraph and the
                // "ago" time -- user request, merging those two lines into one
                // instead of showing a derived callsign+grid snippet. CALLING
                // and the status pill are additive markers carried over from
                // the expanded view's top line; they aren't part of either
                // merged line so they keep their own meaning here.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val labels = decodeRowLabels(isTarget, isCQ, isToMe, compact = true)
                    // Which trailing cells fit next to a readable message on this
                    // width (see compactRowLayout); a narrow phone gives up
                    // metadata, then the pill's label, instead of the text.
                    // A CALLING chip comes out of the message's width, not the
                    // columns', so every row gets the same layout and lines up.
                    // The grid is observed so a GPS/settings update re-lays out
                    // rows already on screen.
                    val myGrid by GeneralVariables.mutableMyMaidenheadGrid
                        .observeAsState(GeneralVariables.getMyMaidenheadGrid())
                    val layout = compactRowLayout(
                        availableDp = maxWidth.value,
                        hasOperatorGrid = !myGrid.isNullOrEmpty(),
                        showHeading = GeneralVariables.showBeamHeading,
                    )
                    val emphasis = compactMessageEmphasis(isToMe = isToMe, isTarget = isTarget)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(COMPACT_GAP_DP.dp),
                    ) {
                        for (label in labels) {
                            DecodeRowLabelChip(label)
                        }
                        Text(
                            text = hoistedMsgText,
                            color = when (emphasis) {
                                CompactMessageEmphasis.TO_YOU -> Signal
                                CompactMessageEmphasis.TARGET -> Target
                                CompactMessageEmphasis.NORMAL -> TextMuted
                            },
                            fontWeight = if (emphasis == CompactMessageEmphasis.NORMAL) null else FontWeight.Bold,
                            fontFamily = GeistMonoFamily,
                            fontSize = 12.sp,
                            letterSpacing = 0.02.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        // Fixed-width slots (reserved even when the cell's content
                        // is absent, e.g. no heading for a station without a
                        // grid, no pill without a status) so the columns start at the same x on
                        // every row instead of zig-zagging with whichever optional
                        // cells happen to be present -- real-device measurement
                        // showed dB's start x varying by 300+ px row to row
                        // otherwise. Unit labels are dropped here to save width;
                        // the expanded view still spells them out.
                        for (column in layout.columns) {
                            Box(modifier = Modifier.width(column.widthDp.dp), contentAlignment = Alignment.CenterEnd) {
                                when (column) {
                                    CompactMetaColumn.SNR ->
                                        MetaText(decodeSnrText(message.hasSnr(), message.snr, withUnit = false))
                                    CompactMetaColumn.FREQ ->
                                        MetaText("${message.getFreq_hz()}")
                                    CompactMetaColumn.DT ->
                                        MetaText(
                                            text = formatDecodeDt(message.time_sec),
                                            color = if (isDecodeDtNotable(message.time_sec)) StatusWarn else TextFaint,
                                        )
                                    CompactMetaColumn.HEADING ->
                                        computeBeamHeadingText(message).takeIf { it.isNotEmpty() }
                                            ?.let { MetaText(it) }
                                }
                            }
                        }
                        Box(modifier = Modifier.width(layout.pillSlotDp.dp), contentAlignment = Alignment.CenterEnd) {
                            if (hoistedStatus != null) {
                                if (layout.pillDotOnly) {
                                    StatusDot(hoistedStatus)
                                } else {
                                    StatusPill(status = hoistedStatus, compact = true)
                                }
                            }
                        }
                    }
                }
            } else {
                // Grid locator, or whatever else the message's own third field
                // held (report, RR73, 73) when it wasn't grid-shaped -- see
                // secondaryFieldText. Free-text messages have no callsignFrom to
                // show this next to, so they're excluded.
                val hoistedGrid = if (message.callsignFrom.isNullOrEmpty()) {
                    ""
                } else {
                    secondaryFieldText(message.maidenGrid, message.extraInfo)
                }
                val hoistedAgoText = if (nowMillis > 0L) formatTimeAgo(message.utcTime, nowMillis) else ""
                val hoistedDistanceText = computeDistanceText(message)

                // Top row: label + callsign + grid + status pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // CALLING / TO YOU / CQ labels (can stack \u2014 e.g. target station
                    // calling CQ shows both CALLING and CQ).
                    for (label in decodeRowLabels(isTarget, isCQ, isToMe, compact = false)) {
                        DecodeRowLabelChip(label)
                    }

                    // Callsign
                    Text(
                        text = message.callsignFrom ?: "",
                        color = when {
                            isToMe -> Signal
                            isTarget -> Target
                            else -> TextPrimary
                        },
                        fontFamily = GeistMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 0.02.sp,
                    )

                    if (hoistedGrid.isNotEmpty()) {
                        Text(
                            text = hoistedGrid,
                            color = TextFaint,
                            fontFamily = GeistMonoFamily,
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Status pill (skipped when there's no useful state to surface)
                    if (hoistedStatus != null) {
                        StatusPill(status = hoistedStatus, compact = true)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Full decoded message text (canonical FT8 frame, e.g. "K1ABC W9XYZ EN37"
                // or "CQ POTA W1ABC FN42"). This is what was actually transmitted.
                if (hoistedMsgText.isNotEmpty()) {
                    Text(
                        text = hoistedMsgText,
                        color = TextMuted,
                        fontFamily = GeistMonoFamily,
                        fontSize = 12.sp,
                        letterSpacing = 0.02.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Metadata row: signal bar, SNR, frequency, distance, UTC time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SignalBar(snr = if (message.hasSnr()) message.snr else -30, width = 28.dp, height = 12.dp)

                    MetaText(decodeSnrText(message.hasSnr(), message.snr, withUnit = true))
                    MetaText("${message.getFreq_hz()} Hz")

                    // DT, as WSJT-X shows it: how far into our RX window this signal
                    // started. Per-decode rather than only the averaged pill on the slot
                    // bar, because the two answer different questions — every station
                    // sitting at the same offset is our clock, one station out on its own
                    // is that station's.
                    MetaText(
                        text = "DT ${formatDecodeDt(message.time_sec)}",
                        color = if (isDecodeDtNotable(message.time_sec)) StatusWarn else TextFaint,
                    )

                    // Distance (computed from grid)
                    if (hoistedDistanceText.isNotEmpty()) {
                        MetaText(hoistedDistanceText)
                    }

                    // Beam heading (short-path bearing) — opt-in, for beam operators
                    // who want to know which way to turn the antenna without opening
                    // the QSO sheet first.
                    if (GeneralVariables.showBeamHeading) {
                        val headingText = computeBeamHeadingText(message)
                        if (headingText.isNotEmpty()) {
                            MetaText(headingText)
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Relative "ago" time
                    if (hoistedAgoText.isNotEmpty()) {
                        MetaText(hoistedAgoText)
                    }
                }

                // State / DX entity location line (shown on every row when known)
                run {
                    val context = LocalContext.current
                    val locationText = resolveLocationText(context, message)
                    if (!locationText.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            radio.ks3ckc.ft8af.ui.components.FT8AFIcons.Globe(
                                color = TextFaint,
                                size = 12.dp,
                            )
                            Text(
                                text = locationText,
                                color = TextFaint,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Alpha applied to a decode row. Rows for stations mid-QSO with a third party
 * ("noise" when the operator is scanning for someone to call) are de-emphasized
 * but kept readable; everything else renders at full opacity. See issue #332 —
 * the old 0.55 floor stacked on already-muted greys was too dark for some users.
 */
internal fun decodeRowDimAlpha(isInQsoWithOther: Boolean): Float =
    if (isInQsoWithOther) 0.8f else 1f

/**
 * A decode row's vertical spacing: the gap around each row card
 * ([outerVerticalDp], each side), the card's inner top/bottom padding
 * ([innerVerticalDp]) and the accent bar's height ([accentBarDp]).
 */
internal data class DecodeRowMetrics(
    val outerVerticalDp: Int,
    val innerVerticalDp: Int,
    val accentBarDp: Int,
)

/** Height of the compact row's tallest content, the compact status pill. */
internal const val COMPACT_ROW_CONTENT_DP = 18

/**
 * Compact rows are a single line, so they drop the padding the two-line
 * layout needed: on a real phone a compact row measured 37dp, mostly
 * padding around 18dp of content, and only ~11 rows fit where a busy slot
 * had 15. Expanded rows keep their original spacing.
 */
internal fun decodeRowMetrics(compact: Boolean): DecodeRowMetrics =
    if (compact) {
        DecodeRowMetrics(outerVerticalDp = 1, innerVerticalDp = 2, accentBarDp = 14)
    } else {
        DecodeRowMetrics(outerVerticalDp = 3, innerVerticalDp = 10, accentBarDp = 52)
    }

/** The height a row with [metrics] takes in the list, its 1dp border included. */
internal fun decodeRowHeightDp(metrics: DecodeRowMetrics, contentDp: Int): Int =
    2 * metrics.outerVerticalDp + 2 + 2 * metrics.innerVerticalDp + maxOf(contentDp, metrics.accentBarDp)

/**
 * The text to show next to the callsign for the message's own exchanged
 * third field -- a grid locator ("EM98"), or whatever else that field held
 * ("RR73", "73", a signal report like "-04"/"R+10") when it isn't a grid.
 *
 * <p>[maidenGrid] is only populated by the native decoder when the third
 * field is a real grid locator (see `ft8af_looks_like_grid` in
 * ft8_grid_shape.h, which also rejects "RR73"); anything else (a report
 * exchange, which is most of a real QSO) leaves it blank, with the actual
 * value sitting unused in [extraInfo] instead -- visible only on the raw
 * message text line, which compact mode hides entirely. Confirmed on real
 * hardware: a signal-report message's row showed nothing in this slot.
 *
 * <p>The fallback only takes a short exchange token ([isShortExchangeToken]).
 * Longer third fields -- contest exchanges such as ARRL RTTY "TU; R 579 MA"
 * or Field Day "R 2A EMA", which also carry a callsignFrom -- stay on the
 * message line rather than crowding this narrow slot. Free-text messages
 * have no callsignFrom to show this next to; callers gate on that.
 */
internal fun secondaryFieldText(maidenGrid: String?, extraInfo: String?): String =
    maidenGrid?.takeIf { it.isNotEmpty() }
        ?: extraInfo?.trim()?.takeIf { isShortExchangeToken(it) }.orEmpty()

/**
 * Whether [token] is one of the short standard-message exchange tokens: a
 * report ("-04", "R+10"), "RRR", "RR73" or "73" -- at most 4 characters with
 * no spaces or ';'. Anything longer is a contest or free-form exchange.
 */
internal fun isShortExchangeToken(token: String): Boolean =
    token.isNotEmpty() && token.length <= 4 && token.none { it.isWhitespace() || it == ';' }

/** The small label chips a decode row can carry ahead of its text. */
internal enum class DecodeRowLabel { CALLING, CQ, TO_YOU }

/**
 * Which label chips a decode row shows, in display order. Expanded rows show
 * CALLING (the operator's target) plus CQ or TO YOU. Compact rows keep only
 * CALLING: the single line already starts with the message text ("CQ ..." or
 * "<my call> ..."), and the CQ/TO YOU state is also carried by the row's
 * highlight and status pill, so repeating it would only cost width.
 */
internal fun decodeRowLabels(
    isTarget: Boolean,
    isCQ: Boolean,
    isToMe: Boolean,
    compact: Boolean,
): List<DecodeRowLabel> = buildList {
    if (isTarget) add(DecodeRowLabel.CALLING)
    if (compact) return@buildList
    if (isCQ) {
        add(DecodeRowLabel.CQ)
    } else if (isToMe) {
        add(DecodeRowLabel.TO_YOU)
    }
}

/**
 * A decode's SNR as row text: "-12 dB" in the expanded metadata line, or the
 * bare "-12" in compact, whose fixed-width column drops the unit to save room.
 * "--" stands in when the decoder didn't report an SNR.
 */
internal fun decodeSnrText(hasSnr: Boolean, snr: Int, withUnit: Boolean): String {
    val value = if (hasSnr) "$snr" else "--"
    return if (withUnit) "$value dB" else value
}

/** Gap between items in the compact row. */
internal const val COMPACT_GAP_DP = 8

/** Fixed width of the compact row's trailing status-pill slot, full label. */
internal const val COMPACT_PILL_DP = 80

/** Width of the pill slot when only the pill's colored dot is shown. */
internal const val COMPACT_PILL_DOT_DP = 16

/**
 * Width the compact row keeps for its message text before giving up any
 * more metadata -- about 21 monospace characters at 12sp (~7.6dp each,
 * measured on device), enough for a full standard frame including a hashed
 * DXpedition call such as "<VP9/AH0CO> K5YT R-12". The message is the row's
 * primary content; the metadata cells are what give way. Sized no larger
 * than that so a ~384dp phone still keeps DT next to the full pill.
 */
internal const val COMPACT_MIN_MESSAGE_DP = 160

/**
 * The compact row's fixed-width metadata columns, in display order. Distance
 * is deliberately not one of them: the message text takes priority over it,
 * and the expanded view still shows it.
 */
internal enum class CompactMetaColumn(val widthDp: Int) {
    SNR(28),
    FREQ(36),
    DT(32),
    HEADING(36),
}

/**
 * How the compact row lays out its trailing cells: which metadata [columns]
 * to show (in display order) and whether the status pill shows its full
 * label or only its colored dot ([pillDotOnly]).
 */
internal data class CompactRowLayout(
    val columns: List<CompactMetaColumn>,
    val pillDotOnly: Boolean,
) {
    val pillSlotDp: Int get() = if (pillDotOnly) COMPACT_PILL_DOT_DP else COMPACT_PILL_DP
}

/**
 * The compact row's layout for a row [availableDp] wide. Every row on a
 * given screen gets the same layout, so the columns line up.
 *
 * The message text is the row's primary content, so the trailing cells give
 * way until it keeps [COMPACT_MIN_MESSAGE_DP], in this order: beam heading,
 * audio frequency, DT, then the pill's label (down to its colored dot). SNR
 * always stays, so a row too narrow even for SNR and the dot leaves the
 * message less. Beam heading is left out when [hasOperatorGrid] is false,
 * since no row could show one.
 *
 * Label chips (CALLING) are deliberately not an input: the chip takes its
 * width from that one row's message, so the layout depends only on the row
 * width and the columns sit at the same x on every row.
 */
internal fun compactRowLayout(
    availableDp: Float,
    hasOperatorGrid: Boolean,
    showHeading: Boolean,
): CompactRowLayout {
    val columns = CompactMetaColumn.values().filter {
        it != CompactMetaColumn.HEADING || (hasOperatorGrid && showHeading)
    }.toMutableList()
    var pillDotOnly = false
    fun required(): Int = COMPACT_MIN_MESSAGE_DP +
        columns.sumOf { it.widthDp + COMPACT_GAP_DP } + COMPACT_GAP_DP +
        (if (pillDotOnly) COMPACT_PILL_DOT_DP else COMPACT_PILL_DP)
    val steps = listOf<() -> Unit>(
        { columns.remove(CompactMetaColumn.HEADING) },
        { columns.remove(CompactMetaColumn.FREQ) },
        { columns.remove(CompactMetaColumn.DT) },
        { pillDotOnly = true },
    )
    for (step in steps) {
        if (required() <= availableDp) break
        step()
    }
    return CompactRowLayout(columns, pillDotOnly)
}

/** How the compact row emphasizes its message text. */
internal enum class CompactMessageEmphasis { TO_YOU, TARGET, NORMAL }

/**
 * Compact rows drop the TO YOU chip and the bold colored callsign, so a reply
 * aimed at the operator is marked by coloring the message itself instead --
 * same priority as the expanded callsign color: to-me first, then the target.
 */
internal fun compactMessageEmphasis(isToMe: Boolean, isTarget: Boolean): CompactMessageEmphasis = when {
    isToMe -> CompactMessageEmphasis.TO_YOU
    isTarget -> CompactMessageEmphasis.TARGET
    else -> CompactMessageEmphasis.NORMAL
}

private fun formatTimeAgo(utcMillis: Long, nowMillis: Long): String {
    val diff = ((nowMillis - utcMillis) / 1000L).coerceAtLeast(0)
    return when {
        diff < 5 -> "now"
        diff < 60 -> "${diff}s ago"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        else -> "${diff / 86400}d ago"
    }
}

private fun resolveLocationText(
    context: android.content.Context,
    message: Ft8Message,
): String? {
    val country = message.fromWhere?.trim()
    val state = UsStateLookup.stateFromGrid(context, message.maidenGrid)
    val isUs = country?.contains("United States", ignoreCase = true) == true
    return when {
        state != null && (isUs || country.isNullOrEmpty()) -> "$state, USA"
        state != null -> "$state, $country"
        country.isNullOrEmpty() -> null
        country == "United States of America" -> "USA"
        country == "United Kingdom" -> "UK"
        else -> country
    }
}

/**
 * The status pill reduced to its colored dot, for compact rows too narrow for
 * the label. The label stays available to TalkBack; expanded view and the QSO
 * sheet still show it in full.
 */
@Composable
private fun StatusDot(status: QsoStatus) {
    val label = stringResource(status.labelRes)
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(status.color)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun DecodeRowLabelChip(label: DecodeRowLabel) {
    when (label) {
        DecodeRowLabel.CALLING ->
            MessageLabel(text = stringResource(R.string.decode_label_calling), color = Target, bgColor = TargetSoft)
        DecodeRowLabel.CQ ->
            MessageLabel(text = stringResource(R.string.decode_label_cq), color = Accent, bgColor = AccentSoft)
        DecodeRowLabel.TO_YOU ->
            MessageLabel(text = stringResource(R.string.decode_label_to_you), color = Signal, bgColor = SignalSoft)
    }
}

/**
 * Small colored label chip (e.g., "CQ", "TO YOU").
 */
@Composable
private fun MessageLabel(
    text: String,
    color: Color,
    bgColor: Color,
) {
    val shape = RoundedCornerShape(4.dp)
    Text(
        text = text,
        modifier = Modifier
            .background(bgColor, shape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        color = color,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.06.sp,
    )
}

/**
 * Small metadata text used in the bottom info row.
 */
@Composable
private fun MetaText(
    text: String,
    color: androidx.compose.ui.graphics.Color = TextFaint,
) {
    Text(
        text = text,
        color = color,
        fontFamily = GeistMonoFamily,
        fontSize = 10.5.sp,
        letterSpacing = 0.02.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

/**
 * Whether [message] carries a Maidenhead grid field the operator hasn't logged
 * yet — i.e. a "new grid" for grid-chasing (VUCC). Requires a full 4-character
 * field (the two-letter field + two-digit square that `checkQSLGrid` keys on);
 * bare-callsign or sub-square-only frames don't count. Shared by the row's
 * NEW_GRID highlight and the Decode screen's "New Grid" filter so the pill and
 * the chip always agree on what counts as new.
 */
internal fun isNewGridStation(message: Ft8Message): Boolean {
    val grid = message.maidenGrid
    return !grid.isNullOrEmpty() &&
        grid.length >= 4 &&
        !GeneralVariables.checkQSLGrid(grid)
}

/**
 * Whether [message]'s sender carries a CQ WPX prefix (e.g. "W1", "DL0") the
 * operator hasn't logged yet — a "new prefix" for Worked-All-Prefixes chasing.
 * The prefix is derived from the callsign via [com.k1af.ft8af.callsign.WpxPrefix]
 * (the same helper that builds the worked-prefix set in the log loader), so the
 * NEW_PREFIX pill and the "New Prefix" filter always agree. Callsigns that don't
 * yield a prefix (grids, reports, hashed calls) are never counted as new.
 */
internal fun isNewPrefixStation(message: Ft8Message): Boolean {
    val prefix = com.k1af.ft8af.callsign.WpxPrefix.of(message.callsignFrom) ?: return false
    return !GeneralVariables.checkQSLPrefix(prefix)
}

/**
 * Resolve the [QsoStatus] for a given [Ft8Message] based on its state.
 *
 * Returns null when there is no useful state to surface (e.g. a station
 * mid-QSO with someone else who isn't new in any dimension) — the caller
 * should skip rendering the pill in that case.
 *
 * Priority (highest first): calling me, POTA/SOTA activation, new DXCC,
 * new zone, new state, new grid, new prefix, new band, plain CQ, already worked.
 */
internal fun resolveQsoStatus(message: Ft8Message): QsoStatus? {
    val isCQ = message.checkIsCQ()
    // Use live lookup instead of the cached field: isQSL_Callsign is set at
    // decode time, so a QSO completed after the message was decoded would leave
    // the stale field as false. The live check catches newly-worked callsigns.
    val isWorked = message.isQSL_Callsign ||
        GeneralVariables.checkQSLCallsign(message.getCallsignFrom())
    val isToMe = GeneralVariables.checkIsMyCallsign(message.callsignTo ?: "")
    val modifier = message.modifier

    val newGrid = isNewGridStation(message)
    val newPrefix = isNewPrefixStation(message)
    val newBand = !isWorked &&
        GeneralVariables.checkQSLCallsign_OtherBand(message.callsignFrom ?: "")

    // Spotted-on-pota.app activators frequently CQ without the "POTA" suffix
    // because their full call already eats the budget. Treat them as POTA so
    // hunters can recognise them at a glance. When we have the park ref and it's
    // not in the hunted log, surface it as a distinct NEW POTA.
    val parkRef = radio.ks3ckc.ft8af.pota.PotaSpotsRepository.parkRefFor(message.callsignFrom)
    val isPota = isCQ && (modifier == "POTA" || parkRef != null)
    val newPota = isPota && parkRef != null && !GeneralVariables.checkQSLPark(parkRef)

    // Each worked-before category is gated by a user toggle (Settings → Decode
    // Highlights). A disabled category falls through to the next in priority.
    //
    // The WORKED pill is only shown when worked handling is enabled AND its mode
    // is HIGHLIGHT (IGNORE leaves the station visible but unmarked; HIDE removes it
    // upstream in filterMessages). "Worked" is resolved under the configured scope
    // — see isWorkedStation / WorkedStations.kt. `isWorked` above keeps its narrow
    // current-band meaning purely so NEW_BAND (worked only on other bands) stays
    // independent of the (broader) worked-station scope.
    return when {
        isToMe -> QsoStatus.PENDING
        GeneralVariables.highlightPota && isPota ->
            if (newPota) QsoStatus.NEW_POTA else QsoStatus.POTA
        isCQ && modifier == "SOTA" -> QsoStatus.SOTA
        GeneralVariables.highlightNewDxcc && message.fromDxcc -> QsoStatus.NEW
        // A new CQ zone (Worked All Zones) outranks a new grid: only 40 zones
        // exist, so an unworked one is a rarer, more prized catch. message.fromCq
        // is set at decode time in CallsignDatabase (unworked-zone lookup) and
        // only ever true once the zone map is ready — same gating as fromDxcc.
        GeneralVariables.highlightNewZone && message.fromCq -> QsoStatus.NEW_ZONE
        // A new US state (Worked All States) outranks a new grid: WAS is one of
        // the most-chased US awards, so an unworked state is more prized than a
        // bare new grid field. message.fromNewState is set at decode time in
        // CallsignDatabase (US-grid → unworked-state lookup); null/non-US grids
        // leave it false.
        GeneralVariables.highlightNewState && message.fromNewState -> QsoStatus.NEW_STATE
        GeneralVariables.highlightNewGrid && newGrid -> QsoStatus.NEW_GRID
        // A new WPX prefix (Worked All Prefixes) ranks just below a new grid: both
        // are common early on, so they sit under the rarer DXCC/zone/state catches.
        GeneralVariables.highlightNewPrefix && newPrefix -> QsoStatus.NEW_PREFIX
        GeneralVariables.highlightNewBand && newBand -> QsoStatus.NEW_BAND
        effectiveWorkedMode() == WorkedStationMode.HIGHLIGHT &&
            isWorkedStation(message) -> QsoStatus.WORKED
        isCQ -> QsoStatus.CQ
        else -> null
    }
}

/**
 * Compute a human-readable distance string between the operator's grid and the
 * message sender's grid, if both are available.
 */
private fun computeDistanceText(message: Ft8Message): String {
    val myGrid = GeneralVariables.getMyMaidenheadGrid()
    val theirGrid = message.maidenGrid
    if (myGrid.isNullOrEmpty() || theirGrid.isNullOrEmpty()) return ""
    return try {
        val dist = MaidenheadGrid.getDist(myGrid, theirGrid)
        if (dist > 0) MaidenheadGrid.formatDist(dist) else ""
    } catch (_: Exception) {
        ""
    }
}

/**
 * Short-path beam heading (e.g. "47°") from the operator's grid to the message
 * sender's grid, or "" when either grid is unknown. Shared with the QSO sheet
 * via [computeBeamHeadings] so the row and the sheet never disagree.
 */
internal fun computeBeamHeadingText(message: Ft8Message): String {
    val headings = computeBeamHeadings(GeneralVariables.getMyMaidenheadGrid(), message.maidenGrid)
        ?: return ""
    return formatHeading(headings.shortPathDeg)
}
