package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.GeistMonoFamily
import radio.ks3ckc.ft8af.theme.TextPrimary

/**
 * FT8AF wordmark — Geist Mono 600 with an amber "8" between mono whites.
 *
 * Use sparingly: splash screen, About screen, share-card surfaces.
 */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 36.sp,
    baseColor: Color = TextPrimary,
    accentColor: Color = Accent,
) {
    Row(modifier = modifier) {
        Text(
            text = "FT",
            color = baseColor,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.04.em,
        )
        Text(
            text = "8",
            color = accentColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.04.em,
        )
        Text(
            text = "AF",
            color = baseColor,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            fontFamily = GeistMonoFamily,
            letterSpacing = 0.04.em,
        )
    }
}
