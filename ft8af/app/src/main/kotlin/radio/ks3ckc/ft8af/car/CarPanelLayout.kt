package radio.ks3ckc.ft8af.car

/** A resolved panel/banner rectangle in surface pixels. */
internal data class PanelBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Full-width status banner pinned to the top of the safe content box: spans the
 * whole surface width but starts at [contentTop] so the host's top status/action
 * chrome (which lives above the safe area) can't clip it.
 */
internal fun carBannerBoxTop(surfaceWidth: Int, contentTop: Int, barH: Float): PanelBox =
    PanelBox(left = 0f, top = contentTop.toFloat(), right = surfaceWidth.toFloat(), bottom = contentTop + barH)

/**
 * Full-width status banner pinned to the bottom of the safe content box: spans the
 * whole surface width and ends at [contentBottom], keeping it above the host's
 * bottom nav chrome.
 */
internal fun carBannerBoxBottom(surfaceWidth: Int, contentBottom: Int, barH: Float): PanelBox =
    PanelBox(left = 0f, top = contentBottom - barH, right = surfaceWidth.toFloat(), bottom = contentBottom.toFloat())
