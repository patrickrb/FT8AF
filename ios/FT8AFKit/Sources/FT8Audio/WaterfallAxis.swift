/// Horizontal-axis geometry shared by the waterfall heatmap, the spectrum
/// strip, the frequency ruler and tap-to-tune.
///
/// The waterfall and spectrum columns produced by `WaterfallRowBuilder` are
/// drawn edge-to-edge across the full view width and cover the audio band
/// `0 ... WaterfallRowBuilder.maxHz` (`columns(...) * binHz(...)`, i.e. the top
/// of the displayed band — 3500 Hz, matching the desktop engine's `WF_MAX_HZ`).
/// Any overlay that positions a frequency on that view — the TX marker, decoded
/// message labels, the ruler ticks, tap-to-tune — must map with the **same**
/// span, or it drifts off the trace it annotates. Previously the overlays used a
/// hard-coded 3000 Hz while the data spanned 3500 Hz, so every marker sat ~17%
/// away from its signal and tapping a visible trace keyed up on the wrong audio
/// frequency.
///
/// This is the single source of truth for that mapping, extracted off the
/// SwiftUI `Canvas` views so it can be unit-tested (per the project rule of
/// putting geometry in a plain, testable type).
public enum WaterfallAxis {
    /// Top of the displayed audio band, in Hz. Kept equal to the span the
    /// waterfall/spectrum data is actually built to so overlays line up with the
    /// columns.
    public static var displayMaxHz: Float { WaterfallRowBuilder.maxHz }

    /// Lowest / highest audio offset the operator may transmit on. The upper
    /// bound keeps TX inside the SSB passband and mirrors the desktop engine's
    /// `tx_audio_hz.clamp(200, 3000)`; the display band (`displayMaxHz`) extends
    /// above it so higher signals remain visible even though they can't be tuned.
    public static let minTxHz: Float = 200
    public static let maxTxHz: Float = 3000

    /// Horizontal position (0...1 of the view width) for a frequency.
    public static func fraction(forHz hz: Float) -> Float {
        let span = displayMaxHz
        guard span > 0 else { return 0 }
        return hz / span
    }

    /// Horizontal position for a TX-marker overlay, clamped to the drawable
    /// width (0...1). `txFreqHz` can be set externally without any range check
    /// — a WSJT-X UDP reply carries a raw `deltaFreq` — so an out-of-band value
    /// would otherwise push the marker line off-canvas. Clamping keeps it pinned
    /// to the nearest edge instead of vanishing.
    public static func clampedFraction(forHz hz: Float) -> Float {
        return min(max(fraction(forHz: hz), 0), 1)
    }

    /// Frequency (Hz) at a horizontal position given as a 0...1 fraction of the
    /// view width. Inverse of `fraction(forHz:)`.
    public static func hz(forFraction fraction: Float) -> Float {
        return fraction * displayMaxHz
    }

    /// Tap-to-tune: the frequency under a horizontal fraction of the view,
    /// clamped to the valid TX offset range. The fraction is read against the
    /// display span so the tap lands on the trace under the finger; the result
    /// is then clamped so it never exceeds the SSB passband.
    public static func tunedTxHz(forFraction fraction: Float) -> Float {
        return min(max(hz(forFraction: fraction), minTxHz), maxTxHz)
    }
}
