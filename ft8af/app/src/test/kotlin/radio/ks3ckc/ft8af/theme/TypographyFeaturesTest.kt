package radio.ks3ckc.ft8af.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the slashed-zero wiring (issue #438): [slashedZero] must enable the
 * `zero` OpenType feature while preserving every other field, and the shared
 * [DATA_FONT_FEATURES] tag must be the value the theme root provides on the
 * ambient text style.
 *
 * Pure — [TextStyle] and [slashedZero] touch no Android runtime, so no
 * Robolectric runner is needed.
 */
class TypographyFeaturesTest {

    @Test
    fun `feature tag is zero`() {
        assertThat(DATA_FONT_FEATURES).isEqualTo("zero")
    }

    @Test
    fun `slashedZero enables the zero feature`() {
        val result = slashedZero(TextStyle())

        assertThat(result.fontFeatureSettings).isEqualTo("zero")
    }

    @Test
    fun `slashedZero preserves other fields`() {
        val base = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            letterSpacing = 0.02.sp,
        )

        val result = slashedZero(base)

        assertThat(result.fontFeatureSettings).isEqualTo("zero")
        assertThat(result.fontWeight).isEqualTo(FontWeight.Medium)
        assertThat(result.fontSize).isEqualTo(15.sp)
        assertThat(result.letterSpacing).isEqualTo(0.02.sp)
    }

    @Test
    fun `slashedZero overrides any pre-existing feature`() {
        val base = TextStyle(fontFeatureSettings = "tnum")

        assertThat(slashedZero(base).fontFeatureSettings).isEqualTo("zero")
    }
}
