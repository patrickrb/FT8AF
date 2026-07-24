package radio.ks3ckc.ft8af.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Theme palette
// ---------------------------------------------------------------------------
//
// The app's structural colors (surfaces, text, borders, accent/signal tints)
// are theme-switchable. Rather than migrate the ~700 call sites that reference
// the color names below, we back those names with a single snapshot-state
// palette: the names stay identical and every read — in composables AND in
// DrawScope draw lambdas — becomes reactive, because Compose tracks State reads
// in both the composition and the draw phase. Flipping [activePalette] repaints
// the whole UI in place with no activity recreate.
//
// The saturated semantic hues (Status*, Band*, Target*, PskSpot) are NOT part
// of the palette: they're small markers/dots tuned to read on either
// background, and keeping them as plain constants avoids stale static captures
// (e.g. the band-color map in LogbookScreen).

/** The switchable color set for one theme. */
data class FT8AFPalette(
    // Surfaces
    val bgApp: Color,
    val bgSurface: Color,
    val bgSurface2: Color,
    val bgSurface3: Color,
    val bgElev: Color,
    // Borders
    val border: Color,
    val borderStrong: Color,
    val borderAmber: Color,
    // Text
    val textPrimary: Color,
    val textMuted: Color,
    val textFaint: Color,
    val textDim: Color,
    // Accent
    val accent: Color,
    val accentSoft: Color,
    val accentGlow: Color,
    // Signal
    val signal: Color,
    val signalSoft: Color,
)

/** The original dark theme — the app default. */
val DarkPalette = FT8AFPalette(
    bgApp = Color(0xFF07090F),
    bgSurface = Color(0xFF0E131E),
    bgSurface2 = Color(0xFF161C2B),
    bgSurface3 = Color(0xFF1D2538),
    bgElev = Color(0xFF232C43),

    border = Color(0x1494A3B8),       // rgba(148,163,184, 0.08)
    borderStrong = Color(0x2994A3B8), // rgba(148,163,184, 0.16)
    borderAmber = Color(0x38FFAF5E),  // rgba(255,175,94, 0.22)

    textPrimary = Color(0xFFE7ECF3),
    textMuted = Color(0xFF8A96B1),
    textFaint = Color(0xFF5A647E),
    textDim = Color(0xFF3E4862),

    accent = Color(0xFFFFAF5E),
    accentSoft = Color(0x24FFAF5E),   // rgba(255,175,94, 0.14)
    accentGlow = Color(0xFFFFD7A0),

    signal = Color(0xFF5CD6E8),
    signalSoft = Color(0x245CD6E8),   // rgba(92,214,232, 0.14)
)

/** Light theme — dark text on near-white surfaces; accent/signal darkened for
 *  legibility against light backgrounds. */
val LightPalette = FT8AFPalette(
    bgApp = Color(0xFFF2F4F8),
    bgSurface = Color(0xFFFFFFFF),
    bgSurface2 = Color(0xFFEDF1F6),
    bgSurface3 = Color(0xFFE2E8F0),
    bgElev = Color(0xFFFFFFFF),

    border = Color(0x1A0F172A),       // rgba(15,23,42, 0.10)
    borderStrong = Color(0x330F172A), // rgba(15,23,42, 0.20)
    borderAmber = Color(0x4DD97706),  // rgba(217,119,6, 0.30)

    textPrimary = Color(0xFF0F172A),
    textMuted = Color(0xFF475569),
    textFaint = Color(0xFF94A3B8),
    textDim = Color(0xFFCBD5E1),

    accent = Color(0xFFD97706),       // amber-600 — readable as text on white
    accentSoft = Color(0x24D97706),   // rgba(217,119,6, 0.14)
    accentGlow = Color(0xFF9A5B0A),

    signal = Color(0xFF0E7490),       // cyan-700
    signalSoft = Color(0x240E7490),   // rgba(14,116,144, 0.14)
)

/**
 * The currently-applied palette. Snapshot-backed so every color getter below is
 * reactive in both the composition and draw phases — assigning a new palette
 * repaints the UI instantly. Set at startup (before setContent) and on theme
 * change; see [ThemePreference].
 */
var activePalette: FT8AFPalette by mutableStateOf(DarkPalette)

// ---------------------------------------------------------------------------
// Switchable colors — reactive getters over [activePalette].
// Names are unchanged so existing call sites compile as-is.
// ---------------------------------------------------------------------------

// Surfaces
val BgApp: Color get() = activePalette.bgApp
val BgSurface: Color get() = activePalette.bgSurface
val BgSurface2: Color get() = activePalette.bgSurface2
val BgSurface3: Color get() = activePalette.bgSurface3
val BgElev: Color get() = activePalette.bgElev

// Borders
val Border: Color get() = activePalette.border
val BorderStrong: Color get() = activePalette.borderStrong
val BorderAmber: Color get() = activePalette.borderAmber

// Text
val TextPrimary: Color get() = activePalette.textPrimary
val TextMuted: Color get() = activePalette.textMuted
val TextFaint: Color get() = activePalette.textFaint
val TextDim: Color get() = activePalette.textDim

// Accent
val Accent: Color get() = activePalette.accent
val AccentSoft: Color get() = activePalette.accentSoft
val AccentGlow: Color get() = activePalette.accentGlow

// Signal
val Signal: Color get() = activePalette.signal
val SignalSoft: Color get() = activePalette.signalSoft

// ---------------------------------------------------------------------------
// Theme-independent saturated hues (shared by every theme).
// ---------------------------------------------------------------------------

// Target — the station the operator is currently calling. Pink/magenta so it's
// visually distinct from CQ (amber) and TO YOU (cyan); these often stack on a
// single row during an active QSO.
val Target = Color(0xFFF472B6)
val TargetSoft = Color(0x24F472B6)   // rgba(244,114,182, 0.14)
val TargetBorder = Color(0x47F472B6) // rgba(244,114,182, 0.28)

// Status
val StatusNew = Color(0xFFC084FC)
val StatusNeeded = Color(0xFFFFAF5E)
val StatusWorked = Color(0xFF5CD6E8)
val StatusConfirmed = Color(0xFF4ADE80)
val StatusCq = Color(0xFFFFAF5E)
val StatusWarn = Color(0xFFFACC15)
// Sky-blue, distinct from the purple NEW-DXCC and cyan NEW-BAND pills, for the
// NEW-ZONE (Worked All Zones) badge.
val StatusZone = Color(0xFF60A5FA)
val StatusBad = Color(0xFFEF4444)

// Band colors (for donut chart / logbook)
val Band20m = Color(0xFF5CD6E8)
val Band15m = Color(0xFF4ADE80)
val Band40m = Color(0xFFFFAF5E)
val Band10m = Color(0xFFC084FC)
val Band30m = Color(0xFFF472B6)
val Band17m = Color(0xFFFACC15)
val Band12m = Color(0xFFFB7185)

// PSK Reporter overlay (red-coral, intentionally absent from the rest of the palette so
// auxiliary "stations hearing me" spots are visually distinct from decoded FT8 markers).
val PskSpot = Color(0xFFF87171)
