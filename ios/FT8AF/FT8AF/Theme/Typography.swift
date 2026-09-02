import SwiftUI
import UIKit
import CoreText

// MARK: - FT8AF typography (parity with Android theme/Type.kt)
//
// Android uses Inter (a single variable TTF whose `wght` axis is pinned per
// FontWeight) for all UI text, and the Geist Mono statics for tabular data
// columns (callsigns, grids, frequencies, SNR, clocks) whose alignment relies
// on fixed advance widths. The OpenType "zero" (slashed zero) feature is
// enabled app-wide so `0` is unambiguous against `O` (Android issue #438).
//
// iOS mirrors that here:
//   Font.ft8afUI(size:weight:)   → Inter      (falls back to SF)
//   Font.ft8afMono(size:weight:) → Geist Mono (falls back to SF Mono)
// Both enable the slashed-zero feature on the resolved font.

enum FT8AFFonts {
    /// PostScript names of the Inter variable font's named instances
    /// (verified against inter_variable.ttf's fvar table via CoreText).
    static func interName(for weight: Font.Weight) -> String {
        switch weight {
        case .medium: return "Inter-Regular_Medium"
        case .semibold: return "Inter-Regular_SemiBold"
        case .bold, .heavy, .black: return "Inter-Regular_Bold"
        default: return "Inter-Regular"
        }
    }

    /// PostScript names of the bundled Geist Mono static weights.
    static func geistMonoName(for weight: Font.Weight) -> String {
        switch weight {
        case .medium: return "GeistMono-Medium"
        case .semibold: return "GeistMono-SemiBold"
        case .bold, .heavy, .black: return "GeistMono-Bold"
        default: return "GeistMono-Regular"
        }
    }

    /// Inter `wght` axis values matching Android's FontVariation.weight pins.
    /// Used only if the named instances aren't registered on this OS.
    static func interAxisValue(for weight: Font.Weight) -> CGFloat {
        switch weight {
        case .medium: return 500
        case .semibold: return 600
        case .bold, .heavy, .black: return 700
        default: return 400
        }
    }

    /// The OpenType 'wght' variation axis identifier (four-char code).
    private static let wghtAxis = 0x7767_6874

    /// Resolves a bundled font by PostScript name with slashed zero enabled.
    static func uiFont(postScriptName: String, size: CGFloat) -> UIFont? {
        guard let base = UIFont(name: postScriptName, size: size) else { return nil }
        return withSlashedZero(base, size: size)
    }

    /// Fallback for OS versions that don't register a variable font's named
    /// instances: take the base Inter face and pin the `wght` axis directly.
    static func interVariableFallback(size: CGFloat, weight: Font.Weight) -> UIFont? {
        guard let base = UIFont(name: "Inter-Regular", size: size) else { return nil }
        let variation: [Int: CGFloat] = [wghtAxis: interAxisValue(for: weight)]
        let descriptor = base.fontDescriptor.addingAttributes(
            [UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String): variation]
        )
        return withSlashedZero(UIFont(descriptor: descriptor, size: size), size: size)
    }

    /// Enables the OpenType `zero` feature (slashed zero) on a font. Uses the
    /// CoreText OpenType feature keys, which UIFontDescriptor.featureSettings
    /// bridges through unchanged.
    private static func withSlashedZero(_ font: UIFont, size: CGFloat) -> UIFont {
        let feature: [UIFontDescriptor.FeatureKey: Any] = [
            UIFontDescriptor.FeatureKey(rawValue: kCTFontOpenTypeFeatureTag as String): "zero",
            UIFontDescriptor.FeatureKey(rawValue: kCTFontOpenTypeFeatureValue as String): 1,
        ]
        let descriptor = font.fontDescriptor.addingAttributes([.featureSettings: [feature]])
        return UIFont(descriptor: descriptor, size: size)
    }
}

extension Font {
    /// UI text — Inter (Android's UI family), slashed zero, SF fallback.
    static func ft8afUI(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        if let ui = FT8AFFonts.uiFont(postScriptName: FT8AFFonts.interName(for: weight), size: size)
            ?? FT8AFFonts.interVariableFallback(size: size, weight: weight) {
            return Font(ui)
        }
        return .system(size: size, weight: weight)
    }

    /// Data columns — Geist Mono (slashed zero by design), SF Mono fallback.
    static func ft8afMono(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        if let ui = FT8AFFonts.uiFont(postScriptName: FT8AFFonts.geistMonoName(for: weight), size: size) {
            return Font(ui)
        }
        return .system(size: size, weight: weight, design: .monospaced)
    }
}
