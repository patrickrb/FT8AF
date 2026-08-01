package radio.ks3ckc.ft8af.ui.decode

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entryAnim.value * decodeRowDimAlpha(isInQsoWithOther)
                translationY = (1f - entryAnim.value) * -12f
            }
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(shape)
            .background(bgColor, shape)
            .border(1.dp, borderColor, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 0.dp, end = 12.dp, top = if (compact) 6.dp else 10.dp, bottom = if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.Top,
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
                    .height(if (compact) 32.dp else 52.dp)
                    .background(accentColor, RoundedCornerShape(99.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
        } else {
            Spacer(modifier = Modifier.width(13.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            // Top row: label + callsign + grid + status pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // CALLING / TO YOU / CQ labels (can stack \u2014 e.g. target station
                // calling CQ shows both CALLING and CQ).
                if (isTarget) {
                    MessageLabel(text = stringResource(R.string.decode_label_calling), color = Target, bgColor = TargetSoft)
                }
                if (isCQ) {
                    MessageLabel(text = stringResource(R.string.decode_label_cq), color = Accent, bgColor = AccentSoft)
                } else if (isToMe) {
                    MessageLabel(text = stringResource(R.string.decode_label_to_you), color = Signal, bgColor = SignalSoft)
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

                // Grid locator
                val grid = message.maidenGrid ?: ""
                if (grid.isNotEmpty()) {
                    Text(
                        text = grid,
                        color = TextFaint,
                        fontFamily = GeistMonoFamily,
                        fontSize = 11.sp,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Status pill (skipped when there's no useful state to surface)
                val status = resolveQsoStatus(message)
                if (status != null) {
                    StatusPill(status = status, compact = true)
                }
            }

            Spacer(modifier = Modifier.height(if (compact) 2.dp else 4.dp))

            // Full decoded message text (canonical FT8 frame, e.g. "K1ABC W9XYZ EN37"
            // or "CQ POTA W1ABC FN42"). This is what was actually transmitted.
            if (!compact) {
                val msgText = message.getMessageText()?.trim().orEmpty()
                if (msgText.isNotEmpty()) {
                    Text(
                        text = msgText,
                        color = TextMuted,
                        fontFamily = GeistMonoFamily,
                        fontSize = 12.sp,
                        letterSpacing = 0.02.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            // Metadata row: signal bar, SNR, frequency, distance, UTC time
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SignalBar(snr = if (message.hasSnr()) message.snr else -30, width = if (compact) 22.dp else 28.dp, height = if (compact) 10.dp else 12.dp)

                MetaText(if (message.hasSnr()) "${message.snr} dB" else "-- dB")
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
                val distanceText = computeDistanceText(message)
                if (distanceText.isNotEmpty()) {
                    MetaText(distanceText)
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
                val agoText = if (nowMillis > 0L) {
                    formatTimeAgo(message.utcTime, nowMillis)
                } else ""
                if (agoText.isNotEmpty()) {
                    MetaText(agoText)
                }
            }

            // State / DX entity location line (shown on every row when known)
            if (!compact) {
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

/**
 * Alpha applied to a decode row. Rows for stations mid-QSO with a third party
 * ("noise" when the operator is scanning for someone to call) are de-emphasized
 * but kept readable; everything else renders at full opacity. See issue #332 —
 * the old 0.55 floor stacked on already-muted greys was too dark for some users.
 */
internal fun decodeRowDimAlpha(isInQsoWithOther: Boolean): Float =
    if (isInQsoWithOther) 0.8f else 1f

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
 * Resolve the [QsoStatus] for a given [Ft8Message] based on its state.
 *
 * Returns null when there is no useful state to surface (e.g. a station
 * mid-QSO with someone else who isn't new in any dimension) — the caller
 * should skip rendering the pill in that case.
 *
 * Priority (highest first): calling me, POTA/SOTA activation, new DXCC,
 * new grid, new band, plain CQ, already worked.
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
