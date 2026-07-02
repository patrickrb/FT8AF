package radio.ks3ckc.ft8af.theme

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test

/**
 * Guards the pure logic behind the in-app Theme picker: id parsing/round-trip,
 * the theme→palette mapping, and the display-name resolver. The Android-touching
 * bits (SharedPreferences IO, applyTheme's night-mode call) are thin wrappers and
 * are not covered here.
 *
 * No Robolectric: R.string.* are plain int constants and [FT8AFPalette] is a pure
 * data class of packed Color values.
 */
class ThemePreferenceTest {

    @Test
    fun `default theme is dark`() {
        assertThat(DEFAULT_THEME).isEqualTo(ThemeOption.DARK)
    }

    @Test
    fun `parseThemeId resolves each known id`() {
        assertThat(parseThemeId("dark")).isEqualTo(ThemeOption.DARK)
        assertThat(parseThemeId("light")).isEqualTo(ThemeOption.LIGHT)
    }

    @Test
    fun `parseThemeId falls back to default for unknown or missing ids`() {
        assertThat(parseThemeId(null)).isEqualTo(DEFAULT_THEME)
        assertThat(parseThemeId("")).isEqualTo(DEFAULT_THEME)
        assertThat(parseThemeId("solarized")).isEqualTo(DEFAULT_THEME)
    }

    @Test
    fun `idOf and parseThemeId round-trip for every theme`() {
        for (option in ThemeOption.entries) {
            assertThat(parseThemeId(idOf(option))).isEqualTo(option)
        }
    }

    @Test
    fun `theme ids are unique`() {
        val ids = ThemeOption.entries.map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `paletteFor returns the theme's palette`() {
        assertThat(paletteFor(ThemeOption.DARK)).isSameInstanceAs(DarkPalette)
        assertThat(paletteFor(ThemeOption.LIGHT)).isSameInstanceAs(LightPalette)
    }

    @Test
    fun `dark and light palettes actually differ`() {
        assertThat(LightPalette.bgApp).isNotEqualTo(DarkPalette.bgApp)
        assertThat(LightPalette.textPrimary).isNotEqualTo(DarkPalette.textPrimary)
        assertThat(LightPalette.bgSurface).isNotEqualTo(DarkPalette.bgSurface)
    }

    @Test
    fun `only light theme reports isLight`() {
        assertThat(ThemeOption.LIGHT.isLight).isTrue()
        assertThat(ThemeOption.DARK.isLight).isFalse()
    }

    @Test
    fun `currentThemeNameRes maps each theme to its display name`() {
        assertThat(currentThemeNameRes(ThemeOption.DARK)).isEqualTo(R.string.theme_name_dark)
        assertThat(currentThemeNameRes(ThemeOption.LIGHT)).isEqualTo(R.string.theme_name_light)
    }
}
