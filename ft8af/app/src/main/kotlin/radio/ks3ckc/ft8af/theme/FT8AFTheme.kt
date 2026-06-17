package radio.ks3ckc.ft8af.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val FT8AFDarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = BgApp,
    primaryContainer = AccentSoft,
    onPrimaryContainer = AccentGlow,

    secondary = Signal,
    onSecondary = BgApp,
    secondaryContainer = SignalSoft,
    onSecondaryContainer = Signal,

    tertiary = StatusNew,
    onTertiary = BgApp,

    background = BgApp,
    onBackground = TextPrimary,

    surface = BgSurface,
    onSurface = TextPrimary,
    surfaceVariant = BgSurface2,
    onSurfaceVariant = TextMuted,
    surfaceTint = Accent,

    outline = Border,
    outlineVariant = BorderStrong,

    error = StatusBad,
    onError = TextPrimary,
    errorContainer = Color(0x24EF4444),
    onErrorContainer = StatusBad,

    inverseSurface = TextPrimary,
    inverseOnSurface = BgApp,
    inversePrimary = Accent,

    scrim = Color(0xCC000000),
)

// Additional semantic colors not in Material3 scheme
object FT8AFColors {
    val bgApp = BgApp
    val bgSurface = BgSurface
    val bgSurface2 = BgSurface2
    val bgSurface3 = BgSurface3
    val bgElev = BgElev

    val border = Border
    val borderStrong = BorderStrong
    val borderAmber = BorderAmber

    val textPrimary = TextPrimary
    val textMuted = TextMuted
    val textFaint = TextFaint
    val textDim = TextDim

    val accent = Accent
    val accentSoft = AccentSoft
    val accentGlow = AccentGlow

    val signal = Signal
    val signalSoft = SignalSoft

    val statusNew = StatusNew
    val statusNeeded = StatusNeeded
    val statusWorked = StatusWorked
    val statusConfirmed = StatusConfirmed
    val statusCq = StatusCq
    val statusWarn = StatusWarn
    val statusBad = StatusBad
}

@Composable
fun FT8AFTheme(content: @Composable () -> Unit) {
    val colorScheme = FT8AFDarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = BgApp.toArgb()
            window.navigationBarColor = BgApp.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FT8AFTypography,
        shapes = FT8AFShapes,
        content = content,
    )
}
