package radio.ks3ckc.ft8af.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.motion.MotionTokens
import radio.ks3ckc.ft8af.ui.motion.rememberHaptics
import com.k1af.ft8af.R

enum class FT8AFTab(@StringRes val labelRes: Int) {
    DECODE(R.string.tab_decode),
    MAP(R.string.tab_map),
    WATERFALL(R.string.tab_waterfall),
    POTA(R.string.tab_pota),
    LOG(R.string.tab_logbook),
    SETTINGS(R.string.tab_settings),
}

@Composable
fun TabBar(
    activeTab: FT8AFTab,
    onTabSelected: (FT8AFTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val tabBounds = remember { mutableStateMapOf<FT8AFTab, Pair<Float, Float>>() } // center x, width (px)

    LaunchedEffect(activeTab) {
        haptics.tick()
    }

    val activeBounds = tabBounds[activeTab]
    val targetCenter = activeBounds?.first ?: 0f
    val targetWidth = activeBounds?.second ?: 0f

    val animatedCenter by animateFloatAsState(
        targetValue = targetCenter,
        animationSpec = MotionTokens.SpringSmooth,
        label = "tab-indicator-center"
    )
    val animatedWidth by animateFloatAsState(
        targetValue = targetWidth,
        animationSpec = MotionTokens.SpringSmooth,
        label = "tab-indicator-width"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, BgApp.copy(alpha = 0.95f)),
                    startY = 0f,
                    endY = 40f,
                )
            )
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 30.dp)
            // drawBehind sits AFTER both padding modifiers so its draw scope matches the inner
            // content area — the same coordinate space the tab Columns report from
            // [LayoutCoordinates.positionInParent]. This keeps the pill horizontally aligned
            // with the active tab's icon regardless of padding values.
            .drawBehind {
                if (animatedWidth <= 0f) return@drawBehind
                val pillWidthPx = animatedWidth * 0.56f
                // The Row's inner content height = icon (~24dp) + 4dp gap + label, but for the
                // pill we want a halo around the icon only. The icon is at the top of each
                // Column with vertical padding 8dp, so center the pill on roughly the icon's
                // vertical center.
                val pillHeightPx = 28.dp.toPx()
                val iconCenterY = 8.dp.toPx() + 12.dp.toPx() // top padding + half of a 24dp icon
                val pillTop = iconCenterY - pillHeightPx / 2f
                val cx = animatedCenter
                val left = cx - pillWidthPx / 2f
                drawRoundRect(
                    color = Accent.copy(alpha = 0.14f),
                    topLeft = Offset(left, pillTop),
                    size = Size(pillWidthPx, pillHeightPx),
                    cornerRadius = CornerRadius(pillHeightPx / 2f, pillHeightPx / 2f),
                )
                // Tiny accent dot just above the pill.
                drawCircle(
                    color = Accent,
                    radius = 1.5.dp.toPx(),
                    center = Offset(cx, pillTop - 4.dp.toPx()),
                )
            },
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (tab in FT8AFTab.entries) {
            val isActive = tab == activeTab

            val color by animateColorAsState(
                targetValue = if (isActive) Accent else TextFaint,
                animationSpec = tween(MotionTokens.DurMed, easing = MotionTokens.EasingStandard),
                label = "tab-color"
            )
            val strokeWidth = if (isActive) 1.8f else 1.5f

            // Bounce animation when this tab becomes active
            val bounceScale = remember { Animatable(1f) }
            LaunchedEffect(isActive) {
                if (isActive) {
                    bounceScale.snapTo(1f)
                    bounceScale.animateTo(1.18f, tween(140, easing = MotionTokens.EasingEmphasizedDecel))
                    bounceScale.animateTo(1.12f, MotionTokens.SpringSmooth)
                } else {
                    bounceScale.animateTo(1f, MotionTokens.SpringSmooth)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned { coords ->
                        val cx = coords.positionInParent().x + coords.size.width / 2f
                        tabBounds[tab] = cx to coords.size.width.toFloat()
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onTabSelected(tab) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier.graphicsLayer {
                        scaleX = bounceScale.value
                        scaleY = bounceScale.value
                    }
                ) {
                    when (tab) {
                        FT8AFTab.DECODE -> FT8AFIcons.Decode(color = color, strokeWidth = strokeWidth)
                        FT8AFTab.MAP -> FT8AFIcons.Globe(color = color, strokeWidth = strokeWidth)
                        FT8AFTab.WATERFALL -> FT8AFIcons.Waterfall(color = color, strokeWidth = strokeWidth)
                        FT8AFTab.POTA -> FT8AFIcons.Tree(color = color, strokeWidth = strokeWidth)
                        FT8AFTab.LOG -> FT8AFIcons.Book(color = color, strokeWidth = strokeWidth)
                        FT8AFTab.SETTINGS -> FT8AFIcons.Cog(color = color, strokeWidth = strokeWidth)
                    }
                }
                Text(
                    text = stringResource(tab.labelRes),
                    color = color,
                    fontSize = 10.5.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = 0.02.sp,
                )
            }
        }
    }
}
