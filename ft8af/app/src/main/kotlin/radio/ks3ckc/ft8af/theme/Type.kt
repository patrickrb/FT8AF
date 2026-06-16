package radio.ks3ckc.ft8af.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R

val GeistFamily: FontFamily = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

val GeistMonoFamily: FontFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
    Font(R.font.geist_mono_bold, FontWeight.Bold),
)

val FT8AFTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        letterSpacing = (-0.02).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        letterSpacing = (-0.02).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        letterSpacing = (-0.01).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.01).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        letterSpacing = 0.02.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.02.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.04.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.04.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.06.sp,
    ),
)
