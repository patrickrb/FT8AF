package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure unit tests for [EquirectViewport] — the canvas-fit + pan math that makes
 * the map fill the screen edge-to-edge. No Android/Compose runtime is touched.
 */
class MapViewportTest {

    @Test
    fun cover_portraitCanvas_fillsHeightAndOverflowsWidth() {
        // Tall canvas: height is the long axis, so COVER scales to fill height and
        // the 2:1 world overflows horizontally (becomes pannable east-west).
        val vp = EquirectViewport(canvasW = 1080f, canvasH = 2000f)
        assertThat(vp.worldPxH).isWithin(0.01f).of(2000f)   // fills height exactly
        assertThat(vp.worldPxW).isGreaterThan(1080f)        // wider than the canvas
        assertThat(vp.worldPxW).isWithin(0.01f).of(4000f)   // 2:1 of the height
    }

    @Test
    fun cover_landscapeCanvas_fillsWidth() {
        // Wide canvas: width is the long axis, so COVER scales to fill width and
        // the world overflows vertically.
        val vp = EquirectViewport(canvasW = 2000f, canvasH = 600f)
        assertThat(vp.worldPxW).isWithin(0.01f).of(2000f)   // fills width exactly
        assertThat(vp.worldPxH).isGreaterThan(600f)         // taller than the canvas
        assertThat(vp.worldPxH).isWithin(0.01f).of(1000f)   // half the width
    }

    @Test
    fun fit_letterboxesInsteadOfFilling() {
        val vp = EquirectViewport(canvasW = 1080f, canvasH = 2000f, fit = FitMode.FIT)
        // FIT picks the smaller scale: width-driven here, so the world is no wider
        // than the canvas and shorter than it (letterboxed top/bottom).
        assertThat(vp.worldPxW).isWithin(0.01f).of(1080f)
        assertThat(vp.worldPxH).isLessThan(2000f)
    }

    @Test
    fun project_centerOfWorldIsCanvasCenter_atScale1NoPan() {
        val vp = EquirectViewport(canvasW = 1000f, canvasH = 800f)
        val p = vp.project(ProjectedPoint(0f, 0f, 0.0))
        assertThat(p.x).isWithin(1e-3f).of(500f)
        assertThat(p.y).isWithin(1e-3f).of(400f)
    }

    @Test
    fun project_appliesPanOffset() {
        val vp = EquirectViewport(canvasW = 1000f, canvasH = 800f, panX = 50f, panY = -30f)
        val p = vp.project(ProjectedPoint(0f, 0f, 0.0))
        assertThat(p.x).isWithin(1e-3f).of(550f)
        assertThat(p.y).isWithin(1e-3f).of(370f)
    }

    @Test
    fun project_rightEdgeOfWorldIsHalfWorldWidthRight() {
        val vp = EquirectViewport(canvasW = 1080f, canvasH = 2000f)
        val center = vp.project(ProjectedPoint(0f, 0f, 0.0))
        val east = vp.project(ProjectedPoint(1f, 0f, 0.0)) // lon = +180
        assertThat(east.x - center.x).isWithin(0.01f).of(vp.worldPxW / 2f)
    }

    @Test
    fun clampPan_overflowAxis_clampsToHalfOverflow() {
        val vp = EquirectViewport(canvasW = 1080f, canvasH = 2000f)
        val maxX = (vp.worldPxW - 1080f) / 2f
        val clamped = vp.clampPan(99999f, 0f)
        assertThat(clamped.x).isWithin(0.01f).of(maxX)
    }

    @Test
    fun clampPan_underflowAxis_locksToZero() {
        // Portrait COVER fills height exactly => no vertical overflow => pan locks to 0.
        // This is the load-bearing case: the old formula would have allowed dragging
        // the map up/down into empty space.
        val vp = EquirectViewport(canvasW = 1080f, canvasH = 2000f)
        assertThat(vp.maxPanY()).isWithin(0.01f).of(0f)
        val clamped = vp.clampPan(0f, 9999f)
        assertThat(clamped.y).isWithin(0.01f).of(0f)
    }

    @Test
    fun clampPan_scaledUp_allowsMorePan() {
        val base = EquirectViewport(canvasW = 1080f, canvasH = 2000f)
        val zoomed = EquirectViewport(canvasW = 1080f, canvasH = 2000f, userScale = 3f)
        // Zooming in grows the world, so both axes now overflow and allow (more) pan.
        assertThat(zoomed.maxPanX()).isGreaterThan(base.maxPanX())
        assertThat(zoomed.maxPanY()).isGreaterThan(0f)
    }
}
