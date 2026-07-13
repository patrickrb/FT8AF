/// Horizontal-axis geometry shared by the waterfall heatmap, the spectrum
/// strip, the frequency ruler and tap-to-tune.
///
/// The waterfall and spectrum columns produced by `WaterfallRowBuilder` are
/// drawn edge-to-edge across the full view width and cover the audio band
/// `0 ... displayMaxHz` (`columns(...) * binHz(...)`, i.e. the top of the
/// displayed band). Any overlay that positions a frequency on that view — the
/// TX marker, decoded message labels, the ruler ticks, tap-to-tune — must map
/// with the **same** span, or it drifts off the trace it annotates. Previously
/// the overlays used a hard-coded 3000 Hz while the data spanned 3500 Hz, so
/// every marker sat ~17% away from its signal and tapping a visible trace keyed
/// up on the wrong audio frequency.
///
/// The span is the operator's **spectrum width** setting (Android's
/// `GeneralVariables.spectrumWidth`), so every function takes it as a
/// parameter instead of baking in a constant. This is display-only: the FT8
/// decoder keeps its fixed range regardless of what's shown.
///
/// This is the single source of truth for that mapping, extracted off the
/// SwiftUI `Canvas` views so it can be unit-tested (per the project rule of
/// putting geometry in a plain, testable type).
public enum WaterfallAxis {
    /// Default top of the displayed audio band, in Hz — used before the
    /// operator's spectrum-width setting is known. Kept equal to the span the
    /// waterfall/spectrum data is built to by default.
    public static var defaultDisplayMaxHz: Float { WaterfallRowBuilder.defaultMaxHz }

    /// Lowest audio offset the operator may transmit on. Mirrors Android,
    /// which clamps the audio frequency to `100 ... spectrumWidth - 100`
    /// (`WaterfallView` tap-to-tune and the settings input share the clamp).
    public static let minTxHz: Float = 100

    /// Absolute ceiling on the TX audio offset, regardless of how wide the
    /// display is. Keeps TX inside the SSB passband (the desktop engine's
    /// `tx_audio_hz.clamp(..., 3000)`); the display band can extend above it so
    /// higher signals remain visible even though they can't be tuned.
    public static let txCeilingHz: Float = 3000

    /// Margin kept between the tunable range and the display edge, mirroring
    /// Android's `100 ... spectrumWidth - 100` clamp.
    public static let txEdgeGuardHz: Float = 100

    /// Hz step between adjacent frequency-ruler labels (Android `RulerStepHz`).
    public static let rulerStepHz = 500

    /// Highest audio offset the operator may transmit on for a given display
    /// width: `min(3000, width - 100)` — the Android clamp with the SSB
    /// passband ceiling. Never collapses below `minTxHz` even for a degenerate
    /// width, so the clamp range stays valid.
    public static func maxTxHz(displayMaxHz: Float) -> Float {
        return max(minTxHz, min(txCeilingHz, displayMaxHz - txEdgeGuardHz))
    }

    /// Horizontal position (0...1 of the view width) for a frequency.
    public static func fraction(forHz hz: Float, displayMaxHz: Float) -> Float {
        guard displayMaxHz > 0 else { return 0 }
        return hz / displayMaxHz
    }

    /// Horizontal position for a TX-marker overlay, clamped to the drawable
    /// width (0...1). `txFreqHz` can be set externally without any range check
    /// — a WSJT-X UDP reply carries a raw `deltaFreq` — so an out-of-band value
    /// would otherwise push the marker line off-canvas. Clamping keeps it pinned
    /// to the nearest edge instead of vanishing.
    public static func clampedFraction(forHz hz: Float, displayMaxHz: Float) -> Float {
        return min(max(fraction(forHz: hz, displayMaxHz: displayMaxHz), 0), 1)
    }

    /// Frequency (Hz) at a horizontal position given as a 0...1 fraction of the
    /// view width. Inverse of `fraction(forHz:displayMaxHz:)`.
    public static func hz(forFraction fraction: Float, displayMaxHz: Float) -> Float {
        return fraction * displayMaxHz
    }

    /// Tap-to-tune: the frequency under a horizontal fraction of the view,
    /// clamped to the valid TX offset range for this display width. The
    /// fraction is read against the display span so the tap lands on the trace
    /// under the finger; the result is then clamped to
    /// `100 ... min(3000, width - 100)` (the Android clamp) so it never leaves
    /// the usable passband.
    public static func tunedTxHz(forFraction fraction: Float, displayMaxHz: Float) -> Float {
        let hz = hz(forFraction: fraction, displayMaxHz: displayMaxHz)
        return min(max(hz, minTxHz), maxTxHz(displayMaxHz: displayMaxHz))
    }

    /// Ruler labels at `rulerStepHz` intervals from 0 up to `displayMaxHz`,
    /// each paired with the horizontal fraction it must sit at.
    ///
    /// A label's fraction is `hz / displayMaxHz` — the same mapping the
    /// waterfall columns use — NOT an even spread to the far edge. When the
    /// width is a multiple of the step the top tick equals it (fraction 1.0)
    /// and the gaps are uniform; otherwise the top tick sits below the far edge
    /// instead of being stretched to it. Mirrors the Android fix for ruler
    /// labels drifting when `spectrumWidth` isn't a 500 multiple (commit
    /// 647b12e8 / PR #575).
    public static func rulerTicks(displayMaxHz: Float) -> [RulerTick] {
        guard displayMaxHz > 0 else { return [RulerTick(hz: 0, fraction: 0)] }
        var ticks: [RulerTick] = []
        var hz = 0
        while Float(hz) <= displayMaxHz {
            ticks.append(RulerTick(hz: hz, fraction: Float(hz) / displayMaxHz))
            hz += rulerStepHz
        }
        return ticks
    }
}

/// A single frequency-ruler label: its frequency and where it sits across the
/// strip (0...1 of the view width).
public struct RulerTick: Equatable, Sendable {
    public let hz: Int
    public let fraction: Float
    public init(hz: Int, fraction: Float) {
        self.hz = hz
        self.fraction = fraction
    }
}
