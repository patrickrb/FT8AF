package radio.ks3ckc.ft8af.theme

import androidx.compose.ui.graphics.Color

// Surfaces
val BgApp = Color(0xFF07090F)
val BgSurface = Color(0xFF0E131E)
val BgSurface2 = Color(0xFF161C2B)
val BgSurface3 = Color(0xFF1D2538)
val BgElev = Color(0xFF232C43)

// Borders
val Border = Color(0x1494A3B8)       // rgba(148,163,184, 0.08)
val BorderStrong = Color(0x2994A3B8) // rgba(148,163,184, 0.16)
val BorderAmber = Color(0x38FFAF5E)  // rgba(255,175,94, 0.22)

// Text
val TextPrimary = Color(0xFFE7ECF3)
val TextMuted = Color(0xFF8A96B1)
val TextFaint = Color(0xFF5A647E)
val TextDim = Color(0xFF3E4862)

// Accent
val Accent = Color(0xFFFFAF5E)
val AccentSoft = Color(0x24FFAF5E)   // rgba(255,175,94, 0.14)
val AccentGlow = Color(0xFFFFD7A0)

// Signal
val Signal = Color(0xFF5CD6E8)
val SignalSoft = Color(0x245CD6E8)   // rgba(92,214,232, 0.14)

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
