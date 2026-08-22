package radio.ks3ckc.ft8af.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizes for one [TopBar] action row: the icon-button touch box, the glyph drawn
 * inside it, and the gap between neighbouring buttons.
 */
internal data class TopBarActionMetrics(
    val buttonSize: Dp,
    val iconSize: Dp,
    val spacing: Dp,
)

/**
 * Picks action-row metrics for the current screen width (in dp), using the
 * Material window-width classes: compact (< 600), medium (600-839), expanded
 * (>= 840).
 *
 * On a compact phone the row has to share a single line with a title and a
 * subtitle that grows with the locale — pt-BR "decodificados neste ciclo" and
 * es "decodificados este ciclo" are far wider than the English copy — so the
 * buttons stay small and shoulder to shoulder. A tablet has width to spare, so
 * the buttons grow and separate.
 */
internal fun topBarActionMetrics(screenWidthDp: Int): TopBarActionMetrics = when {
    screenWidthDp < 600 -> TopBarActionMetrics(buttonSize = 36.dp, iconSize = 22.dp, spacing = 0.dp)
    screenWidthDp < 840 -> TopBarActionMetrics(buttonSize = 44.dp, iconSize = 24.dp, spacing = 6.dp)
    else -> TopBarActionMetrics(buttonSize = 48.dp, iconSize = 26.dp, spacing = 12.dp)
}
