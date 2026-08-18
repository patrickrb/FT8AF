import SwiftUI

// MARK: - Surfaces

let bgApp      = Color(red: 0x07/255, green: 0x09/255, blue: 0x0F/255)
let bgSurface  = Color(red: 0x0E/255, green: 0x13/255, blue: 0x1E/255)
let bgSurface2 = Color(red: 0x16/255, green: 0x1C/255, blue: 0x2B/255)
let bgSurface3 = Color(red: 0x1D/255, green: 0x25/255, blue: 0x38/255)
let bgElev     = Color(red: 0x23/255, green: 0x2C/255, blue: 0x43/255)

// MARK: - Borders

let borderSubtle = Color(red: 148/255, green: 163/255, blue: 184/255).opacity(0.08)
let borderStrong = Color(red: 148/255, green: 163/255, blue: 184/255).opacity(0.16)
let borderAmber  = Color(red: 255/255, green: 175/255, blue: 94/255).opacity(0.22)

// MARK: - Text

let textPrimary = Color(red: 0xE7/255, green: 0xEC/255, blue: 0xF3/255)
let textMuted   = Color(red: 0x8A/255, green: 0x96/255, blue: 0xB1/255)
let textFaint   = Color(red: 0x5A/255, green: 0x64/255, blue: 0x7E/255)
let textDim     = Color(red: 0x3E/255, green: 0x48/255, blue: 0x62/255)

// MARK: - Accent

let accent     = Color(red: 0xFF/255, green: 0xAF/255, blue: 0x5E/255)
let accentSoft = Color(red: 0xFF/255, green: 0xAF/255, blue: 0x5E/255).opacity(0.14)
let accentGlow = Color(red: 0xFF/255, green: 0xD7/255, blue: 0xA0/255)

// MARK: - Signal

let signal     = Color(red: 0x5C/255, green: 0xD6/255, blue: 0xE8/255)
let signalSoft = Color(red: 0x5C/255, green: 0xD6/255, blue: 0xE8/255).opacity(0.14)

// MARK: - Target

let target       = Color(red: 0xF4/255, green: 0x72/255, blue: 0xB6/255)
let targetSoft   = Color(red: 0xF4/255, green: 0x72/255, blue: 0xB6/255).opacity(0.14)
let targetBorder = Color(red: 0xF4/255, green: 0x72/255, blue: 0xB6/255).opacity(0.28)

// MARK: - Status

let statusNew       = Color(red: 0xC0/255, green: 0x84/255, blue: 0xFC/255)
let statusNeeded    = Color(red: 0xFF/255, green: 0xAF/255, blue: 0x5E/255)
let statusWorked    = Color(red: 0x5C/255, green: 0xD6/255, blue: 0xE8/255)
let statusState     = Color(red: 0x2D/255, green: 0xD4/255, blue: 0xBF/255) // teal — new US state (WAS)
let statusConfirmed = Color(red: 0x4A/255, green: 0xDE/255, blue: 0x80/255)
let statusCq        = Color(red: 0xFF/255, green: 0xAF/255, blue: 0x5E/255)
let statusWarn      = Color(red: 0xFA/255, green: 0xCC/255, blue: 0x15/255)
let statusBad       = Color(red: 0xEF/255, green: 0x44/255, blue: 0x44/255)

// MARK: - Band colors

let band20m = Color(red: 0x5C/255, green: 0xD6/255, blue: 0xE8/255)
let band15m = Color(red: 0x4A/255, green: 0xDE/255, blue: 0x80/255)
let band40m = Color(red: 0xFF/255, green: 0xAF/255, blue: 0x5E/255)
let band10m = Color(red: 0xC0/255, green: 0x84/255, blue: 0xFC/255)
let band30m = Color(red: 0xF4/255, green: 0x72/255, blue: 0xB6/255)
let band17m = Color(red: 0xFA/255, green: 0xCC/255, blue: 0x15/255)
let band12m = Color(red: 0xFB/255, green: 0x71/255, blue: 0x85/255)
let band80m = Color(red: 0x60/255, green: 0xA5/255, blue: 0xFA/255)
let band160m = Color(red: 0x94/255, green: 0xA3/255, blue: 0xB8/255)
let band6m  = Color(red: 0xA7/255, green: 0x8B/255, blue: 0xFA/255)

// PSK Reporter overlay
let pskSpot = Color(red: 0xF8/255, green: 0x71/255, blue: 0x71/255)

/// Return the band color for a given band label (e.g. "20M", "40m").
func bandColor(for band: String) -> Color {
    switch band.uppercased() {
    case "160M": return band160m
    case "80M":  return band80m
    case "40M":  return band40m
    case "30M":  return band30m
    case "20M":  return band20m
    case "17M":  return band17m
    case "15M":  return band15m
    case "12M":  return band12m
    case "10M":  return band10m
    case "6M":   return band6m
    default:     return textMuted
    }
}
