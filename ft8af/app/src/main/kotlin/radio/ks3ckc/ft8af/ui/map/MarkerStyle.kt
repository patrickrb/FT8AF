package radio.ks3ckc.ft8af.ui.map

import androidx.compose.ui.graphics.Color
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.Signal
import radio.ks3ckc.ft8af.theme.StatusNew
import radio.ks3ckc.ft8af.theme.StatusWorked

/**
 * Marker appearance, decided once per station from its decode state. Pure (only
 * touches Compose's [Color], no Android runtime), so it's unit-testable — the
 * canvas is a thin renderer that switches on [shape] and draws [fill].
 *
 * Replaces the old inline `when` that picked a single colour and unconditionally
 * drew a circle + text label for every station (which overlapped illegibly in a
 * busy opening). Now each station type gets a distinct shape, the colour is
 * tinted by SNR, and labels are decluttered to only show when zoomed in or
 * selected.
 */
internal enum class MarkerShape {
    TRIANGLE_CQ,    // calling CQ — open to be worked
    DIAMOND_TO_ME,  // directing a message at us
    CHECK_WORKED,   // already worked / QSL'd
    DOT_DEFAULT,    // any other decode
    SQUARE_PSK,     // PSK Reporter receiver that heard us
}

internal data class MarkerStyle(
    val shape: MarkerShape,
    val fill: Color,
    val radiusPx: Float,
    val showLabel: Boolean,
)

/**
 * Labels only render at/above this zoom, or when the marker is selected. Below
 * it the callsigns would overlap into an unreadable smear, so we keep just the
 * shapes until the operator zooms into a region.
 */
internal const val LABEL_ZOOM_THRESHOLD = 3.0f

/**
 * Tint a base type colour by SNR so stronger signals read brighter and weak ones
 * fade back. FT8 SNRs run roughly -24..+10 dB; map that to an alpha ramp. The
 * floor is deliberately high (0.78) so even the weakest decode is an obvious,
 * readable dot — the old 0.45 floor let weak markers wash out against the map.
 */
internal fun snrTint(base: Color, snr: Int): Color {
    val t = ((snr + 24f) / 34f).coerceIn(0f, 1f)
    val alpha = 0.78f + 0.22f * t
    return base.copy(alpha = alpha)
}

internal fun markerStyleFor(
    isCQ: Boolean,
    isToMe: Boolean,
    isWorked: Boolean,
    snr: Int,
    isSelected: Boolean,
    currentZoom: Float,
): MarkerStyle {
    // Priority: a station calling us matters most, then worked, then CQ, then plain.
    val shape = when {
        isToMe -> MarkerShape.DIAMOND_TO_ME
        isWorked -> MarkerShape.CHECK_WORKED
        isCQ -> MarkerShape.TRIANGLE_CQ
        else -> MarkerShape.DOT_DEFAULT
    }
    val base = when {
        isToMe -> Signal
        isWorked -> StatusWorked
        isCQ -> Accent
        else -> StatusNew
    }
    // Selected markers ignore the SNR fade (always full strength) and draw larger.
    // Bumped up from 3.5/5.5 so every indicator is an obvious dot, not a speck.
    val fill = if (isSelected) base else snrTint(base, snr)
    val radius = if (isSelected) 9f else 6f
    val showLabel = isSelected || currentZoom >= LABEL_ZOOM_THRESHOLD
    return MarkerStyle(shape, fill, radius, showLabel)
}
