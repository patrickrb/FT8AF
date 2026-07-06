package radio.ks3ckc.ft8af.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R

/**
 * The OpenType feature tag we enable app-wide so `0` renders slashed and is
 * unambiguous against `O` — important for callsigns, grid squares, and
 * frequencies (issue #438). Inter exposes this as its `zero` stylistic feature;
 * providing it on the ambient [androidx.compose.material3.LocalTextStyle] at the
 * theme root makes every inline data `Text(...)` site inherit it without editing
 * the ~130 call sites individually.
 */
internal const val DATA_FONT_FEATURES = "zero"

/**
 * Returns [style] with the slashed-zero OpenType feature enabled, preserving all
 * other fields. Extracted as a pure, testable helper so the feature wiring can be
 * unit-tested without a running Compose environment.
 */
internal fun slashedZero(style: TextStyle): TextStyle =
    style.copy(fontFeatureSettings = DATA_FONT_FEATURES)

/**
 * Inter is a single variable TTF; we pin each Compose [FontWeight] to the matching
 * `wght` axis value via [FontVariation] so the four UI weights render crisply.
 */
@OptIn(ExperimentalTextApi::class)
val InterFamily: FontFamily = FontFamily(
    Font(
        R.font.inter_variable,
        FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.inter_variable,
        FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.inter_variable,
        FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.inter_variable,
        FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

val GeistFamily: FontFamily = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

// Kept for tabular data columns (decode rows, logbook, frequency pickers) whose
// numeric/column alignment relies on Geist Mono's fixed advance widths. Inter is
// proportional and would break that alignment, so data sites stay on Geist Mono;
// they still pick up slashed zeros via the ambient LocalTextStyle if the glyph
// exists in Geist Mono (see FT8AFTheme).
val GeistMonoFamily: FontFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
    Font(R.font.geist_mono_bold, FontWeight.Bold),
)

val FT8AFTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        letterSpacing = (-0.02).sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    displayMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        letterSpacing = (-0.02).sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    displaySmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        letterSpacing = (-0.01).sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    headlineLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.01).sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    titleLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    titleMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        letterSpacing = 0.02.sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    titleSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.02.sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    bodySmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    labelLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.04.sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    labelMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.04.sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
    labelSmall = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.06.sp,
        fontFeatureSettings = DATA_FONT_FEATURES,
    ),
)
