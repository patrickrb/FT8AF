import Foundation

/// Pure audio input-level metering for the receive level indicator — the
/// classification logic behind the waterfall's RX meter, kept off the SwiftUI
/// views so it is host-testable. Port of the Android `AudioInputLevel` class
/// (the app's single RX level classifier), same thresholds, so the two
/// platforms judge the same audio identically.
///
/// A user comparing the app to WSJT-X often has the radio's audio set too
/// quiet (weak signals fall below the decode threshold) or hot enough to clip
/// (intermodulation wrecks the decode). This turns a raw 12 kHz mono float
/// buffer (samples in roughly [-1, 1]) into a peak/RMS reading plus a coarse
/// `Status` the UI can color, so the operator can set gain the way WSJT-X's
/// meter lets them.
///
/// All thresholds are expressed in dBFS (decibels relative to full scale, so
/// 0 dB is a sample magnitude of 1.0 and every value here is <= 0). Clipping
/// is judged from the peak; "too quiet" from the RMS, which tracks the
/// noise/signal energy rather than a lone spike.
public enum AudioInputLevel {
    /// Coarse health of the input level.
    public enum Status: Equatable, Sendable {
        /// Essentially no input — disconnected cable, wrong source, or muted radio.
        case silent
        /// Audible but too quiet; weak signals sit below the decode threshold.
        case low
        /// In the healthy window — what the operator should aim for.
        case good
        /// Hot: peaks are close to full scale, raise nothing further.
        case high
        /// Peaks pinned at full scale; clipping distortion will block decodes.
        case clipping
    }

    /// Immutable result of metering one buffer.
    public struct Reading: Equatable, Sendable {
        /// Largest sample magnitude in the buffer, linear (0..~1).
        public let peak: Float
        /// Root-mean-square sample magnitude, linear.
        public let rms: Float
        /// `peak` in dBFS, clamped at `minDbfs`.
        public let peakDbfs: Float
        /// `rms` in dBFS, clamped at `minDbfs`.
        public let rmsDbfs: Float
        /// Coarse health used to color the meter.
        public let status: Status
    }

    /// Floor we report instead of -infinity for a digitally silent buffer.
    public static let minDbfs: Float = -120

    // Peak at/above this linear magnitude is treated as clipping (~ -0.13 dBFS).
    static let clipPeak: Float = 0.985
    // Peak dBFS at/above this is "hot" (clipping risk on transients).
    static let highPeakDbfs: Float = -3
    // Below this RMS the buffer carries no usable signal.
    static let silentRmsDbfs: Float = -75
    // RMS below this (but above SILENT) is too quiet for reliable weak-signal decode.
    static let lowRmsDbfs: Float = -45

    /// A reading for an empty/absent buffer: digital silence.
    public static let silence = Reading(
        peak: 0, rms: 0, peakDbfs: minDbfs, rmsDbfs: minDbfs, status: .silent
    )

    /// Meter one buffer of mono float samples in roughly [-1, 1]. An empty
    /// buffer yields a `.silent` reading rather than trapping, so the caller
    /// can meter unconditionally.
    public static func measure(_ samples: [Float]) -> Reading {
        guard !samples.isEmpty else { return silence }
        var peak: Float = 0
        var sumSquares: Double = 0
        for s in samples {
            let a = abs(s)
            if a > peak { peak = a }
            sumSquares += Double(s) * Double(s)
        }
        let rms = Float((sumSquares / Double(samples.count)).squareRoot())
        return fromPeakRms(peak: peak, rms: rms)
    }

    /// Meter from an already-accumulated linear peak/RMS pair — e.g. the
    /// metering window the waterfall loop snapshots. Single classification
    /// entry point so the "healthy gain window" lives in exactly one set of
    /// thresholds (mirrors Android issue #405).
    public static func fromPeakRms(peak: Float, rms: Float) -> Reading {
        // Sanitize non-finite inputs to digital silence: comparisons against a
        // NaN are all false, which would misreport GOOD with NaN dB values.
        let p = peak.isFinite ? max(0, peak) : 0
        let r = rms.isFinite ? max(0, rms) : 0
        let peakDbfs = toDbfs(p)
        let rmsDbfs = toDbfs(r)
        return Reading(
            peak: p, rms: r, peakDbfs: peakDbfs, rmsDbfs: rmsDbfs,
            status: classify(peak: p, peakDbfs: peakDbfs, rmsDbfs: rmsDbfs)
        )
    }

    /// Meter-bar fill fraction for a linear sample level, clamped to 0...1.
    public static func meterFraction(_ level: Float) -> Float {
        guard level.isFinite else { return 0 }
        return min(max(level, 0), 1)
    }

    /// Linear magnitude (>= 0) to dBFS, with a `minDbfs` floor for silence.
    static func toDbfs(_ linear: Float) -> Float {
        guard linear > 0 else { return minDbfs }
        let db = 20 * log10(linear)
        return db < minDbfs ? minDbfs : db
    }

    static func classify(peak: Float, peakDbfs: Float, rmsDbfs: Float) -> Status {
        if peak >= clipPeak { return .clipping }
        if peakDbfs >= highPeakDbfs { return .high }
        if rmsDbfs < silentRmsDbfs { return .silent }
        if rmsDbfs < lowRmsDbfs { return .low }
        return .good
    }
}
