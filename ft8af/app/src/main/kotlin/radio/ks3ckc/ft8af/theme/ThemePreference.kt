package radio.ks3ckc.ft8af.theme

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.k1af.ft8af.R

/**
 * A selectable app theme: its persisted id, the display-name resource shown in
 * the picker, the palette it applies, and whether it is a light theme (used to
 * pick night-mode + system-bar icon appearance).
 *
 * Adding a theme later is a single entry here plus its [FT8AFPalette]; the
 * picker, persistence, and startup application all derive from this enum.
 */
enum class ThemeOption(
    val id: String,
    @StringRes val nameRes: Int,
    val palette: FT8AFPalette,
    val isLight: Boolean,
) {
    DARK("dark", R.string.theme_name_dark, DarkPalette, isLight = false),
    LIGHT("light", R.string.theme_name_light, LightPalette, isLight = true),
}

/** The app default when nothing is saved yet. */
val DEFAULT_THEME: ThemeOption = ThemeOption.DARK

/** The [FT8AFPalette] for a theme (convenience over [ThemeOption.palette]). */
fun paletteFor(option: ThemeOption): FT8AFPalette = option.palette

/** Persisted id for a theme. */
fun idOf(option: ThemeOption): String = option.id

/**
 * The theme for a persisted id, falling back to [DEFAULT_THEME] for unknown or
 * missing values. Pure logic, unit-tested.
 */
fun parseThemeId(raw: String?): ThemeOption =
    ThemeOption.entries.firstOrNull { it.id == raw } ?: DEFAULT_THEME

/** The display-name resource for the given theme. Pure logic, unit-tested. */
@StringRes
fun currentThemeNameRes(option: ThemeOption): Int = option.nameRes

// ---------------------------------------------------------------------------
// Persistence + application
// ---------------------------------------------------------------------------
//
// The theme is stored in a small SharedPreferences so it can be read
// *synchronously* before setContent — the app's SQLite config (DatabaseOpr)
// loads asynchronously and would let the wrong shade flash on cold start. This
// mirrors how the framework stores the locale/night-mode outside the app DB.

private const val PREFS_NAME = "ft8af_theme"
private const val KEY_THEME = "theme"

/** The saved theme, or [DEFAULT_THEME] if none. Safe to call before UI exists. */
fun loadTheme(context: Context): ThemeOption {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return parseThemeId(prefs.getString(KEY_THEME, null))
}

/** Persist the chosen theme. */
fun saveTheme(context: Context, option: ThemeOption) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_THEME, option.id)
        .apply()
}

/**
 * Apply a theme everywhere it affects: swap the live [activePalette] (instant
 * repaint of the whole Compose UI) and set the matching night mode so the
 * pre-Compose native window background and any AppCompat surfaces match.
 */
fun applyTheme(option: ThemeOption) {
    activePalette = option.palette
    AppCompatDelegate.setDefaultNightMode(
        if (option.isLight) AppCompatDelegate.MODE_NIGHT_NO
        else AppCompatDelegate.MODE_NIGHT_YES,
    )
}
