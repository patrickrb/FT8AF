package radio.ks3ckc.ft8af

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only

/**
 * Which sides of the safe-drawing insets the app's root content is padded away from.
 *
 * The app runs edge-to-edge with the system bars hidden (see [ImmersiveBars]), and since
 * Android 15 a fullscreen window is laid out *under* the display cutout instead of being
 * letterboxed below it — so without this, the page title and top-row actions sit behind
 * the camera on notched phones. [WindowInsets.safeDrawing] is the union of system bars,
 * display cutout and IME, which makes it the right source: with the bars hidden only the
 * cutout contributes, and if the bars do come back (split-screen, a device that refuses
 * immersive mode) the status bar is covered too, with no double-padding risk because the
 * app's own scaffolds apply no insets of their own.
 *
 * Top handles the portrait notch. Horizontal handles the same notch after rotation —
 * the app rotates freely (it owns `orientation` in `configChanges`), and in landscape
 * the cutout sits on the left or right edge, where a top-only pad would leave content
 * under it. It also covers waterfall (curved-edge) insets. Bottom is deliberately left
 * out: the navigation bar is hidden, and the IME is handled by the focused field.
 */
internal val APP_CONTENT_INSET_SIDES: WindowInsetsSides =
    WindowInsetsSides.Top + WindowInsetsSides.Horizontal

/** The subset of [safeDrawing] the root content pads for — see [APP_CONTENT_INSET_SIDES]. */
internal fun appContentInsets(safeDrawing: WindowInsets): WindowInsets =
    safeDrawing.only(APP_CONTENT_INSET_SIDES)
